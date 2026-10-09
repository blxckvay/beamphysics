package dev.beamphysics;

import dev.beamphysics.math.V3;
import dev.beamphysics.model.MeshBuilder;
import dev.beamphysics.model.MeshPart;
import dev.beamphysics.model.VehicleModel;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.VehicleConfig;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Dumps the model (optionally crashed) as "x y z ... r g b a nx ny nz" quads for an offline preview. */
public final class ModelExport {
    public static void main(String[] args) throws Exception {
        String dir = "src/main/resources/data/beamphysics/vehicles/test_suv/";
        VehicleConfig cfg = VehicleConfig.fromJson(Files.readString(Path.of(dir + "vehicle.json")));
        VehicleModel model = VehicleModel.fromJson(Files.readString(Path.of(dir + "model.json")), cfg.wheels.radius, cfg.wheels.width);
        DamageSystem dmg = new DamageSystem(cfg);
        boolean crashed = args.length > 1 && args[1].equals("crash");
        if (crashed) {
            ArrayList<Integer> lost = new ArrayList<>();
            for (double x : new double[]{-0.8, 0, 0.8}) {
                dmg.applyImpact(new V3(x, 0.5, 2.3), new V3(0, 0, -1), 24, 0.6, lost);
                dmg.applyImpact(new V3(x, 1.0, 2.3), new V3(0, 0, -1), 24, 0.6, lost);
            }
            dmg.applyImpact(new V3(0.6, 1.7, -0.5), new V3(0, -1, 0), 16, 1, lost);
            System.out.println("lost parts: " + lost);
        }
        System.out.println("quads: " + model.totalQuads() + " + wheel " + model.wheel.quadCount() + " x4");
        try (PrintWriter w = new PrintWriter(args[0])) {
            float[] n = new float[3];
            for (MeshPart p : model.parts) {
                int pi = dmg.partIndex(p.name);
                if (pi >= 0 && dmg.partDetached[pi]) continue;
                double loose = pi >= 0 ? dmg.partLooseness(pi) : 0;
                emit(w, p, dmg, 0, 0, 0, n, loose);
            }
            double hubY = cfg.suspension.mountHeight - cfg.suspension.length + 0.155;
            for (int i = 0; i < 4; i++) emit(w, model.wheel, null, cfg.wheelX(i), hubY, cfg.wheelZ(i), n, 0);
        }
    }

    static void emit(PrintWriter w, MeshPart p, DamageSystem dmg, double ox, double oy, double oz, float[] n, double loose) {
        float[] pos = p.positions.clone();
        double ang = Math.toRadians(p.hingeAngleDeg * loose);
        V3 axis = V3.of(p.hingeAxis), pivot = V3.of(p.pivot);
        dev.beamphysics.math.Quat q = dev.beamphysics.math.Quat.axisAngle(axis, ang);
        for (int i = 0; i < pos.length; i += 3) {
            V3 v = new V3(pos[i], pos[i + 1], pos[i + 2]);
            if (dmg != null && p.deformable) v = dmg.deform(v);
            if (loose > 0 && !p.role.equals("steering")) v = pivot.add(q.rotate(v.sub(pivot)));
            pos[i] = (float) (v.x + ox);
            pos[i + 1] = (float) (v.y + oy);
            pos[i + 2] = (float) (v.z + oz);
        }
        for (int qd = 0; qd < p.quadCount(); qd++) {
            MeshBuilder.normal(pos, qd * 12, n);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 12; k++) sb.append(pos[qd * 12 + k]).append(' ');
            int c = p.colors[qd];
            sb.append((c >> 16) & 255).append(' ').append((c >> 8) & 255).append(' ').append(c & 255).append(' ').append((c >>> 24) & 255);
            sb.append(' ').append(n[0]).append(' ').append(n[1]).append(' ').append(n[2]);
            w.println(sb);
        }
    }
}
