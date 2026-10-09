package dev.beamphysics.physics;

import dev.beamphysics.math.V3;

/**
 * Raycast suspension. Every substep each wheel fires a fan of rays along the suspension axis:
 * the centre ray is the classic raycast-vehicle ray; the rays ahead, behind and beside it let the
 * wheel "see" block edges early and treat a ledge of up to one block as a short ramp, so blocky
 * Minecraft terrain is drivable. The ramp ahead gets longer with speed.
 *
 * The spring/damper force is applied at the wheel, which is what pitches and rolls the body.
 */
public final class SuspensionPhysics {
    private static final double RAY_UP = 0.8;
    /** Slope of the virtual ramp behind the wheel and beside it (steep: ledges are simply dropped off). */
    private static final double RAMP_SLOPE = 1.25;
    /** Slope of the ramp ahead of a slow wheel; it gets shallower with speed so ledges do not act like walls. */
    private static final double RAMP_AHEAD = 1.1;
    private static final double RAMP_SPEED_GAIN = 0.09;
    private static final double RAMP_MIN = 0.34;
    private static final double[] FORWARD = {0.25, 0.5, 0.75, 1.0, 1.3, 1.6, 2.0, 2.4, 2.8, 3.2};
    private static final int BEHIND_COUNT = 4;

    private final VehicleConfig.Suspension cfg;
    private final double radius;
    private final double halfWidth;
    private final Terrain.RayHit tmp = new Terrain.RayHit();
    /** Debug: world-space ray segments of the last cast, [wheel][0..5] = from xyz, to xyz. */
    public final double[][] debugRays = new double[4][6];

    // result of the best ray so far (scratch state of one cast)
    private double best, bestFriction, bestGroundY, bestDx, bestDy, bestTilt;
    private V3 bestNormal;
    private boolean found;

    public SuspensionPhysics(VehicleConfig config) {
        this.cfg = config.suspension;
        this.radius = config.wheels.radius;
        this.halfWidth = config.wheels.width * 0.5;
    }

    private double probe(Terrain terrain, V3 mount, V3 up, V3 down, V3 wheelFwd, V3 wheelSide, double dx, double dy,
                         double allow, double tilt, double maxDist, double centreDist) {
        V3 o = new V3(
                mount.x + up.x * RAY_UP + wheelFwd.x * dx + wheelSide.x * dy,
                mount.y + up.y * RAY_UP + wheelFwd.y * dx + wheelSide.y * dy,
                mount.z + up.z * RAY_UP + wheelFwd.z * dx + wheelSide.z * dy);
        Terrain.RayHit hit = terrain.raycast(o, down, maxDist, tmp);
        if (hit == null || hit.startedInside) return Double.NaN;
        // anything much higher than the ground under the wheel is a wall, not a step: leave it to the body
        if (!Double.isNaN(centreDist) && centreDist - hit.distance > cfg.stepAssistHeight) return hit.distance;
        double hubDist = hit.distance - RAY_UP - radius + allow;
        if (hubDist < best) {
            best = hubDist;
            found = true;
            bestFriction = hit.box.friction;
            bestNormal = hit.normal;
            bestGroundY = o.y + down.y * hit.distance;
            bestDx = dx;
            bestDy = dy;
            bestTilt = tilt;
        }
        return hit.distance;
    }

    /**
     * Find the ground under one wheel and fill in its suspension state.
     *
     * @param travelSpeed speed of the wheel along its heading (signed), used to shape the ramp ahead
     */
    public void cast(WheelPhysics w, Terrain terrain, V3 mount, V3 up, V3 wheelFwd, V3 wheelSide, double travelSpeed) {
        V3 down = up.neg();
        best = Double.POSITIVE_INFINITY;
        found = false;
        bestNormal = up;
        double speed = Math.abs(travelSpeed);
        double ahead = Math.max(RAMP_MIN, RAMP_AHEAD / (1 + speed * RAMP_SPEED_GAIN));
        double dir = travelSpeed >= 0 ? 1 : -1;
        double reachAhead = (cfg.stepAssistHeight + 0.1) / ahead;
        double maxDist = RAY_UP + cfg.length + radius + cfg.stepAssistHeight + 0.6;

        double centre = probe(terrain, mount, up, down, wheelFwd, wheelSide, 0, 0, 0, 0, maxDist, Double.NaN);
        for (int i = 0; i < FORWARD.length; i++) {
            double a = FORWARD[i];
            if (a <= reachAhead + 0.3) {
                probe(terrain, mount, up, down, wheelFwd, wheelSide, dir * a, 0, ahead * a, 0.65 * ahead, maxDist, centre);
            }
            if (i < BEHIND_COUNT) {
                double slope = speed < 0.5 ? ahead : RAMP_SLOPE;
                probe(terrain, mount, up, down, wheelFwd, wheelSide, -dir * a, 0, slope * a, 0.5, maxDist, centre);
            }
        }
        for (double b : new double[]{halfWidth + 0.14, halfWidth + 0.4}) {
            double allow = RAMP_SLOPE * (b - halfWidth);
            probe(terrain, mount, up, down, wheelFwd, wheelSide, 0, b, allow, 0.5, maxDist, centre);
            probe(terrain, mount, up, down, wheelFwd, wheelSide, 0, -b, allow, 0.5, maxDist, centre);
        }

        double[] dbg = debugRays[w.index];
        dbg[0] = mount.x; dbg[1] = mount.y; dbg[2] = mount.z;

        if (!found || best >= cfg.length) {
            w.contact = false;
            w.compression = 0;
            w.rawCompression = 0;
            w.springForce = 0;
            w.suspensionVelocity = 0;
            w.hub = mount.addScaled(down, cfg.length);
            w.contactPoint = w.hub.addScaled(down, radius);
            w.normal = up;
            V3 end = w.contactPoint;
            dbg[3] = end.x; dbg[4] = end.y; dbg[5] = end.z;
            return;
        }
        w.contact = true;
        w.rawCompression = cfg.length - best;
        w.compression = Math.min(w.rawCompression, cfg.travel);
        w.surfaceFriction = bestFriction;
        w.groundY = bestGroundY;
        w.hub = mount.addScaled(down, cfg.length - w.compression);

        double off = Math.sqrt(bestDx * bestDx + bestDy * bestDy);
        V3 n;
        if (off < 1e-6) {
            n = bestNormal.dot(up) > 0.2 ? up.add(bestNormal).normalizeOr(up) : up;
        } else {
            // lean the support force back like the slope the wheel is effectively climbing
            double tilt = bestTilt / off;
            n = new V3(
                    up.x - (wheelFwd.x * bestDx + wheelSide.x * bestDy) * tilt,
                    up.y - (wheelFwd.y * bestDx + wheelSide.y * bestDy) * tilt,
                    up.z - (wheelFwd.z * bestDx + wheelSide.z * bestDy) * tilt).normalizeOr(up);
        }
        w.normal = n;
        w.contactPoint = w.hub.addScaled(n, -radius);
        V3 end = w.contactPoint;
        dbg[3] = end.x; dbg[4] = end.y; dbg[5] = end.z;
    }

    /**
     * Spring + damper force along the contact normal.
     *
     * @param mountVelocityAlongNormal velocity of the wheel's body point along the normal
     *                                 (negative = body moving towards the ground)
     */
    public double force(WheelPhysics w, double mountVelocityAlongNormal, double staticLoad) {
        if (!w.contact) return 0;
        double vel = Math.max(-8, Math.min(8, -mountVelocityAlongNormal));
        w.suspensionVelocity = vel;
        double spring = w.compression * cfg.springStiffness;
        double damper = vel * (vel > 0 ? cfg.damperCompression : cfg.damperRebound);
        double f = spring + damper;
        return Math.max(0, Math.min(f, staticLoad * 6));
    }
}
