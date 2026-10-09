package dev.beamphysics.physics;

import dev.beamphysics.math.V3;

/**
 * One wheel. Not a Minecraft entity - just state owned by the vehicle.
 * Holds the tyre model: slip ratio / slip angle based forces with a friction circle,
 * wheel spin, lock-up and burnout.
 */
public final class WheelPhysics {
    public final int index;
    /** Suspension mount in body space. */
    public final V3 position;
    public final double radius;
    public final double width;
    public final double inertia;
    public final double grip;

    // suspension state (written by SuspensionPhysics)
    public boolean contact;
    public double compression;
    public double rawCompression;
    public double suspensionVelocity;
    public double springForce;
    public V3 normal = V3.Y;
    public V3 hub = V3.ZERO;
    public V3 contactPoint = V3.ZERO;
    public double surfaceFriction = 1;
    public double groundY;

    // wheel state
    public double steeringAngle;
    public double wheelAngularVelocity;
    public double spinAngle;
    public double brakeForce;
    public double driveForce;
    public double slipRatio;
    public double slipAngle;
    public double forceLong;
    public double forceLat;
    public boolean detached;

    private final double peakSlip;
    private final double slideGrip;

    public WheelPhysics(int index, VehicleConfig cfg) {
        this.index = index;
        this.position = new V3(cfg.wheelX(index), cfg.suspension.mountHeight, cfg.wheelZ(index));
        this.radius = cfg.wheels.radius;
        this.width = cfg.wheels.width;
        this.inertia = cfg.wheels.inertia;
        this.grip = cfg.wheels.grip;
        this.peakSlip = Math.toRadians(cfg.wheels.peakSlipAngleDeg);
        this.slideGrip = cfg.wheels.slideGrip;
    }

    private double lateralCurve(double x) {
        if (x < 1) return Math.sin(x * Math.PI * 0.5);
        return 1 - (1 - slideGrip) * Math.min(1, (x - 1) / 2.5);
    }

    /**
     * Advance wheel spin and compute the tyre force for this substep.
     *
     * @param vLong        contact patch speed along the wheel heading
     * @param vLat         contact patch speed sideways (positive = left)
     * @param invMassLong  effective inverse mass the body presents along the heading
     * @param invMassLat   effective inverse mass the body presents sideways
     * @param driveTorque  torque from the drivetrain, Nm
     * @param brakeTorque  torque from brakes, Nm (always opposes rotation)
     * @param mu           total friction coefficient (surface x tyre x damage)
     */
    public void updateTyre(double dt, double vLong, double vLat, double invMassLong, double invMassLat,
                           double driveTorque, double brakeTorque, double mu, double camber, double rollingResistance) {
        driveForce = driveTorque / radius;
        brakeForce = brakeTorque / radius;

        // free spin-up from the drivetrain, then brakes (which can only slow the wheel)
        wheelAngularVelocity += driveTorque / inertia * dt;
        double dw = brakeTorque / inertia * dt;
        if (Math.abs(wheelAngularVelocity) <= dw) wheelAngularVelocity = 0;
        else wheelAngularVelocity -= Math.signum(wheelAngularVelocity) * dw;

        if (!contact || springForce <= 0) {
            forceLong = 0;
            forceLat = 0;
            slipRatio = 0;
            slipAngle = 0;
            wheelAngularVelocity *= (1 - 0.15 * dt);
            spinAngle += wheelAngularVelocity * dt;
            return;
        }

        double load = springForce;
        double fMax = mu * load;
        double slipSpeed = wheelAngularVelocity * radius - vLong;
        slipRatio = slipSpeed / Math.max(Math.abs(vLong), 1.5);
        slipAngle = Math.atan2(vLat, Math.max(Math.abs(vLong), 0.6));

        // force that would bring the tyre surface and the road to the same speed in one step
        double fSync = slipSpeed / (dt * (radius * radius / inertia + invMassLong));
        double latCap = Math.abs(vLat) / (dt * invMassLat);
        double fx, fy;
        if (Math.abs(fSync) <= fMax) {
            fx = fSync;
            double x = Math.abs(slipAngle) / peakSlip;
            fy = -Math.signum(vLat) * Math.min(fMax * lateralCurve(x), latCap);
            double room = Math.sqrt(Math.max(0, fMax * fMax - fx * fx));
            if (Math.abs(fy) > room) fy = Math.signum(fy) * room;
        } else {
            // tyre is sliding (spinning or locked): kinetic friction opposes the slip velocity
            double sx = slipSpeed, sy = -vLat;
            double len = Math.sqrt(sx * sx + sy * sy);
            double f = fMax * slideGrip;
            fx = f * sx / len;
            fy = f * sy / len;
            if (Math.abs(fy) > latCap) fy = Math.signum(fy) * latCap;
        }

        wheelAngularVelocity -= fx * radius / inertia * dt;

        // rolling resistance and camber thrust act on the body only
        double rr = -rollingResistance * load * Math.max(-1, Math.min(1, vLong / 0.5));
        double camberThrust = load * Math.sin(camber) * 0.45 * Math.min(1, Math.abs(vLong) / 2.0);
        forceLong = fx + rr;
        forceLat = fy + camberThrust;
        spinAngle += wheelAngularVelocity * dt;
        if (spinAngle > 1e4 || spinAngle < -1e4) spinAngle = 0;
    }
}
