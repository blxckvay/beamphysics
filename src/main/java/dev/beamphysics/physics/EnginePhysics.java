package dev.beamphysics.physics;

/** Engine: RPM state, torque curve, idle control, rev limiter, engine braking, starter. */
public final class EnginePhysics {
    private final VehicleConfig.Engine cfg;
    /** Crankshaft speed, rad/s. */
    public double omega;
    public boolean running;
    public double starter;
    public double throttle;

    public EnginePhysics(VehicleConfig.Engine cfg) {
        this.cfg = cfg;
    }

    public double rpm() { return omega * 60 / (2 * Math.PI); }

    public static double rpmToOmega(double rpm) { return rpm * 2 * Math.PI / 60; }

    public void toggle(DamageSystem damage) {
        if (running || starter > 0) {
            running = false;
            starter = 0;
        } else {
            starter = 0.7;
        }
    }

    /** Full-throttle torque at the given RPM from the configured curve. */
    public double curveTorque(double rpm) {
        double[][] c = cfg.torqueCurve;
        if (rpm <= c[0][0]) return c[0][1];
        for (int i = 1; i < c.length; i++) {
            if (rpm <= c[i][0]) {
                double t = (rpm - c[i - 1][0]) / (c[i][0] - c[i - 1][0]);
                return c[i - 1][1] + (c[i][1] - c[i - 1][1]) * t;
            }
        }
        return c[c.length - 1][1];
    }

    /** Net crank torque before the clutch. */
    public double torque(double throttleInput, double powerFactor) {
        double rpm = rpm();
        if (!running) {
            if (starter > 0) return 55 - cfg.friction;              // starter motor
            return -(cfg.friction + 0.02 * rpm);
        }
        double thr = throttleInput;
        if (rpm > cfg.redline) thr = 0;                              // rev limiter
        // idle controller keeps the engine alive without the driver
        double idleThr = 0;
        if (rpm < cfg.idleRPM * 1.15) idleThr = Math.min(0.35, (cfg.idleRPM * 1.15 - rpm) / cfg.idleRPM * 1.2);
        thr = Math.max(thr, idleThr);
        throttle = thr;
        double drive = curveTorque(rpm) * thr * powerFactor;
        double braking = (1 - thr) * cfg.engineBraking * (rpm / cfg.redline) + cfg.friction;
        return drive - braking;
    }

    public void update(double dt, DamageSystem damage) {
        if (starter > 0) {
            starter -= dt;
            if (starter <= 0) {
                starter = 0;
                if (!damage.engineDead()) {
                    running = true;
                    omega = Math.max(omega, rpmToOmega(cfg.idleRPM));
                }
            }
        }
        if (running && (damage.engineDead() || rpm() < 250)) running = false;
        if (omega < 0) omega = 0;
        double max = rpmToOmega(cfg.redline * 1.08);
        if (omega > max) omega = max;
    }
}
