package dev.beamphysics.world;

import dev.beamphysics.physics.Terrain;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/** Copies the collision boxes of the blocks around a vehicle into a physics {@link Terrain} snapshot. */
public final class TerrainSampler {
    private TerrainSampler() {
    }

    public static void sample(World world, double cx, double cy, double cz, double horizontal, double below, double above, Terrain out) {
        out.clear();
        int x0 = MathHelper.floor(cx - horizontal), x1 = MathHelper.floor(cx + horizontal);
        int y0 = MathHelper.floor(cy - below), y1 = MathHelper.floor(cy + above);
        int z0 = MathHelper.floor(cz - horizontal), z1 = MathHelper.floor(cz + horizontal);
        y0 = Math.max(y0, world.getBottomY());
        y1 = Math.min(y1, world.getTopY() - 1);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                pos.set(x, y0, z);
                if (!world.isChunkLoaded(pos)) {
                    out.valid = false;
                    return;
                }
                for (int y = y0; y <= y1; y++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(world, pos);
                    if (shape.isEmpty()) continue;
                    final double friction = SurfaceFriction.of(state.getBlock());
                    if (shape == VoxelShapes.fullCube()) {
                        out.add(x, y, z, x + 1, y + 1, z + 1, friction);
                    } else {
                        final int bx = x, by = y, bz = z;
                        shape.forEachBox((ax, ay, az, ex, ey, ez) -> out.add(bx + ax, by + ay, bz + az, bx + ex, by + ey, bz + ez, friction));
                    }
                }
            }
        }
    }

    /** Cheap fingerprint used to notice that the ground under a sleeping vehicle changed. */
    public static long fingerprint(Terrain t) {
        long h = t.boxes.size();
        for (int i = 0; i < t.boxes.size(); i++) {
            var b = t.boxes.get(i);
            h = h * 31 + Double.doubleToLongBits(b.minX * 3 + b.minY * 5 + b.minZ * 7 + b.maxY * 11);
        }
        return h;
    }
}
