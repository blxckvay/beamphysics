package dev.beamphysics.physics;

import dev.beamphysics.math.V3;

import java.util.List;
import java.util.Random;

/**
 * Hybrid damage model:
 * <ul>
 *   <li>a coarse deformation lattice over the body - every mesh vertex and every collision sphere
 *       is displaced by trilinear interpolation of it, so the geometry really changes shape;</li>
 *   <li>named damage zones that drive gameplay effects (radiator, engine, suspension);</li>
 *   <li>detachable parts with health and a tear-off threshold;</li>
 *   <li>per-wheel alignment damage (camber, toe, jammed bearing).</li>
 * </ul>
 */
public final class DamageSystem {
    public static final int Z_FRONT_LEFT = 0, Z_FRONT_CENTER = 1, Z_FRONT_RIGHT = 2, Z_SIDE_LEFT = 3, Z_SIDE_RIGHT = 4,
            Z_REAR_LEFT = 5, Z_REAR_CENTER = 6, Z_REAR_RIGHT = 7, Z_ROOF = 8, Z_ENGINE = 9,
            Z_SUSP_FL = 10, Z_SUSP_FR = 11, Z_SUSP_RL = 12, Z_SUSP_RR = 13;
    public static final int ZONE_COUNT = 14;
    public static final String[] ZONE_NAMES = {
            "FrontLeft", "FrontCenter", "FrontRight", "SideLeft", "SideRight", "RearLeft", "RearCenter", "RearRight",
            "Roof", "Engine", "SuspensionFL", "SuspensionFR", "SuspensionRL", "SuspensionRR"
    };

    private final VehicleConfig cfg;
    public final int nx, ny, nz;
    private final double minX, minY, minZ, sizeX, sizeY, sizeZ;
    /** Node displacements, xyz per node, metres. */
    public final float[] lattice;
    /** Bumped whenever the lattice changes, so renderers know to rebuild. */
    public int latticeVersion;
    /** Bumped whenever anything else that clients need changes. */
    public int stateVersion;

    public final double[] zones = new double[ZONE_COUNT];
    public final double[] partHealth;
    public final boolean[] partDetached;

    public final double[] wheelCamber = new double[4];
    public final double[] wheelToe = new double[4];
    public final boolean[] wheelJammed = new boolean[4];
    public final boolean[] wheelDetached = new boolean[4];

    public double engineHealth = 1;
    public double radiatorHealth = 1;
    public double coolant = 1;
    public double temperature = 20;

    private final Random random = new Random(1234);

    public DamageSystem(VehicleConfig cfg) {
        this.cfg = cfg;
        VehicleConfig.Damage d = cfg.damage;
        nx = d.lattice[0];
        ny = d.lattice[1];
        nz = d.lattice[2];
        minX = d.boundsMin[0];
        minY = d.boundsMin[1];
        minZ = d.boundsMin[2];
        sizeX = d.boundsMax[0] - minX;
        sizeY = d.boundsMax[1] - minY;
        sizeZ = d.boundsMax[2] - minZ;
        lattice = new float[nx * ny * nz * 3];
        partHealth = new double[d.parts.length];
        partDetached = new boolean[d.parts.length];
        for (int i = 0; i < d.parts.length; i++) partHealth[i] = d.parts[i].health;
    }

    public int partCount() { return partHealth.length; }

    public int partIndex(String name) {
        for (int i = 0; i < cfg.damage.parts.length; i++) {
            if (cfg.damage.parts[i].name.equals(name)) return i;
        }
        return -1;
    }

    /** 0 = pristine, 1 = about to fall off. */
    public double partLooseness(int i) {
        double max = cfg.damage.parts[i].health;
        if (max <= 0) return 0;
        return clamp01(1 - partHealth[i] / max);
    }

    public void repair() {
        java.util.Arrays.fill(lattice, 0f);
        java.util.Arrays.fill(zones, 0);
        for (int i = 0; i < partHealth.length; i++) {
            partHealth[i] = cfg.damage.parts[i].health;
            partDetached[i] = false;
        }
        for (int i = 0; i < 4; i++) {
            wheelCamber[i] = 0;
            wheelToe[i] = 0;
            wheelJammed[i] = false;
            wheelDetached[i] = false;
        }
        engineHealth = 1;
        radiatorHealth = 1;
        coolant = 1;
        temperature = Math.min(temperature, cfg.engine.normalTemperature);
        latticeVersion++;
        stateVersion++;
    }

    // ------------------------------------------------------------------ lattice

    private int node(int ix, int iy, int iz) { return ((iz * ny + iy) * nx + ix) * 3; }

    /** Displacement of a body-space point caused by deformation. */
    public V3 displacement(double x, double y, double z) {
        double fx = clamp01((x - minX) / sizeX) * (nx - 1);
        double fy = clamp01((y - minY) / sizeY) * (ny - 1);
        double fz = clamp01((z - minZ) / sizeZ) * (nz - 1);
        int ix = Math.min((int) fx, nx - 2), iy = Math.min((int) fy, ny - 2), iz = Math.min((int) fz, nz - 2);
        double tx = fx - ix, ty = fy - iy, tz = fz - iz;
        double ox = 0, oy = 0, oz = 0;
        for (int c = 0; c < 8; c++) {
            int dx = c & 1, dy = (c >> 1) & 1, dz = (c >> 2) & 1;
            double w = (dx == 1 ? tx : 1 - tx) * (dy == 1 ? ty : 1 - ty) * (dz == 1 ? tz : 1 - tz);
            if (w == 0) continue;
            int n = node(ix + dx, iy + dy, iz + dz);
            ox += lattice[n] * w;
            oy += lattice[n + 1] * w;
            oz += lattice[n + 2] * w;
        }
        return new V3(ox, oy, oz);
    }

    public V3 deform(V3 p) { return p.add(displacement(p.x, p.y, p.z)); }

    private void dent(V3 p, V3 dir, double depth) {
        double radius = cfg.damage.deformRadius;
        double max = cfg.damage.maxDeform;
        boolean changed = false;
        for (int iz = 0; iz < nz; iz++) {
            double z = minZ + sizeZ * iz / (nz - 1);
            for (int iy = 0; iy < ny; iy++) {
                double y = minY + sizeY * iy / (ny - 1);
                for (int ix = 0; ix < nx; ix++) {
                    double x = minX + sizeX * ix / (nx - 1);
                    double dx = x - p.x, dy = y - p.y, dz = z - p.z;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist >= radius) continue;
                    double w = 1 - dist / radius;
                    w = w * w * (3 - 2 * w);
                    // crumple: metal never folds evenly
                    double jitter = 0.75 + random.nextDouble() * 0.5;
                    double amount = depth * w * jitter;
                    int n = node(ix, iy, iz);
                    double vx = lattice[n] + dir.x * amount + (random.nextDouble() - 0.5) * amount * 0.35;
                    double vy = lattice[n + 1] + dir.y * amount + (random.nextDouble() - 0.5) * amount * 0.35;
                    double vz = lattice[n + 2] + dir.z * amount + (random.nextDouble() - 0.5) * amount * 0.35;
                    double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                    if (len > max) {
                        double s = max / len;
                        vx *= s;
                        vy *= s;
                        vz *= s;
                    }
                    lattice[n] = (float) vx;
                    lattice[n + 1] = (float) vy;
                    lattice[n + 2] = (float) vz;
                    changed = true;
                }
            }
        }
        if (changed) latticeVersion++;
    }

    // ------------------------------------------------------------------ impacts

    public int zoneAt(V3 p) {
        double lenHalf = sizeZ * 0.5, zc = minZ + lenHalf, xc = minX + sizeX * 0.5;
        double rz = (p.z - zc) / lenHalf;       // -1 rear .. +1 front
        double rx = (p.x - xc) / (sizeX * 0.5); // -1 right .. +1 left
        double ry = (p.y - minY) / sizeY;
        if (ry > 0.8 && Math.abs(rz) < 0.55) return Z_ROOF;
        if (rz > 0.45) return rx > 0.33 ? Z_FRONT_LEFT : rx < -0.33 ? Z_FRONT_RIGHT : Z_FRONT_CENTER;
        if (rz < -0.45) return rx > 0.33 ? Z_REAR_LEFT : rx < -0.33 ? Z_REAR_RIGHT : Z_REAR_CENTER;
        return rx >= 0 ? Z_SIDE_LEFT : Z_SIDE_RIGHT;
    }

    /**
     * Register an impact.
     *
     * @param localPoint body-space point of contact
     * @param localDir   body-space direction the body is being pushed (into the car)
     * @param speed      closing speed in m/s
     * @param share      0..1 weight when several points hit at once
     * @param detached   receives indices of parts torn off by this impact
     */
    public void applyImpact(V3 localPoint, V3 localDir, double speed, double share, List<Integer> detached) {
        VehicleConfig.Damage d = cfg.damage;
        double over = speed - d.minImpactSpeed;
        if (over <= 0) return;
        over *= share;

        double depth = Math.min(d.maxDeform, over * d.deformPerSpeed);
        dent(localPoint, localDir.normalizeOr(V3.Z.neg()), depth);

        int zone = zoneAt(localPoint);
        double zoneDamage = over * 0.035;
        zones[zone] = clamp01(zones[zone] + zoneDamage);

        // radiator + engine sit behind the front panel
        if (zone == Z_FRONT_CENTER || zone == Z_FRONT_LEFT || zone == Z_FRONT_RIGHT) {
            double centre = zone == Z_FRONT_CENTER ? 1.0 : 0.5;
            radiatorHealth = clamp01(radiatorHealth - Math.max(0, over - 2.5) * 0.05 * centre);
            double engineHit = Math.max(0, over - 9) * 0.028 * centre;
            zones[Z_ENGINE] = clamp01(zones[Z_ENGINE] + engineHit);
            engineHealth = clamp01(engineHealth - engineHit);
        }

        // suspension / wheels close to the hit
        for (int i = 0; i < 4; i++) {
            if (wheelDetached[i]) continue;
            V3 hub = new V3(cfg.wheelX(i), cfg.suspension.mountHeight - cfg.suspension.length * 0.6, cfg.wheelZ(i));
            double dist = hub.distance(localPoint);
            if (dist < 1.15) damageSuspension(i, over * (1 - dist / 1.15) * 0.75);
        }

        // detachable parts
        for (int i = 0; i < d.parts.length; i++) {
            VehicleConfig.Part part = d.parts[i];
            if (partDetached[i] || part.wheel >= 0) continue;
            double dist = V3.of(part.anchor).distance(localPoint);
            if (dist >= part.radius) continue;
            double hit = over * d.partDamagePerSpeed * (1 - dist / part.radius);
            partHealth[i] -= hit;
            if (hit >= part.detachThreshold || partHealth[i] <= 0) {
                partHealth[i] = 0;
                partDetached[i] = true;
                detached.add(i);
            }
        }
        stateVersion++;
    }

    /** Damage one corner of the suspension. {@code amount} is in "m/s over threshold" units. */
    public void damageSuspension(int wheel, double amount) {
        if (amount <= 0 || wheelDetached[wheel]) return;
        int zone = Z_SUSP_FL + wheel;
        zones[zone] = clamp01(zones[zone] + amount * 0.05);
        double bend = amount * 0.012;
        wheelCamber[wheel] = clamp(wheelCamber[wheel] + bend * (0.4 + random.nextDouble()), -0.5, 0.5);
        wheelToe[wheel] = clamp(wheelToe[wheel] + bend * (random.nextDouble() - 0.5) * 1.6, -0.3, 0.3);
        if (zones[zone] > 0.7 && !wheelJammed[wheel] && random.nextDouble() < 0.5) wheelJammed[wheel] = true;
        stateVersion++;
    }

    /** True once the corner is so damaged that the wheel should come off. Marks it detached. */
    public boolean checkWheelLoss(int wheel) {
        if (wheelDetached[wheel]) return false;
        if (zones[Z_SUSP_FL + wheel] >= 1.0) {
            wheelDetached[wheel] = true;
            for (int i = 0; i < cfg.damage.parts.length; i++) {
                if (cfg.damage.parts[i].wheel == wheel) {
                    partDetached[i] = true;
                    partHealth[i] = 0;
                }
            }
            stateVersion++;
            return true;
        }
        return false;
    }

    public double wheelGripFactor(int wheel) {
        double camberLoss = Math.min(0.5, Math.abs(wheelCamber[wheel]) * 1.6);
        return (1 - 0.35 * zones[Z_SUSP_FL + wheel]) * (1 - camberLoss);
    }

    // ------------------------------------------------------------------ engine cooling

    /** Fraction of nominal engine power currently available. */
    public double enginePowerFactor() {
        VehicleConfig.Engine e = cfg.engine;
        double heat = 1;
        if (temperature > e.overheatTemperature) {
            heat = 1 - 0.7 * clamp01((temperature - e.overheatTemperature) / (e.failTemperature - e.overheatTemperature));
        }
        return heat * (0.35 + 0.65 * engineHealth);
    }

    public boolean engineDead() { return engineHealth <= 0.02; }

    public void updateThermal(double dt, boolean running, double rpm, double throttle, double speed) {
        VehicleConfig.Engine e = cfg.engine;
        // a punctured radiator loses coolant
        if (radiatorHealth < 0.75 && coolant > 0) {
            double leak = (0.75 - radiatorHealth) * (running ? 0.035 : 0.01);
            coolant = clamp01(coolant - leak * dt);
            stateVersion++;
        }
        double ambient = 20;
        if (running) {
            double load = 0.25 + 0.75 * throttle;
            double heat = (rpm / e.redline) * load * 14 + 2.0;                    // deg/s produced
            double cooling = coolant * radiatorHealth;                             // 0..1
            double airflow = 0.55 + Math.min(1.0, speed / 25.0) * 0.45;
            // thermostat: a healthy system settles around the normal temperature
            double over = Math.max(0, temperature - (e.normalTemperature - 6));
            double shed = cooling * airflow * over * 1.6 + (temperature - ambient) * 0.004;
            temperature += (heat - shed) * dt;
        } else {
            temperature += (ambient - temperature) * 0.01 * dt;
        }
        if (temperature > e.failTemperature) {
            engineHealth = clamp01(engineHealth - 0.05 * dt);
            stateVersion++;
        }
        temperature = clamp(temperature, -40, 200);
    }

    // ------------------------------------------------------------------ serialisation

    public byte[] latticeBytes() {
        byte[] out = new byte[lattice.length];
        double max = cfg.damage.maxDeform;
        for (int i = 0; i < lattice.length; i++) {
            out[i] = (byte) Math.round(clamp(lattice[i] / max, -1, 1) * 127);
        }
        return out;
    }

    public void setLatticeBytes(byte[] in) {
        if (in == null || in.length != lattice.length) return;
        double max = cfg.damage.maxDeform;
        for (int i = 0; i < lattice.length; i++) lattice[i] = (float) (in[i] / 127.0 * max);
        latticeVersion++;
    }

    public int detachedMask() {
        int m = 0;
        for (int i = 0; i < partDetached.length && i < 31; i++) if (partDetached[i]) m |= 1 << i;
        return m;
    }

    public void setDetachedMask(int m) {
        for (int i = 0; i < partDetached.length && i < 31; i++) {
            partDetached[i] = (m & (1 << i)) != 0;
            VehicleConfig.Part p = cfg.damage.parts[i];
            if (p.wheel >= 0 && p.wheel < 4) wheelDetached[p.wheel] = partDetached[i];
        }
    }

    public static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
}
