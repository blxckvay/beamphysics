package dev.beamphysics.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A vehicle's visual model, loaded from {@code vehicles/<id>/model.json}: a list of named parts,
 * each made of simple shapes (boxes, hexahedra, quads, cylinders) that are tessellated on load so
 * the damage lattice has enough vertices to bend.
 */
public final class VehicleModel {
    public final List<MeshPart> parts = new ArrayList<>();
    private final Map<String, MeshPart> byName = new HashMap<>();
    public MeshPart wheel;

    public MeshPart part(String name) { return byName.get(name); }

    public int totalQuads() {
        int n = 0;
        for (MeshPart p : parts) n += p.quadCount();
        return n;
    }

    public static VehicleModel fromJson(String json, double wheelRadius, double wheelWidth) {
        VehicleModel model = new VehicleModel();
        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        double cell = root.has("tessellation") ? root.get("tessellation").getAsDouble() : 0.3;
        Map<String, Integer> palette = new HashMap<>();
        if (root.has("colors")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("colors").entrySet()) {
                palette.put(e.getKey(), parseColor(e.getValue().getAsString()));
            }
        }
        for (JsonElement pe : root.getAsJsonArray("parts")) {
            JsonObject po = pe.getAsJsonObject();
            MeshPart part = new MeshPart(po.get("name").getAsString());
            if (po.has("pivot")) part.pivot = vec(po.getAsJsonArray("pivot"));
            if (po.has("hingeAxis")) part.hingeAxis = vec(po.getAsJsonArray("hingeAxis"));
            if (po.has("hingeAngle")) part.hingeAngleDeg = po.get("hingeAngle").getAsDouble();
            if (po.has("role")) part.role = po.get("role").getAsString();
            if (po.has("deformable")) part.deformable = po.get("deformable").getAsBoolean();
            MeshBuilder b = new MeshBuilder(cell);
            for (JsonElement se : po.getAsJsonArray("shapes")) {
                JsonObject s = se.getAsJsonObject();
                boolean mirror = s.has("mirror") && s.get("mirror").getAsBoolean();
                addShape(b, s, palette, false);
                if (mirror) addShape(b, s, palette, true);
            }
            b.into(part);
            model.parts.add(part);
            model.byName.put(part.name, part);
        }
        model.wheel = WheelMesh.build(wheelRadius, wheelWidth);
        return model;
    }

    private static void addShape(MeshBuilder b, JsonObject s, Map<String, Integer> palette, boolean mirrored) {
        String type = s.get("type").getAsString();
        int color = 0xFFFFFFFF;
        if (s.has("color")) {
            String c = s.get("color").getAsString();
            color = palette.containsKey(c) ? palette.get(c) : parseColor(c);
        }
        if (s.has("alpha")) {
            int a = (int) Math.round(Math.max(0, Math.min(1, s.get("alpha").getAsDouble())) * 255);
            color = (color & 0x00FFFFFF) | (a << 24);
        }
        boolean tess = !s.has("tessellate") || s.get("tessellate").getAsBoolean();
        String faces = s.has("faces") ? s.get("faces").getAsString() : "";
        double m = mirrored ? -1 : 1;
        switch (type) {
            case "box": {
                double[] f = vec(s.getAsJsonArray("from")), t = vec(s.getAsJsonArray("to"));
                b.box(f[0] * m, f[1], f[2], t[0] * m, t[1], t[2], mirrored ? swapSides(faces) : faces, color, tess);
                break;
            }
            case "hexa": {
                JsonArray pts = s.getAsJsonArray("points");
                double[][] c = new double[8][];
                for (int i = 0; i < 8; i++) c[i] = vec(pts.get(i).getAsJsonArray());
                if (mirrored) {
                    // negate x and swap the x0/x1 corners so the winding stays outward
                    double[][] r = new double[8][];
                    int[] map = {1, 0, 3, 2, 5, 4, 7, 6};
                    for (int i = 0; i < 8; i++) r[i] = new double[]{-c[map[i]][0], c[map[i]][1], c[map[i]][2]};
                    c = r;
                }
                b.hexa(c, mirrored ? swapSides(faces) : faces, color, tess);
                break;
            }
            case "quad": {
                JsonArray pts = s.getAsJsonArray("points");
                double[][] c = new double[4][];
                for (int i = 0; i < 4; i++) c[i] = vec(pts.get(i).getAsJsonArray());
                if (mirrored) {
                    b.quad(neg(c[3]), neg(c[2]), neg(c[1]), neg(c[0]), color, tess);
                } else {
                    b.quad(c[0], c[1], c[2], c[3], color, tess);
                }
                break;
            }
            case "cylinder": {
                double[] c = vec(s.getAsJsonArray("center"));
                int seg = s.has("segments") ? s.get("segments").getAsInt() : 12;
                int cap = color;
                if (s.has("capColor")) {
                    String cc = s.get("capColor").getAsString();
                    cap = palette.containsKey(cc) ? palette.get(cc) : parseColor(cc);
                }
                b.cylinderX(c[0] * m, c[1], c[2], s.get("radius").getAsDouble(), s.get("length").getAsDouble(), seg, color, cap, true);
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown shape type: " + type);
        }
    }

    private static String swapSides(String faces) {
        if (faces == null || faces.isEmpty()) return faces;
        StringBuilder sb = new StringBuilder();
        for (char ch : faces.toCharArray()) sb.append(ch == 'L' ? 'R' : ch == 'R' ? 'L' : ch);
        return sb.toString();
    }

    private static double[] neg(double[] p) { return new double[]{-p[0], p[1], p[2]}; }

    private static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static int parseColor(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        long v = Long.parseLong(h, 16);
        if (h.length() <= 6) v |= 0xFF000000L;
        return (int) v;
    }
}
