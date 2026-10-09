package dev.beamphysics.physics;

/** One axis-aligned collision box of the block world, with its surface friction. */
public final class TerrainBox {
    public final double minX, minY, minZ, maxX, maxY, maxZ;
    public final double friction;

    public TerrainBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double friction) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.friction = friction;
    }

    public boolean contains(double x, double y, double z) {
        return x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ;
    }
}
