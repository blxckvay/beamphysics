package dev.beamphysics;

import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import dev.beamphysics.physics.Terrain;
import dev.beamphysics.physics.VehicleConfig;
import dev.beamphysics.physics.VehicleInput;
import dev.beamphysics.physics.VehiclePhysics;

import java.util.function.BiFunction;

/** Headless test world: a block height-map sampled into the same box snapshot the game uses. */
public final class Sim {
    public interface Height { int at(int x, int z); }

    public final VehiclePhysics car;
    public final VehicleInput input = new VehicleInput();
    public final Terrain terrain = new Terrain();
    public Height height = (x, z) -> 0;
    public BiFunction<Integer, Integer, Double> friction = (x, z) -> 1.0;
    public double time;

    public Sim(VehicleConfig cfg) {
        car = new VehiclePhysics(cfg);
        car.hasDriver = true;
        car.engine.running = true;
        car.engine.omega = 90;
    }

    public void place(double x, double z, double yawDeg) {
        int h = height.at((int) Math.floor(x), (int) Math.floor(z));
        car.setPose(new V3(x, h + car.restHeight() + 0.02, z), Quat.fromMcYaw(yawDeg));
        car.stop();
        car.wake();
    }

    private void snapshot() {
        terrain.clear();
        V3 o = car.origin();
        int cx = (int) Math.floor(o.x), cz = (int) Math.floor(o.z);
        int r = 6;
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                int h = height.at(x, z);
                double f = friction.apply(x, z);
                // top blocks of the column as individual unit cubes, like the real world
                for (int y = h - 1; y >= h - 4 && y > o.y - 12; y--) {
                    if (y + 1 < o.y - 8) break;
                    terrain.add(x, y, z, x + 1, y + 1, z + 1, f);
                }
            }
        }
    }

    public void step() {
        snapshot();
        car.tick(0.05, input, terrain);
        time += 0.05;
    }

    public void run(double seconds) {
        int n = (int) Math.round(seconds / 0.05);
        for (int i = 0; i < n; i++) step();
    }
}
