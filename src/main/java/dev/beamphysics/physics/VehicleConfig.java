package dev.beamphysics.physics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Everything that makes one vehicle different from another, loaded from
 * {@code vehicles/<id>/vehicle.json}. A new car is a new JSON file - the physics code is shared.
 *
 * Body frame: +X = left, +Y = up, +Z = forward. Units: metres, kilograms, newtons, radians
 * (angles in the JSON are in degrees where the field name ends with "Deg").
 */
public final class VehicleConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String id = "test_suv";
    public String displayName = "Test SUV";

    public double mass = 1900;
    public double[] centerOfMass = {0, 0.28, 0.08};
    /** Body box (width, height, length) used to derive the moment of inertia. */
    public double[] inertiaBox = {1.9, 1.3, 4.4};
    /** Optional explicit moment of inertia (pitch/X, yaw/Y, roll/Z), kg*m^2. */
    public double[] momentOfInertia = null;
    public double wheelBase = 2.9;
    public double trackWidth = 1.72;
    /** Drag coefficient times frontal area. */
    public double dragArea = 1.15;
    public double rollingResistance = 0.014;

    public Engine engine = new Engine();
    public Gearbox gearbox = new Gearbox();
    public Suspension suspension = new Suspension();
    public Wheels wheels = new Wheels();
    public Steering steering = new Steering();
    public Brakes brakes = new Brakes();
    public Damage damage = new Damage();
    public Seat[] seats = {new Seat(0.42, 0.42, 0.05, true), new Seat(-0.42, 0.42, 0.05, false)};
    public Cameras cameras = new Cameras();
    /** Body collision spheres; generated from the damage bounds when empty. */
    public Sphere[] collision = {};

    public static final class Engine {
        public double idleRPM = 850;
        public double redline = 6200;
        public double inertia = 0.28;
        /** Pairs of [rpm, torque Nm]; linearly interpolated. */
        public double[][] torqueCurve = {
                {0, 180}, {1000, 300}, {2000, 380}, {3000, 430}, {4000, 440}, {5000, 400}, {6000, 330}, {6500, 240}
        };
        /** Braking torque at redline with closed throttle, Nm. */
        public double engineBraking = 90;
        public double friction = 18;
        public double normalTemperature = 90;
        public double overheatTemperature = 115;
        public double failTemperature = 135;
    }

    public static final class Gearbox {
        public double reverse = -3.6;
        public double[] gears = {3.9, 2.3, 1.55, 1.15, 0.88, 0.7};
        public double finalDrive = 4.1;
        public double efficiency = 0.9;
        public double shiftTime = 0.28;
        /** "awd", "fwd" or "rwd". */
        public String drive = "awd";
        /** Front share of the drive torque for AWD. */
        public double frontTorqueSplit = 0.45;
        public double clutchMaxTorque = 780;
    }

    public static final class Suspension {
        /** Height of the suspension mount in body space. */
        public double mountHeight = 0.55;
        /** Free (fully extended) length from the mount to the wheel hub. */
        public double length = 0.60;
        /** Maximum compression from the free length. */
        public double travel = 0.45;
        public double springStiffness = 31000;
        public double damperCompression = 3000;
        public double damperRebound = 4200;
        /** Anti-roll bar stiffness, N per metre of left/right difference. */
        public double antiRoll = 9000;
        /** How far ahead of the tyre the virtual ramp over block edges starts. */
        public double stepAssistReach = 0.5;
        /** Obstacles lower than this are left to the wheels instead of the bumper. */
        public double stepAssistHeight = 1.1;
    }

    public static final class Wheels {
        public double radius = 0.5;
        public double width = 0.32;
        public double mass = 28;
        public double inertia = 2.4;
        public double grip = 1.08;
        public double peakSlipAngleDeg = 8;
        public double slideGrip = 0.82;
        /** Longitudinal offset of the axle pair relative to the body origin. */
        public double axleOffset = 0;
    }

    public static final class Steering {
        public double maxAngleDeg = 36;
        /** Speed (m/s) at which the available lock is halved. */
        public double speedSensitivity = 14;
        public double minAngleDeg = 5;
        /** Full lock per second. */
        public double steeringSpeed = 2.6;
        public double steeringReturnSpeed = 4.2;
    }

    public static final class Brakes {
        public double maxTorque = 4200;
        public double frontBias = 0.62;
        public double handbrakeTorque = 6500;
    }

    public static final class Damage {
        /** Deformation lattice bounds in body space: min xyz, max xyz. */
        public double[] boundsMin = {-1.08, -0.05, -2.45};
        public double[] boundsMax = {1.08, 1.75, 2.45};
        public int[] lattice = {5, 4, 9};
        /** Closing speed (m/s) below which nothing is damaged. */
        public double minImpactSpeed = 3.0;
        /** Metres of crush per m/s over the threshold. */
        public double deformPerSpeed = 0.034;
        public double maxDeform = 0.55;
        public double deformRadius = 1.35;
        /** Part damage per m/s over the threshold. */
        public double partDamagePerSpeed = 6.0;
        public double radiatorZ = 2.0;
        public Part[] parts = {};
    }

    public static final class Part {
        public String name;
        public double[] anchor = {0, 0, 0};
        public double health = 100;
        /** A single hit stronger than this tears the part off immediately. */
        public double detachThreshold = 70;
        /** Distance over which an impact still hurts this part. */
        public double radius = 1.4;
        public double mass = 15;
        /** Index of the wheel this part represents, or -1. */
        public int wheel = -1;
    }

    public static final class Seat {
        public double x, y, z;
        public boolean driver;

        public Seat() {
        }

        public Seat(double x, double y, double z, boolean driver) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.driver = driver;
        }
    }

    public static final class Cameras {
        public double[] interior = {0.42, 1.36, 0.02};
        public double[] hood = {0, 1.42, 1.05};
        public double chaseDistance = 7.5;
        public double chaseHeight = 2.6;
        public double orbitDistance = 8.0;
    }

    public static final class Sphere {
        public double x, y, z, r;
        /** Low spheres let small steps pass so the wheels can climb them. */
        public boolean low;

        public Sphere() {
        }

        public Sphere(double x, double y, double z, double r, boolean low) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.r = r;
            this.low = low;
        }
    }

    public static VehicleConfig fromJson(String json) {
        VehicleConfig c = GSON.fromJson(json, VehicleConfig.class);
        if (c == null) c = new VehicleConfig();
        c.validate();
        return c;
    }

    public void validate() {
        if (engine == null) engine = new Engine();
        if (gearbox == null) gearbox = new Gearbox();
        if (suspension == null) suspension = new Suspension();
        if (wheels == null) wheels = new Wheels();
        if (steering == null) steering = new Steering();
        if (brakes == null) brakes = new Brakes();
        if (damage == null) damage = new Damage();
        if (cameras == null) cameras = new Cameras();
        if (seats == null || seats.length == 0) seats = new Seat[]{new Seat(0.42, 0.42, 0.05, true)};
        if (damage.parts == null) damage.parts = new Part[0];
        if (collision == null) collision = new Sphere[0];
        if (mass < 50) mass = 50;
        if (wheels.radius < 0.1) wheels.radius = 0.1;
        if (gearbox.gears == null || gearbox.gears.length == 0) gearbox.gears = new double[]{3.0};
        if (engine.torqueCurve == null || engine.torqueCurve.length == 0) engine.torqueCurve = new double[][]{{0, 300}, {6000, 300}};
        for (int i = 0; i < 3; i++) {
            damage.lattice[i] = Math.max(2, Math.min(12, damage.lattice[i]));
        }
        if (collision.length == 0) collision = defaultSpheres();
    }

    /** Wheel order: 0 = front left, 1 = front right, 2 = rear left, 3 = rear right. */
    public double wheelX(int i) { return (i % 2 == 0 ? 1 : -1) * trackWidth * 0.5; }

    public double wheelZ(int i) { return (i < 2 ? 1 : -1) * wheelBase * 0.5 + wheels.axleOffset; }

    public boolean isDriven(int i) {
        if ("fwd".equalsIgnoreCase(gearbox.drive)) return i < 2;
        if ("rwd".equalsIgnoreCase(gearbox.drive)) return i >= 2;
        return true;
    }

    public double driveShare(int i) {
        if ("fwd".equalsIgnoreCase(gearbox.drive)) return i < 2 ? 0.5 : 0;
        if ("rwd".equalsIgnoreCase(gearbox.drive)) return i >= 2 ? 0.5 : 0;
        return (i < 2 ? gearbox.frontTorqueSplit : 1 - gearbox.frontTorqueSplit) * 0.5;
    }

    private Sphere[] defaultSpheres() {
        double x0 = damage.boundsMin[0], x1 = damage.boundsMax[0];
        double y1 = damage.boundsMax[1];
        double z0 = damage.boundsMin[2], z1 = damage.boundsMax[2];
        double r = 0.24;
        double xl = x1 - r, xr = x0 + r, zf = z1 - r, zr = z0 + r;
        double yLow = 0.52, yBelt = 0.98, yRoof = y1 - r;
        double zRoofF = z1 * 0.22, zRoofR = z0 + 0.45;
        return new Sphere[]{
                // bumper level
                new Sphere(xl, yLow, zf, r, true), new Sphere(xr, yLow, zf, r, true), new Sphere(0, yLow, zf, r, true),
                new Sphere(xl, yLow, zr, r, true), new Sphere(xr, yLow, zr, r, true), new Sphere(0, yLow, zr, r, true),
                // belt line
                new Sphere(xl, yBelt, zf, r, false), new Sphere(xr, yBelt, zf, r, false), new Sphere(0, yBelt, zf, r, false),
                new Sphere(xl, yBelt, zr, r, false), new Sphere(xr, yBelt, zr, r, false), new Sphere(0, yBelt, zr, r, false),
                new Sphere(xl, yBelt, 0, r, false), new Sphere(xr, yBelt, 0, r, false),
                new Sphere(xl, yLow, 0, r, true), new Sphere(xr, yLow, 0, r, true),
                // roof
                new Sphere(xl * 0.8, yRoof, zRoofF, r, false), new Sphere(xr * 0.8, yRoof, zRoofF, r, false),
                new Sphere(xl * 0.8, yRoof, zRoofR, r, false), new Sphere(xr * 0.8, yRoof, zRoofR, r, false),
                new Sphere(0, yRoof, (zRoofF + zRoofR) * 0.5, r, false),
                new Sphere(xl * 0.8, yRoof, (zRoofF + zRoofR) * 0.5, r, false), new Sphere(xr * 0.8, yRoof, (zRoofF + zRoofR) * 0.5, r, false),
                // underbody
                new Sphere(0, 0.2, zf * 0.55, 0.22, true), new Sphere(0, 0.2, 0, 0.22, true), new Sphere(0, 0.2, zr * 0.55, 0.22, true),
        };
    }
}
