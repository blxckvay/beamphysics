package dev.beamphysics.physics;

import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;

import java.util.ArrayList;
import java.util.List;

/**
 * The vehicle as a 6-DOF rigid body driven by four independently simulated wheels.
 *
 * Nothing here is animated: body roll, pitch, squat, dive, jumps and rollovers all come out of
 * forces applied at the wheel contact points and impulses at the body collision points.
 * The simulation runs in fixed substeps inside each 50 ms game tick.
 */
public final class VehiclePhysics {
    public static final double GRAVITY = 9.81;
    public static final int SUBSTEPS = 8;

    public final VehicleConfig cfg;
    public final WheelPhysics[] wheels = new WheelPhysics[4];
    public final SuspensionPhysics suspension;
    public final EnginePhysics engine;
    public final TransmissionPhysics transmission;
    public final DamageSystem damage;

    // rigid body state ------------------------------------------------------
    public final double mass;
    public final V3 centerOfMass;
    /** World position of the centre of mass. */
    public V3 comPosition = V3.ZERO;
    public Quat rotation = Quat.IDENTITY;
    public V3 linearVelocity = V3.ZERO;
    public V3 angularVelocity = V3.ZERO;
    /** Principal moments of inertia in body space (about X, Y, Z). */
    public final V3 momentOfInertia;
    private final V3 invInertia;

    // driver state ----------------------------------------------------------
    public double steer;            // -1..1, smoothed
    public double throttle;
    public double brake;
    public boolean handbrake;
    public boolean hasDriver;

    // telemetry ---------------------------------------------------------------
    public int wheelsOnGround;
    public double lastImpactSpeed;
    public boolean resting;
    private double restTimer;

    public static final class Impact {
        public final V3 position;
        public final double speed;

        Impact(V3 position, double speed) {
            this.position = position;
            this.speed = speed;
        }
    }

    public static final class Detachment {
        public final int part;
        public final V3 position;
        public final V3 velocity;

        Detachment(int part, V3 position, V3 velocity) {
            this.part = part;
            this.position = position;
            this.velocity = velocity;
        }
    }

    /** Filled during {@link #tick}; the game layer drains them for sounds, particles and debris. */
    public final List<Impact> impacts = new ArrayList<>();
    public final List<Detachment> detachments = new ArrayList<>();

    private final List<Integer> detachedScratch = new ArrayList<>();
    private final double staticWheelLoad;

    private static final class Contact {
        V3 point, normal, local;
        double penetration, closing, friction;
    }

    private final List<Contact> contacts = new ArrayList<>();

    public VehiclePhysics(VehicleConfig cfg) {
        this.cfg = cfg;
        this.mass = cfg.mass;
        this.centerOfMass = V3.of(cfg.centerOfMass);
        if (cfg.momentOfInertia != null && cfg.momentOfInertia.length == 3) {
            momentOfInertia = V3.of(cfg.momentOfInertia);
        } else {
            double w = cfg.inertiaBox[0], h = cfg.inertiaBox[1], l = cfg.inertiaBox[2];
            momentOfInertia = new V3(
                    mass * (h * h + l * l) / 12.0,
                    mass * (w * w + l * l) / 12.0,
                    mass * (w * w + h * h) / 12.0 * 1.25);
        }
        invInertia = new V3(1 / momentOfInertia.x, 1 / momentOfInertia.y, 1 / momentOfInertia.z);
        for (int i = 0; i < 4; i++) wheels[i] = new WheelPhysics(i, cfg);
        suspension = new SuspensionPhysics(cfg);
        engine = new EnginePhysics(cfg.engine);
        transmission = new TransmissionPhysics(cfg.gearbox);
        damage = new DamageSystem(cfg);
        staticWheelLoad = mass * GRAVITY / 4.0;
    }

    // ------------------------------------------------------------------ pose helpers

    /** World position of the body origin (what the entity position tracks). */
    public V3 origin() { return comPosition.sub(rotation.rotate(centerOfMass)); }

    public void setPose(V3 origin, Quat rot) {
        rotation = rot.normalize();
        comPosition = origin.add(rotation.rotate(centerOfMass));
    }

    public void stop() {
        linearVelocity = V3.ZERO;
        angularVelocity = V3.ZERO;
        for (WheelPhysics w : wheels) w.wheelAngularVelocity = 0;
    }

    public V3 toWorld(V3 local) { return origin().add(rotation.rotate(local)); }

    public V3 velocityAt(V3 worldPoint) {
        return linearVelocity.add(angularVelocity.cross(worldPoint.sub(comPosition)));
    }

    public double speed() { return linearVelocity.length(); }

    public double forwardSpeed() { return linearVelocity.dot(rotation.forward()); }

    /** Distance from the body origin down to the ground when standing on level ground. */
    public double restHeight() {
        double sag = staticWheelLoad / cfg.suspension.springStiffness;
        return cfg.suspension.length - sag + cfg.wheels.radius - cfg.suspension.mountHeight;
    }

    public void wake() {
        resting = false;
        restTimer = 0;
    }

    private V3 applyInvInertia(V3 worldVec) {
        V3 l = rotation.unrotate(worldVec);
        return rotation.rotate(new V3(l.x * invInertia.x, l.y * invInertia.y, l.z * invInertia.z));
    }

    private double invMassAt(V3 r, V3 dir) {
        V3 rxd = r.cross(dir);
        return 1.0 / mass + rxd.dot(applyInvInertia(rxd));
    }

    private void applyImpulse(V3 worldPoint, V3 impulse) {
        linearVelocity = linearVelocity.addScaled(impulse, 1.0 / mass);
        angularVelocity = angularVelocity.add(applyInvInertia(worldPoint.sub(comPosition).cross(impulse)));
    }

    // ------------------------------------------------------------------ main loop

    /** Advance one game tick (dt seconds, normally 0.05) using fixed substeps. */
    public void tick(double dt, VehicleInput input, Terrain terrain) {
        if (!terrain.valid) return;
        updateDriverInputs(dt, input);

        if (resting) {
            // parked and settled: keep only the engine alive
            double h = dt / SUBSTEPS;
            for (int i = 0; i < SUBSTEPS; i++) {
                double t = engine.torque(0, damage.enginePowerFactor());
                engine.omega += t / cfg.engine.inertia * h;
                engine.update(h, damage);
            }
            damage.updateThermal(dt, engine.running, engine.rpm(), 0, 0);
            if (throttle > 0.01 || Math.abs(input.steer) > 0.01 && hasDriver) wake();
            return;
        }

        double h = dt / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) substep(h, terrain, input);

        damage.updateThermal(dt, engine.running, engine.rpm(), engine.throttle, speed());
        sanitize();

        boolean still = speed() < 0.06 && angularVelocity.length() < 0.06 && throttle < 0.01 && wheelsOnGround >= 3;
        restTimer = still ? restTimer + dt : 0;
        if (restTimer > 1.5) {
            resting = true;
            stop();
        }
    }

    private void updateDriverInputs(double dt, VehicleInput in) {
        double fwd = hasDriver ? in.forward : 0, back = hasDriver ? in.backward : 0;
        if (transmission.automatic && transmission.gear < 0) {
            throttle = back;
            brake = fwd;
        } else {
            throttle = fwd;
            brake = back;
        }
        if (transmission.gear == 0 && transmission.automatic) throttle = Math.max(fwd, back);
        handbrake = !hasDriver || in.handbrake;

        // steering wheel has travel time and returns to centre faster than it turns
        double target = hasDriver ? Math.max(-1, Math.min(1, in.steer)) : 0;
        double rate = (Math.abs(target) < Math.abs(steer) || Math.signum(target) != Math.signum(steer))
                ? cfg.steering.steeringReturnSpeed : cfg.steering.steeringSpeed;
        double d = target - steer;
        double maxStep = rate * dt;
        steer += Math.max(-maxStep, Math.min(maxStep, d));
    }

    /** Front wheel angle for the current steering wheel position, speed sensitive. */
    public double steerAngle() {
        double max = Math.toRadians(cfg.steering.maxAngleDeg);
        double min = Math.toRadians(cfg.steering.minAngleDeg);
        double v = Math.abs(forwardSpeed());
        double lock = Math.max(min, max / (1 + v / cfg.steering.speedSensitivity));
        return steer * lock;
    }

    private void substep(double h, Terrain terrain, VehicleInput input) {
        V3 up = rotation.up(), fwd = rotation.forward(), left = rotation.left();
        V3 origin = origin();

        // ---- 1. suspension raycasts ------------------------------------------------
        double delta = steerAngle();
        wheelsOnGround = 0;
        V3[] wheelFwd = new V3[4];
        double groundRef = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            WheelPhysics w = wheels[i];
            w.detached = damage.wheelDetached[i];
            double ang = damage.wheelToe[i] * (i % 2 == 0 ? 1 : -1);
            if (i < 2) {
                // Ackermann: the inner wheel turns tighter
                double a = delta;
                if (Math.abs(delta) > 1e-4) {
                    double radius = cfg.wheelBase / Math.tan(Math.abs(delta));
                    double inner = Math.atan(cfg.wheelBase / Math.max(0.5, radius - cfg.trackWidth * 0.5));
                    double outer = Math.atan(cfg.wheelBase / (radius + cfg.trackWidth * 0.5));
                    boolean isInner = (delta > 0) == (i == 0);
                    a = Math.signum(delta) * (isInner ? inner : outer);
                }
                ang += a;
            }
            w.steeringAngle = ang;
            V3 wf = fwd.scale(Math.cos(ang)).addScaled(left, Math.sin(ang));
            wheelFwd[i] = wf;
            V3 mount = origin.add(rotation.rotate(w.position));
            if (w.detached) {
                w.contact = false;
                w.springForce = 0;
                w.compression = 0;
                w.hub = mount.addScaled(up, -cfg.suspension.length * 0.5);
                continue;
            }
            suspension.cast(w, terrain, mount, up, wf, up.cross(wf));
            if (w.contact) {
                wheelsOnGround++;
                groundRef = Math.min(groundRef, w.groundY);
            }
        }

        // ---- 2. spring forces + anti-roll bars -------------------------------------
        for (int i = 0; i < 4; i++) {
            WheelPhysics w = wheels[i];
            if (!w.contact) continue;
            V3 mount = w.hub;
            double vn = velocityAt(mount).dot(w.normal);
            w.springForce = suspension.force(w, vn, staticWheelLoad);
        }
        for (int axle = 0; axle < 2; axle++) {
            WheelPhysics l = wheels[axle * 2], r = wheels[axle * 2 + 1];
            if (l.detached || r.detached) continue;
            if (!l.contact && !r.contact) continue;
            double arb = cfg.suspension.antiRoll * (l.compression - r.compression);
            if (l.contact) l.springForce = Math.max(0, l.springForce + arb);
            if (r.contact) r.springForce = Math.max(0, r.springForce - arb);
        }

        // ---- 3. drivetrain -----------------------------------------------------------
        double ratio = transmission.ratio();
        double drivenOmega = 0, drivenInertia = 0;
        int drivenCount = 0;
        for (int i = 0; i < 4; i++) {
            if (cfg.isDriven(i) && !wheels[i].detached) {
                drivenOmega += wheels[i].wheelAngularVelocity;
                drivenInertia += wheels[i].inertia;
                drivenCount++;
            }
        }
        if (drivenCount > 0) drivenOmega /= drivenCount;

        double engineTorque = engine.torque(throttle, damage.enginePowerFactor());
        engine.omega += engineTorque / cfg.engine.inertia * h;
        double clutchTorque = 0;
        if (ratio != 0 && drivenCount > 0 && !transmission.shifting()) {
            double idle = EnginePhysics.rpmToOmega(cfg.engine.idleRPM);
            double biteStart = idle * 1.05;
            double biteSpan = idle * (0.8 + 1.4 * throttle);
            double engagement = DamageSystem.clamp01((engine.omega - biteStart) / biteSpan);
            if (!engine.running) engagement = 0;
            double sync = (engine.omega - drivenOmega * ratio)
                    / (h * (1 / cfg.engine.inertia + ratio * ratio / Math.max(0.1, drivenInertia)));
            double limit = cfg.gearbox.clutchMaxTorque * engagement;
            clutchTorque = Math.max(-limit, Math.min(limit, sync));
            engine.omega -= clutchTorque / cfg.engine.inertia * h;
        }
        engine.update(h, damage);
        double wheelTorqueTotal = clutchTorque * ratio * cfg.gearbox.efficiency;
        double shareSum = 0;
        for (int i = 0; i < 4; i++) if (!wheels[i].detached) shareSum += cfg.driveShare(i);

        // ---- 4. tyres ------------------------------------------------------------------
        V3 force = new V3(0, -mass * GRAVITY, 0);
        V3 torque = V3.ZERO;
        for (int i = 0; i < 4; i++) {
            WheelPhysics w = wheels[i];
            if (w.detached) continue;
            double drive = shareSum > 0 ? wheelTorqueTotal * cfg.driveShare(i) / shareSum : 0;
            double brakeT = brake * cfg.brakes.maxTorque * (i < 2 ? cfg.brakes.frontBias : 1 - cfg.brakes.frontBias);
            // simple ABS so that braking keeps the car steerable
            if (brake > 0 && w.contact && w.slipRatio < -0.18 && Math.abs(forwardSpeed()) > 3) brakeT *= 0.25;
            if (handbrake && i >= 2) brakeT += cfg.brakes.handbrakeTorque;
            if (damage.wheelJammed[i]) brakeT += 9000;

            if (!w.contact) {
                w.updateTyre(h, 0, 0, 1, 1, drive, brakeT, 0, 0, 0);
                continue;
            }
            V3 n = w.normal;
            V3 wf = wheelFwd[i];
            V3 fDir = wf.addScaled(n, -wf.dot(n)).normalizeOr(fwd);
            V3 sDir = n.cross(fDir);
            V3 r = w.contactPoint.sub(comPosition);
            V3 vP = linearVelocity.add(angularVelocity.cross(r));
            double vLong = vP.dot(fDir), vLat = vP.dot(sDir);
            double loadRatio = w.springForce / staticWheelLoad;
            double loadSens = Math.max(0.7, 1 - 0.08 * (loadRatio - 1));
            double mu = w.surfaceFriction * w.grip * damage.wheelGripFactor(i) * loadSens;
            double softGround = 1 + 3.5 * Math.max(0, 0.8 - w.surfaceFriction);
            double camber = damage.wheelCamber[i] * (i % 2 == 0 ? 1 : -1);
            w.updateTyre(h, vLong, vLat, 4 * invMassAt(r, fDir), 4 * invMassAt(r, sDir), drive, brakeT, mu,
                    camber, cfg.rollingResistance * softGround);
            V3 f = n.scale(w.springForce).addScaled(fDir, w.forceLong).addScaled(sDir, w.forceLat);
            force = force.add(f);
            torque = torque.add(r.cross(f));
        }

        // ---- 5. aerodynamics -------------------------------------------------------------
        double sp = linearVelocity.length();
        if (sp > 0.01) force = force.addScaled(linearVelocity, -0.5 * 1.2 * cfg.dragArea * sp);

        // ---- 6. integrate velocities ----------------------------------------------------
        linearVelocity = linearVelocity.addScaled(force, h / mass);
        angularVelocity = angularVelocity.add(applyInvInertia(torque).scale(h));
        angularVelocity = angularVelocity.scale(1 - (wheelsOnGround == 0 ? 0.05 : 0.25) * h);

        // ---- 7. bump stops ---------------------------------------------------------------
        V3 correction = V3.ZERO;
        for (int i = 0; i < 4; i++) {
            WheelPhysics w = wheels[i];
            if (!w.contact || w.rawCompression <= cfg.suspension.travel) continue;
            // the stop acts along the suspension axis: it lifts the body instead of blocking it
            V3 axis = up.add(w.normal).normalizeOr(up);
            V3 r = w.contactPoint.sub(comPosition);
            double vn = linearVelocity.add(angularVelocity.cross(r)).dot(axis);
            if (vn < 0) {
                double j = -vn / invMassAt(r, axis);
                applyImpulse(w.contactPoint, axis.scale(j));
                if (-vn > 5.5) {
                    damage.damageSuspension(i, (-vn - 5.5) * 1.6);
                    impacts.add(new Impact(w.contactPoint, -vn * 0.6));
                    if (damage.checkWheelLoss(i)) detachWheel(i);
                }
            }
            double excess = Math.min(0.03, (w.rawCompression - cfg.suspension.travel) * 0.25);
            correction = correction.addScaled(axis, excess * 0.5);
        }

        // ---- 8. body collisions -----------------------------------------------------------
        collideBody(terrain, origin, up, groundRef);

        // ---- 9. integrate pose ---------------------------------------------------------------
        comPosition = comPosition.addScaled(linearVelocity, h).add(correction).add(pendingCorrection);
        pendingCorrection = V3.ZERO;
        rotation = rotation.integrate(angularVelocity, h);

        // ---- 10. gearbox ------------------------------------------------------------------------
        // shift points follow road speed, so wheelspin does not trigger upshifts
        double wheelRpm = Math.abs(forwardSpeed() / cfg.wheels.radius * ratio) * 60 / (2 * Math.PI);
        transmission.update(h, throttle, forwardSpeed(), wheelRpm, cfg.engine, input, wheelsOnGround >= 2);
    }

    private V3 pendingCorrection = V3.ZERO;

    private void detachWheel(int i) {
        WheelPhysics w = wheels[i];
        w.detached = true;
        for (int p = 0; p < cfg.damage.parts.length; p++) {
            if (cfg.damage.parts[p].wheel == i) {
                V3 v = velocityAt(w.hub).add(rotation.left().scale((i % 2 == 0 ? 1 : -1) * 2.5)).add(0, 2.0, 0);
                detachments.add(new Detachment(p, w.hub, v));
            }
        }
    }

    // ------------------------------------------------------------------ body vs world

    private void collideBody(Terrain terrain, V3 origin, V3 up, double groundRef) {
        contacts.clear();
        boolean assist = wheelsOnGround >= 2 && up.y > 0.55;
        double stepLimit = groundRef + cfg.suspension.stepAssistHeight;

        VehicleConfig.Sphere[] spheres = cfg.collision;
        int total = spheres.length + 4;
        for (int si = 0; si < total; si++) {
            V3 local;
            double radius;
            boolean low;
            if (si < spheres.length) {
                VehicleConfig.Sphere s = spheres[si];
                local = damage.deform(new V3(s.x, s.y, s.z));
                radius = s.r;
                low = s.low;
            } else {
                // a corner that lost its wheel drags its hub on the ground
                int wi = si - spheres.length;
                if (!damage.wheelDetached[wi]) continue;
                local = new V3(cfg.wheelX(wi) * 0.9, cfg.suspension.mountHeight - 0.3, cfg.wheelZ(wi));
                radius = 0.26;
                low = false;
            }
            V3 c = origin.add(rotation.rotate(local));
            Contact best = null;
            for (int bi = 0, n = terrain.boxes.size(); bi < n; bi++) {
                TerrainBox b = terrain.boxes.get(bi);
                double qx = Math.max(b.minX, Math.min(c.x, b.maxX));
                double qy = Math.max(b.minY, Math.min(c.y, b.maxY));
                double qz = Math.max(b.minZ, Math.min(c.z, b.maxZ));
                double dx = c.x - qx, dy = c.y - qy, dz = c.z - qz;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 >= radius * radius) continue;
                V3 normal;
                double pen;
                if (d2 > 1e-10) {
                    double d = Math.sqrt(d2);
                    normal = new V3(dx / d, dy / d, dz / d);
                    pen = radius - d;
                } else {
                    // centre is inside the box: leave through the nearest face that is not buried
                    double[] depth = {c.x - b.minX, b.maxX - c.x, c.y - b.minY, b.maxY - c.y, c.z - b.minZ, b.maxZ - c.z};
                    V3[] dirs = {V3.X.neg(), V3.X, V3.Y.neg(), V3.Y, V3.Z.neg(), V3.Z};
                    int bestFace = -1;
                    double bestDepth = Double.POSITIVE_INFINITY;
                    for (int f = 0; f < 6; f++) {
                        if (depth[f] >= bestDepth) continue;
                        V3 probe = c.addScaled(dirs[f], depth[f] + 0.05);
                        if (terrain.contains(probe.x, probe.y, probe.z)) continue;
                        bestDepth = depth[f];
                        bestFace = f;
                    }
                    if (bestFace < 0) continue;
                    normal = dirs[bestFace];
                    pen = radius + bestDepth;
                    qx = c.x + normal.x * bestDepth;
                    qy = c.y + normal.y * bestDepth;
                    qz = c.z + normal.z * bestDepth;
                }
                if (low && assist && b.maxY <= stepLimit && !terrain.contains(qx, b.maxY + 0.3, qz)) continue;
                if (best == null || pen > best.penetration) {
                    if (best == null) best = new Contact();
                    best.point = new V3(qx, qy, qz);
                    best.normal = normal;
                    best.penetration = pen;
                    best.friction = b.friction;
                    best.local = local;
                }
            }
            if (best != null) contacts.add(best);
        }
        if (contacts.isEmpty()) return;

        // closing speeds before any response, used for damage
        int hard = 0;
        for (Contact c : contacts) {
            c.closing = -velocityAt(c.point).dot(c.normal);
            if (c.closing > cfg.damage.minImpactSpeed) hard++;
        }
        if (hard > 0) {
            double share = 1.0 / Math.sqrt(hard);
            double maxSpeed = 0;
            V3 where = null;
            for (Contact c : contacts) {
                if (c.closing <= cfg.damage.minImpactSpeed) continue;
                detachedScratch.clear();
                damage.applyImpact(c.local, rotation.unrotate(c.normal), c.closing, share, detachedScratch);
                for (int p : detachedScratch) {
                    V3 anchor = toWorld(V3.of(cfg.damage.parts[p].anchor));
                    V3 v = velocityAt(anchor).scale(0.6).addScaled(c.normal, 1.5 + c.closing * 0.15).add(0, 1.5, 0);
                    detachments.add(new Detachment(p, anchor, v));
                }
                if (c.closing > maxSpeed) {
                    maxSpeed = c.closing;
                    where = c.point;
                }
            }
            for (int i = 0; i < 4; i++) if (damage.checkWheelLoss(i)) detachWheel(i);
            impacts.add(new Impact(where, maxSpeed));
            lastImpactSpeed = maxSpeed;
        }

        // sequential impulses
        for (int iter = 0; iter < 3; iter++) {
            for (Contact c : contacts) {
                V3 r = c.point.sub(comPosition);
                V3 v = linearVelocity.add(angularVelocity.cross(r));
                double vn = v.dot(c.normal);
                if (vn >= 0) continue;
                double e = vn < -2.0 && iter == 0 ? 0.12 : 0.0;
                double j = -(1 + e) * vn / invMassAt(r, c.normal);
                applyImpulse(c.point, c.normal.scale(j));
                // friction
                v = linearVelocity.add(angularVelocity.cross(r));
                V3 vt = v.addScaled(c.normal, -v.dot(c.normal));
                double vtLen = vt.length();
                if (vtLen > 1e-4) {
                    V3 t = vt.scale(1 / vtLen);
                    double jt = Math.min(0.55 * c.friction * j, vtLen / invMassAt(r, t));
                    applyImpulse(c.point, t.scale(-jt));
                }
            }
        }

        // push out of penetration
        V3 push = V3.ZERO;
        for (Contact c : contacts) {
            push = push.addScaled(c.normal, Math.min(0.06, c.penetration * 0.35) / contacts.size());
        }
        pendingCorrection = pendingCorrection.add(push);
    }

    private void sanitize() {
        if (!comPosition.isFinite() || !linearVelocity.isFinite() || !angularVelocity.isFinite() || !rotation.isFinite()) {
            linearVelocity = V3.ZERO;
            angularVelocity = V3.ZERO;
            if (!rotation.isFinite()) rotation = Quat.IDENTITY;
            if (!comPosition.isFinite()) comPosition = lastGoodPosition;
        }
        lastGoodPosition = comPosition;
        double sp = linearVelocity.length();
        if (sp > 110) linearVelocity = linearVelocity.scale(110 / sp);
        double w = angularVelocity.length();
        if (w > 22) angularVelocity = angularVelocity.scale(22 / w);
        for (WheelPhysics wh : wheels) {
            if (!Double.isFinite(wh.wheelAngularVelocity)) wh.wheelAngularVelocity = 0;
            wh.wheelAngularVelocity = Math.max(-400, Math.min(400, wh.wheelAngularVelocity));
        }
        if (!Double.isFinite(engine.omega)) engine.omega = 0;
    }

    private V3 lastGoodPosition = V3.ZERO;
}
