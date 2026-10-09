package dev.beamphysics.physics;

import dev.beamphysics.math.V3;

/**
 * Raycast suspension. Every substep each wheel fires a fan of rays along the suspension axis:
 * the centre ray is the classic raycast-vehicle ray, the forward/back rays trace the tyre's
 * round profile (so a slab or stair is rolled over instead of hit like a wall), and the outermost
 * rays build a short virtual ramp in front of full-block ledges so Minecraft terrain is drivable.
 *
 * The spring/damper force is applied at the wheel, which is what pitches and rolls the body.
 */
public final class SuspensionPhysics {
    private static final double RAY_UP = 0.8;
    private static final double RAMP_SLOPE = 1.25;

    private final VehicleConfig.Suspension cfg;
    private final double radius;
    private final double[] sampleDx, sampleDy, sampleAllow;
    private final double maxAllow;
    private final Terrain.RayHit tmp = new Terrain.RayHit();
    /** Debug: world-space ray segments of the last cast, [wheel][0..5] = from xyz, to xyz. */
    public final double[][] debugRays = new double[4][6];

    public SuspensionPhysics(VehicleConfig config) {
        this.cfg = config.suspension;
        this.radius = config.wheels.radius;
        double r = radius, reach = cfg.stepAssistReach, hw = config.wheels.width * 0.5;
        double[] fwd = {0, 0.5 * r, 0.85 * r, r, r + reach * 0.33, r + reach * 0.66, r + reach};
        java.util.List<double[]> s = new java.util.ArrayList<>();
        for (double a : fwd) {
            double allow = a <= r ? r - Math.sqrt(Math.max(0, r * r - a * a)) : r + RAMP_SLOPE * (a - r);
            s.add(new double[]{a, 0, allow});
            if (a > 0) s.add(new double[]{-a, 0, allow});
        }
        for (double b : new double[]{hw + 0.14, hw + 0.4}) {
            double allow = RAMP_SLOPE * (b - hw);
            s.add(new double[]{0, b, allow});
            s.add(new double[]{0, -b, allow});
        }
        sampleDx = new double[s.size()];
        sampleDy = new double[s.size()];
        sampleAllow = new double[s.size()];
        double m = 0;
        for (int i = 0; i < s.size(); i++) {
            sampleDx[i] = s.get(i)[0];
            sampleDy[i] = s.get(i)[1];
            sampleAllow[i] = s.get(i)[2];
            m = Math.max(m, sampleAllow[i]);
        }
        maxAllow = m;
    }

    /** Find the ground under one wheel and fill in its suspension state. */
    public void cast(WheelPhysics w, Terrain terrain, V3 mount, V3 up, V3 wheelFwd, V3 wheelSide) {
        V3 down = up.neg();
        double best = Double.POSITIVE_INFINITY;
        int bestI = -1;
        double bestFriction = 1, bestGroundY = 0;
        V3 bestNormal = up;
        double maxDist = RAY_UP + cfg.length + radius + maxAllow;
        for (int i = 0; i < sampleDx.length; i++) {
            V3 o = new V3(
                    mount.x + up.x * RAY_UP + wheelFwd.x * sampleDx[i] + wheelSide.x * sampleDy[i],
                    mount.y + up.y * RAY_UP + wheelFwd.y * sampleDx[i] + wheelSide.y * sampleDy[i],
                    mount.z + up.z * RAY_UP + wheelFwd.z * sampleDx[i] + wheelSide.z * sampleDy[i]);
            Terrain.RayHit hit = terrain.raycast(o, down, maxDist, tmp);
            if (hit == null || hit.startedInside) continue;
            double hubDist = hit.distance - RAY_UP - radius + sampleAllow[i];
            if (hubDist < best) {
                best = hubDist;
                bestI = i;
                bestFriction = hit.box.friction;
                bestNormal = hit.normal;
                bestGroundY = o.y + down.y * hit.distance;
            }
        }
        double[] dbg = debugRays[w.index];
        V3 o = mount.addScaled(up, 0.0);
        dbg[0] = o.x; dbg[1] = o.y; dbg[2] = o.z;

        if (bestI < 0 || best >= cfg.length) {
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

        double dx = sampleDx[bestI], dy = sampleDy[bestI];
        double off = Math.sqrt(dx * dx + dy * dy);
        V3 n;
        if (off < 1e-6) {
            n = bestNormal.dot(up) > 0.2 ? up.add(bestNormal).normalizeOr(up) : up;
        } else {
            double tilt = 0.7 * Math.min(1, off / radius) / off;
            n = new V3(
                    up.x - (wheelFwd.x * dx + wheelSide.x * dy) * tilt,
                    up.y - (wheelFwd.y * dx + wheelSide.y * dy) * tilt,
                    up.z - (wheelFwd.z * dx + wheelSide.z * dy) * tilt).normalizeOr(up);
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
