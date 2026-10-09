package dev.beamphysics.model;

/**
 * One named piece of a vehicle model (body, hood, doorFL, ...), stored as flat quads in body space.
 * Vertices are plain data so the renderer can displace every one of them by the damage lattice.
 */
public final class MeshPart {
    public final String name;
    /** 12 floats per quad: four corners, xyz each, counter-clockwise seen from outside. */
    public float[] positions = new float[0];
    /** ARGB per quad. Alpha below 255 means the quad is drawn in the translucent pass. */
    public int[] colors = new int[0];
    /** Hinge point in body space. */
    public double[] pivot = {0, 0, 0};
    public double[] hingeAxis = {1, 0, 0};
    /** Rotation about the hinge when the part is fully loose (about to fall off), degrees. */
    public double hingeAngleDeg = 0;
    /** "steering" parts rotate with the steering wheel about pivot/hingeAxis. */
    public String role = "";
    /** Rigid parts (wheels) ignore the body deformation lattice. */
    public boolean deformable = true;

    public MeshPart(String name) {
        this.name = name;
    }

    public int quadCount() { return colors.length; }

    /** Average of all vertices: used as the origin of the part once it is torn off. */
    public double[] centroid() {
        double x = 0, y = 0, z = 0;
        int n = positions.length / 3;
        if (n == 0) return new double[]{0, 0, 0};
        for (int i = 0; i < n; i++) {
            x += positions[i * 3];
            y += positions[i * 3 + 1];
            z += positions[i * 3 + 2];
        }
        return new double[]{x / n, y / n, z / n};
    }
}
