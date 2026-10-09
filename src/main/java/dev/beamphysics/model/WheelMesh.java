package dev.beamphysics.model;

/** Procedural wheel: tyre, sidewalls, rim and spokes. Axis = X, centre = origin. */
public final class WheelMesh {
    private WheelMesh() {
    }

    public static MeshPart build(double radius, double width) {
        MeshPart part = new MeshPart("wheel");
        part.deformable = false;
        MeshBuilder b = new MeshBuilder(1.0);
        int seg = 20;
        int tyre = 0xFF1B1B1D, tyreAlt = 0xFF242427, wall = 0xFF151517, rim = 0xFFB9BCC2, rimDark = 0xFF6E7279, hubCol = 0xFF3A3C40;
        double hw = width * 0.5;
        double rimR = radius * 0.62;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            // tread (alternating shade reads as tread blocks and makes rotation visible)
            b.quad(v(-hw, c0 * radius, s0 * radius), v(-hw, c1 * radius, s1 * radius), v(hw, c1 * radius, s1 * radius), v(hw, c0 * radius, s0 * radius),
                    i % 2 == 0 ? tyre : tyreAlt, false);
            for (int side = -1; side <= 1; side += 2) {
                double x = side * hw, xi = side * hw * 0.55;
                double[] o0 = v(x, c0 * radius, s0 * radius), o1 = v(x, c1 * radius, s1 * radius);
                double[] i0 = v(x, c0 * rimR, s0 * rimR), i1 = v(x, c1 * rimR, s1 * rimR);
                double[] d0 = v(xi, c0 * rimR * 0.92, s0 * rimR * 0.92), d1 = v(xi, c1 * rimR * 0.92, s1 * rimR * 0.92);
                double[] centre = v(xi, 0, 0);
                boolean spoke = (i % 4) < 2;
                if (side > 0) {
                    b.quad(o1, i1, i0, o0, wall, false);                    // sidewall
                    b.quad(i1, d1, d0, i0, rimDark, false);                // rim lip
                    b.quad(centre, d0, d1, centre, spoke ? rim : hubCol, false); // dish
                } else {
                    b.quad(o0, i0, i1, o1, wall, false);
                    b.quad(i0, d0, d1, i1, rimDark, false);
                    b.quad(centre, d1, d0, centre, spoke ? rim : hubCol, false);
                }
            }
        }
        b.into(part);
        return part;
    }

    private static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
}
