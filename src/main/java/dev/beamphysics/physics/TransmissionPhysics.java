package dev.beamphysics.physics;

/** Gearbox: R, N, 1..n with automatic or manual shifting and an automatic clutch. */
public final class TransmissionPhysics {
    private final VehicleConfig.Gearbox cfg;
    /** -1 = reverse, 0 = neutral, 1..n = forward gears. */
    public int gear = 1;
    public boolean automatic = true;
    public double shiftTimer;
    private double sinceShift = 10;

    public TransmissionPhysics(VehicleConfig.Gearbox cfg) {
        this.cfg = cfg;
    }

    public int gearCount() { return cfg.gears.length; }

    /** Total ratio engine:wheel for the current gear (0 in neutral). */
    public double ratio() {
        if (gear == 0) return 0;
        if (gear < 0) return cfg.reverse * cfg.finalDrive;
        return cfg.gears[Math.min(gear, cfg.gears.length) - 1] * cfg.finalDrive;
    }

    public boolean shifting() { return shiftTimer > 0; }

    public void shiftTo(int g) {
        g = Math.max(-1, Math.min(cfg.gears.length, g));
        if (g == gear) return;
        gear = g;
        shiftTimer = cfg.shiftTime;
        sinceShift = 0;
    }

    public void shiftUp() { shiftTo(gear + 1); }

    public void shiftDown() { shiftTo(gear - 1); }

    public String gearName() {
        return gear < 0 ? "R" : gear == 0 ? "N" : Integer.toString(gear);
    }

    /**
     * @param forwardSpeed speed along the body forward axis, m/s
     * @param wheelRpmAsEngine engine RPM the current wheel speed would give in the current gear
     */
    public void update(double dt, double throttle, double forwardSpeed, double wheelRpmAsEngine,
                       VehicleConfig.Engine engine, VehicleInput input, boolean grounded) {
        if (shiftTimer > 0) shiftTimer -= dt;
        sinceShift += dt;
        if (!automatic) return;

        // arcade direction selection: hold S at a standstill for reverse, W for drive
        if (Math.abs(forwardSpeed) < 0.6) {
            if (gear >= 0 && input.backward > 0.1 && input.forward < 0.1) {
                shiftTo(-1);
                return;
            }
            if (gear <= 0 && input.forward > 0.1 && input.backward < 0.1) {
                shiftTo(1);
                return;
            }
        }
        if (gear < 1 || sinceShift < 0.55 || shiftTimer > 0) return;

        double up = engine.idleRPM + (engine.redline * 0.93 - engine.idleRPM) * (0.38 + 0.62 * throttle);
        double down = engine.idleRPM * 1.45 + (engine.redline * 0.42 - engine.idleRPM) * throttle;
        if (wheelRpmAsEngine > up && gear < cfg.gears.length && grounded) {
            // only shift up if the next gear will not bog the engine
            double next = wheelRpmAsEngine * cfg.gears[gear] / cfg.gears[gear - 1];
            if (next > down * 1.12) shiftUp();
        } else if (wheelRpmAsEngine < down && gear > 1) {
            shiftDown();
        }
    }
}
