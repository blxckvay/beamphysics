package dev.beamphysics;

import dev.beamphysics.math.V3;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.VehicleConfig;
import dev.beamphysics.physics.WheelPhysics;

import java.nio.file.Files;
import java.nio.file.Path;

/** Scenario checks for the physics core. Runs without Minecraft. */
public final class PhysicsSelfTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + what);
        if (!ok) failures++;
    }

    static VehicleConfig config() throws Exception {
        Path p = Path.of("src/main/resources/data/beamphysics/vehicles/test_suv/vehicle.json");
        if (Files.exists(p)) return VehicleConfig.fromJson(Files.readString(p));
        VehicleConfig c = new VehicleConfig();
        c.validate();
        return c;
    }

    static String status(Sim s) {
        V3 o = s.car.origin();
        V3 up = s.car.rotation.up();
        return String.format("t=%5.2f pos=(%7.2f %6.2f %7.2f) v=%6.2f m/s (%5.1f km/h) rpm=%5.0f gear=%s up.y=%5.2f wheels=%d",
                s.time, o.x, o.y, o.z, s.car.speed(), s.car.speed() * 3.6, s.car.engine.rpm(),
                s.car.transmission.gearName(), up.y, s.car.wheelsOnGround);
    }

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println(failures == 0 ? "\nALL PHYSICS CHECKS PASSED" : "\nFAILURES: " + failures);
        if (failures > 0) System.exit(1);
    }

    public static int runAll() throws Exception {
        failures = 0;
        settle();
        accelerateAndBrake();
        cornering();
        independentSuspension();
        stepClimb();
        hill();
        ledgeAtSpeed();
        rollingTerrain();
        cliff();
        wallCrash();
        reverse();
        ice();
        overheating();
        return failures;
    }

    static void settle() throws Exception {
        System.out.println("== settle on flat ground");
        Sim s = new Sim(config());
        s.car.hasDriver = false;
        s.place(0.5, 0.5, 0);
        s.run(4);
        System.out.println("  " + status(s));
        double h = s.car.origin().y;
        check(Math.abs(h - s.car.restHeight()) < 0.03, "ride height " + String.format("%.3f", h) + " ~ " + String.format("%.3f", s.car.restHeight()));
        check(s.car.resting, "comes to rest and sleeps");
        check(s.car.rotation.up().y > 0.999, "stays level");
        for (WheelPhysics w : s.car.wheels) check(w.contact, "wheel " + w.index + " on ground, compression " + String.format("%.3f", w.compression));
    }

    static void accelerateAndBrake() throws Exception {
        System.out.println("== acceleration, gears, braking");
        Sim s = new Sim(config());
        s.place(0.5, 0.5, 0);
        s.run(1);
        s.input.forward = 1;
        double t100 = -1;
        int maxGear = 1;
        double minPitch = 0;
        for (int i = 0; i < 400; i++) {
            s.step();
            maxGear = Math.max(maxGear, s.car.transmission.gear);
            minPitch = Math.min(minPitch, s.car.rotation.forward().y);
            if (t100 < 0 && s.car.speed() * 3.6 >= 100) t100 = s.time - 1;
            if (i % 40 == 0) System.out.println("  " + status(s));
        }
        System.out.println("  " + status(s));
        check(t100 > 3 && t100 < 14, "0-100 km/h in " + String.format("%.1f", t100) + " s");
        check(maxGear >= 4, "automatic gearbox shifted up to gear " + maxGear);
        check(s.car.forwardSpeed() > 35, "top speed after 20 s: " + String.format("%.0f", s.car.speed() * 3.6) + " km/h");
        check(Math.abs(s.car.origin().x - 0.5) < 1.5, "tracks straight (drift " + String.format("%.2f", s.car.origin().x - 0.5) + " m)");
        s.input.forward = 0;
        s.input.backward = 1;
        double z0 = s.car.origin().z, v0 = s.car.speed();
        double maxDive = 0;
        int n = 0;
        while (s.car.speed() > 0.5 && n++ < 400) {
            s.step();
            maxDive = Math.max(maxDive, -s.car.rotation.forward().y);
            if (s.car.transmission.gear < 0) break;
        }
        double dist = s.car.origin().z - z0;
        double decel = v0 * v0 / (2 * dist);
        System.out.println("  " + status(s));
        check(decel > 6 && decel < 13, "braking from " + String.format("%.0f", v0 * 3.6) + " km/h in " + String.format("%.0f", dist) + " m (" + String.format("%.1f", decel) + " m/s2)");
        check(maxDive > 0.01, "nose dives under braking (" + String.format("%.1f", Math.toDegrees(Math.asin(maxDive))) + " deg)");
    }

    static void cornering() throws Exception {
        System.out.println("== cornering");
        Sim s = new Sim(config());
        s.place(0.5, 0.5, 0);
        s.input.forward = 1;
        while (s.car.speed() < 16) s.step();
        s.input.forward = 0.35;
        s.input.steer = 1; // left
        double maxRoll = 0;
        V3 f0 = s.car.rotation.forward();
        for (int i = 0; i < 50; i++) {
            s.step();
            maxRoll = Math.max(maxRoll, Math.abs(s.car.rotation.left().y));
        }
        System.out.println("  " + status(s));
        V3 f1 = s.car.rotation.forward();
        double turned = Math.toDegrees(Math.atan2(f0.cross(f1).y, f0.dot(f1)));
        check(turned > 25, "turns left: heading changed " + String.format("%.0f", turned) + " deg in 2.5 s");
        check(maxRoll > 0.02, "body rolls in the corner (" + String.format("%.1f", Math.toDegrees(Math.asin(maxRoll))) + " deg)");
        check(s.car.rotation.up().y > 0.8, "does not flip in a normal corner");
        // handbrake turn
        s.input.handbrake = true;
        double maxSlip = 0;
        for (int i = 0; i < 30; i++) {
            s.step();
            maxSlip = Math.max(maxSlip, Math.abs(s.car.wheels[2].slipAngle));
        }
        check(Math.abs(s.car.wheels[2].wheelAngularVelocity) < 1, "handbrake locks the rear wheels");
        check(maxSlip > Math.toRadians(12), "rear steps out on the handbrake (slip " + String.format("%.0f", Math.toDegrees(maxSlip)) + " deg)");
    }

    static void independentSuspension() throws Exception {
        System.out.println("== independent suspension on uneven blocks");
        Sim s = new Sim(config());
        // a half-slab-like bump under the left wheels only is emulated with a 1-block ridge of friction 1
        s.height = (x, z) -> (x >= 1 && z >= 6 && z <= 40) ? 1 : 0;
        s.place(0.5, 0.5, 0); // left wheels at x~1.36 -> on the raised strip
        s.input.forward = 0.4;
        double maxRoll = 0, maxDiff = 0;
        for (int i = 0; i < 160; i++) {
            s.step();
            if (s.car.speed() > 4) s.input.forward = 0.1;
            maxRoll = Math.max(maxRoll, Math.abs(s.car.rotation.left().y));
            maxDiff = Math.max(maxDiff, Math.abs(s.car.wheels[0].compression - s.car.wheels[1].compression));
            if (i % 20 == 0) System.out.println("  " + status(s) + String.format(" comp FL=%.2f FR=%.2f RL=%.2f RR=%.2f",
                    s.car.wheels[0].compression, s.car.wheels[1].compression, s.car.wheels[2].compression, s.car.wheels[3].compression));
        }
        check(maxRoll > 0.2, "body leans when one side is a block higher (" + String.format("%.0f", Math.toDegrees(Math.asin(maxRoll))) + " deg)");
        check(maxDiff > 0.05, "left/right suspension compress differently (" + String.format("%.2f", maxDiff) + " m)");
        check(s.car.origin().z > 12, "keeps driving along the ledge (z=" + String.format("%.1f", s.car.origin().z) + ")");
    }

    static void stepClimb() throws Exception {
        System.out.println("== climbing a full block step");
        for (double speed : new double[]{3, 7}) {
            Sim s = new Sim(config());
            s.height = (x, z) -> z >= 12 ? 1 : 0;
            s.place(0.5, 0.5, 0);
            s.input.forward = 0.6;
            double maxPitch = 0;
            for (int i = 0; i < 240; i++) {
                s.step();
                s.input.forward = s.car.speed() < speed ? 0.6 : 0.0;
                maxPitch = Math.max(maxPitch, s.car.rotation.forward().y);
                if (i % 30 == 0) System.out.println("  " + status(s));
            }
            check(s.car.origin().z > 20 && s.car.origin().y > 1.2, "drives up a 1-block ledge at ~" + (int) (speed * 3.6) + " km/h (z=" + String.format("%.1f", s.car.origin().z) + ", y=" + String.format("%.2f", s.car.origin().y) + ")");
            check(maxPitch > 0.1, "nose pitches up while climbing (" + String.format("%.0f", Math.toDegrees(Math.asin(maxPitch))) + " deg)");
            check(s.car.rotation.up().y > 0.9, "upright afterwards");
        }
    }

    static void hill() throws Exception {
        System.out.println("== block staircase hill (1 up per 3 forward)");
        Sim s = new Sim(config());
        s.height = (x, z) -> z < 10 ? 0 : Math.min(12, (z - 10) / 3 + 1);
        s.place(0.5, 0.5, 0);
        s.input.forward = 0.7;
        for (int i = 0; i < 400; i++) {
            s.step();
            s.input.forward = s.car.speed() < 7 ? 0.8 : 0.1;
            if (i % 50 == 0) System.out.println("  " + status(s));
        }
        check(s.car.origin().y > 10, "climbs the hill (y=" + String.format("%.1f", s.car.origin().y) + ")");
        check(s.car.rotation.up().y > 0.7, "still on its wheels");
    }

    static void ledgeAtSpeed() throws Exception {
        System.out.println("== one-block ledge taken at speed");
        Sim s = new Sim(config());
        s.height = (x, z) -> (z >= 60 && z < 72) ? 1 : 0;
        s.place(0.5, 0.5, 0);
        double minUp = 1;
        for (int i = 0; i < 20 * 14; i++) {
            s.input.forward = s.car.speed() < 12 ? 1 : 0;
            s.step();
            if (s.car.origin().z > 55) minUp = Math.min(minUp, s.car.rotation.up().y);
        }
        check(s.car.origin().z > 80, "clears the ledge at ~43 km/h (z=" + String.format("%.0f", s.car.origin().z) + ")");
        check(minUp > 0.6, "stays on its wheels (min up " + String.format("%.2f", minUp) + ")");
    }

    static void rollingTerrain() throws Exception {
        System.out.println("== rolling block terrain at ~36 km/h for 60 s");
        Sim s = new Sim(config());
        s.height = (x, z) -> (int) Math.round(3 * Math.sin(x / 14.0) + 3 * Math.sin(z / 17.0) + Math.sin((x + z) / 9.0)) + 8;
        s.place(0.5, 0.5, 0);
        double dist = 0, minUp = 1;
        V3 last = s.car.origin();
        for (int i = 0; i < 20 * 60; i++) {
            s.input.forward = s.car.speed() < 10 ? 1 : 0.1;
            s.input.steer = Math.sin(i / 60.0) * 0.25;
            s.step();
            V3 o = s.car.origin();
            dist += o.distance(last);
            last = o;
            minUp = Math.min(minUp, s.car.rotation.up().y);
        }
        check(dist / 60 * 3.6 > 15, "keeps moving over block hills (average " + String.format("%.0f", dist / 60 * 3.6) + " km/h)");
        check(minUp > 0.3, "never rolls over (min up " + String.format("%.2f", minUp) + ")");
        check(Integer.bitCount(s.car.damage.detachedMask()) == 0, "no parts fall off from ordinary off-road driving");
    }

    static void cliff() throws Exception {
        System.out.println("== driving off a cliff");
        Sim s = new Sim(config());
        s.height = (x, z) -> z < 30 ? 14 : 0;
        s.place(0.5, 0.5, 0);
        s.input.forward = 1;
        boolean airborne = false;
        double maxSpin = 0;
        for (int i = 0; i < 260; i++) {
            s.step();
            if (s.car.origin().z > 31) s.input.forward = 0;
            if (s.car.origin().y < 3 && s.car.wheelsOnGround > 0) s.input.backward = s.car.transmission.gear < 0 ? 0 : 1;
            if (s.car.wheelsOnGround == 0 && s.car.origin().y > 3) {
                airborne = true;
                maxSpin = Math.max(maxSpin, s.car.angularVelocity.length());
            }
            if (i % 20 == 0) System.out.println("  " + status(s));
        }
        DamageSystem d = s.car.damage;
        double maxDisp = 0;
        for (float f : d.lattice) maxDisp = Math.max(maxDisp, Math.abs(f));
        int lost = Integer.bitCount(d.detachedMask());
        System.out.println("  impact " + String.format("%.1f", s.car.lastImpactSpeed) + " m/s, max deformation " + String.format("%.2f", maxDisp) + " m, parts lost " + lost);
        check(airborne, "leaves the ground");
        check(maxSpin > 0.3, "keeps angular velocity in the air (" + String.format("%.2f", maxSpin) + " rad/s)");
        check(s.car.origin().y < 4, "lands at the bottom (y=" + String.format("%.2f", s.car.origin().y) + ")");
        check(maxDisp > 0.1, "body is visibly deformed");
        check(s.car.origin().isFinite() && s.car.speed() < 3, "comes to a stop without exploding");
    }

    static void wallCrash() throws Exception {
        System.out.println("== head-on wall crash at speed");
        Sim s = new Sim(config());
        s.height = (x, z) -> z >= 120 ? 4 : 0;
        s.place(0.5, 0.5, 0);
        s.input.forward = 1;
        double vBefore = 0;
        for (int i = 0; i < 400; i++) {
            vBefore = Math.max(vBefore, s.car.speed());
            s.step();
            if (s.car.lastImpactSpeed > 5) s.input.forward = 0;
        }
        DamageSystem d = s.car.damage;
        System.out.println("  " + status(s));
        System.out.println(String.format("  hit at %.0f km/h, impact %.1f m/s; zones FL=%.2f FC=%.2f FR=%.2f engine=%.2f radiator=%.2f coolant=%.2f",
                vBefore * 3.6, s.car.lastImpactSpeed, d.zones[0], d.zones[1], d.zones[2], d.zones[DamageSystem.Z_ENGINE], d.radiatorHealth, d.coolant));
        StringBuilder lost = new StringBuilder();
        for (int i = 0; i < d.partCount(); i++) if (d.partDetached[i]) lost.append(s.car.cfg.damage.parts[i].name).append(' ');
        System.out.println("  detached: " + lost);
        check(s.car.origin().z < 119, "stopped by the wall (z=" + String.format("%.2f", s.car.origin().z) + ")");
        check(d.zones[DamageSystem.Z_FRONT_CENTER] + d.zones[0] + d.zones[2] > 0.5, "front zones damaged");
        check(d.zones[DamageSystem.Z_REAR_CENTER] < 0.05, "rear is untouched");
        V3 nose = d.displacement(0, 0.6, 2.3), tail = d.displacement(0, 0.6, -2.3);
        check(nose.z < -0.15, "front is pushed in by " + String.format("%.2f", -nose.z) + " m");
        check(tail.length() < 0.02, "tail geometry unchanged");
        check(lost.length() > 0, "at least one part came off");
        check(d.radiatorHealth < 0.8, "radiator damaged");
        check(s.car.detachments.size() > 0, "detachment events were emitted for the game layer");
    }

    static void reverse() throws Exception {
        System.out.println("== reverse");
        Sim s = new Sim(config());
        s.place(0.5, 0.5, 0);
        s.input.backward = 1;
        s.run(4);
        System.out.println("  " + status(s));
        check(s.car.transmission.gear == -1, "selects reverse from standstill");
        check(s.car.forwardSpeed() < -3, "drives backwards at " + String.format("%.1f", -s.car.forwardSpeed() * 3.6) + " km/h");
        s.input.backward = 0;
        s.input.forward = 1;
        s.run(5);
        check(s.car.transmission.gear >= 1 && s.car.forwardSpeed() > 3, "W brakes, then drives forward again");
    }

    static void ice() throws Exception {
        System.out.println("== surface friction");
        double[] dist = new double[2];
        for (int k = 0; k < 2; k++) {
            Sim s = new Sim(config());
            final double f = k == 0 ? 1.0 : 0.08;
            s.friction = (x, z) -> f;
            s.place(0.5, 0.5, 0);
            s.input.forward = 1;
            s.run(3);
            dist[k] = s.car.origin().z;
        }
        check(dist[1] < dist[0] * 0.5, "ice gives far less traction than stone (" + String.format("%.1f m vs %.1f m in 3 s", dist[1], dist[0]) + ")");
    }

    static void overheating() throws Exception {
        System.out.println("== cooling system");
        Sim s = new Sim(config());
        s.place(0.5, 0.5, 0);
        s.input.forward = 0.5;
        s.run(60);
        double normal = s.car.damage.temperature;
        check(normal > 70 && normal < 105, "healthy engine settles at " + String.format("%.0f", normal) + " C");
        s.car.damage.radiatorHealth = 0.1;
        boolean lostPower = false, died = false;
        for (int i = 0; i < 20 * 400 && !died; i++) {
            s.step();
            if (s.car.damage.enginePowerFactor() < 0.6) lostPower = true;
            if (!s.car.engine.running) died = true;
        }
        check(lostPower, "broken radiator -> overheating -> power loss (" + String.format("%.0f", s.car.damage.temperature) + " C)");
        check(died, "engine eventually stops (after " + String.format("%.0f", s.time - 60) + " s)");
    }
}
