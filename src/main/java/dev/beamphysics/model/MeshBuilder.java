package dev.beamphysics.model;

import java.util.ArrayList;
import java.util.List;

/** Accumulates tessellated quads for one {@link MeshPart}. */
public final class MeshBuilder {
    private final List<float[]> quads = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private final double cell;

    public MeshBuilder(double cell) {
        this.cell = Math.max(0.08, cell);
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] bilerp(double[] a, double[] b, double[] c, double[] d, double u, double v) {
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            double ab = a[i] + (b[i] - a[i]) * u;
            double dc = d[i] + (c[i] - d[i]) * u;
            o[i] = ab + (dc - ab) * v;
        }
        return o;
    }

    /** Add a quad a-b-c-d (counter-clockwise from outside), split into cells no larger than the cell size. */
    public void quad(double[] a, double[] b, double[] c, double[] d, int argb, boolean tessellate) {
        int nu = 1, nv = 1;
        if (tessellate) {
            nu = (int) Math.max(1, Math.min(14, Math.ceil(Math.max(dist(a, b), dist(d, c)) / cell)));
            nv = (int) Math.max(1, Math.min(14, Math.ceil(Math.max(dist(b, c), dist(a, d)) / cell)));
        }
        for (int iu = 0; iu < nu; iu++) {
            for (int iv = 0; iv < nv; iv++) {
                double u0 = (double) iu / nu, u1 = (double) (iu + 1) / nu;
                double v0 = (double) iv / nv, v1 = (double) (iv + 1) / nv;
                double[] p0 = bilerp(a, b, c, d, u0, v0), p1 = bilerp(a, b, c, d, u1, v0);
                double[] p2 = bilerp(a, b, c, d, u1, v1), p3 = bilerp(a, b, c, d, u0, v1);
                quads.add(new float[]{
                        (float) p0[0], (float) p0[1], (float) p0[2], (float) p1[0], (float) p1[1], (float) p1[2],
                        (float) p2[0], (float) p2[1], (float) p2[2], (float) p3[0], (float) p3[1], (float) p3[2]});
                colors.add(argb);
            }
        }
    }

    private static final int[][] FACES = {
            {0, 1, 2, 3}, // D
            {4, 7, 6, 5}, // U
            {0, 4, 5, 1}, // B (-z)
            {3, 2, 6, 7}, // F (+z)
            {0, 3, 7, 4}, // R (-x)
            {1, 5, 6, 2}, // L (+x)
    };
    private static final String FACE_KEYS = "DUBFRL";

    /**
     * Add a hexahedron. Corners: bottom ring (x0,z0) (x1,z0) (x1,z1) (x0,z1), then the top ring in the
     * same order, with x1 &gt; x0 and z1 &gt; z0. {@code faces} lists the faces to emit out of "DUBFRL".
     */
    public void hexa(double[][] c, String faces, int argb, boolean tessellate) {
        for (int f = 0; f < 6; f++) {
            if (faces != null && !faces.isEmpty() && faces.indexOf(FACE_KEYS.charAt(f)) < 0) continue;
            int[] idx = FACES[f];
            quad(c[idx[0]], c[idx[1]], c[idx[2]], c[idx[3]], argb, tessellate);
        }
    }

    public void box(double x0, double y0, double z0, double x1, double y1, double z1, String faces, int argb, boolean tessellate) {
        double ax = Math.min(x0, x1), bx = Math.max(x0, x1);
        double ay = Math.min(y0, y1), by = Math.max(y0, y1);
        double az = Math.min(z0, z1), bz = Math.max(z0, z1);
        hexa(new double[][]{
                {ax, ay, az}, {bx, ay, az}, {bx, ay, bz}, {ax, ay, bz},
                {ax, by, az}, {bx, by, az}, {bx, by, bz}, {ax, by, bz}}, faces, argb, tessellate);
    }

    /** Cylinder along the X axis centred at (cx, cy, cz). */
    public void cylinderX(double cx, double cy, double cz, double radius, double length, int segments,
                          int sideColor, int capColor, boolean caps) {
        double x0 = cx - length * 0.5, x1 = cx + length * 0.5;
        for (int i = 0; i < segments; i++) {
            double a0 = 2 * Math.PI * i / segments, a1 = 2 * Math.PI * (i + 1) / segments;
            double y0 = cy + Math.cos(a0) * radius, z0 = cz + Math.sin(a0) * radius;
            double y1 = cy + Math.cos(a1) * radius, z1 = cz + Math.sin(a1) * radius;
            quad(new double[]{x0, y0, z0}, new double[]{x0, y1, z1}, new double[]{x1, y1, z1}, new double[]{x1, y0, z0}, sideColor, false);
            if (caps) {
                quad(new double[]{x1, cy, cz}, new double[]{x1, y0, z0}, new double[]{x1, y1, z1}, new double[]{x1, cy, cz}, capColor, false);
                quad(new double[]{x0, cy, cz}, new double[]{x0, y1, z1}, new double[]{x0, y0, z0}, new double[]{x0, cy, cz}, capColor, false);
            }
        }
    }

    /** Outward normal of a quad stored as 12 floats starting at {@code o}; robust for triangles stored as quads. */
    public static void normal(float[] p, int o, float[] out) {
        float ax = p[o + 3] - p[o], ay = p[o + 4] - p[o + 1], az = p[o + 5] - p[o + 2];
        float bx = p[o + 6] - p[o], by = p[o + 7] - p[o + 1], bz = p[o + 8] - p[o + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-7f) {
            ax = p[o + 9] - p[o];
            ay = p[o + 10] - p[o + 1];
            az = p[o + 11] - p[o + 2];
            nx = by * az - bz * ay;
            ny = bz * ax - bx * az;
            nz = bx * ay - by * ax;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len < 1e-7f) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    public void into(MeshPart part) {
        int old = part.colors.length;
        float[] pos = new float[(old + quads.size()) * 12];
        int[] col = new int[old + quads.size()];
        System.arraycopy(part.positions, 0, pos, 0, old * 12);
        System.arraycopy(part.colors, 0, col, 0, old);
        for (int i = 0; i < quads.size(); i++) {
            System.arraycopy(quads.get(i), 0, pos, (old + i) * 12, 12);
            col[old + i] = colors.get(i);
        }
        part.positions = pos;
        part.colors = col;
        quads.clear();
        colors.clear();
    }
}
