package dev.beamphysics.client;

import dev.beamphysics.math.V3;
import dev.beamphysics.model.MeshBuilder;
import dev.beamphysics.model.MeshPart;
import dev.beamphysics.physics.DamageSystem;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Render-ready copy of one mesh part: vertices already displaced by the damage lattice and
 * per-quad normals recomputed from the bent geometry, so crumpled metal is lit as crumpled.
 */
public final class MeshCache {
    private final float[] pos;
    private final float[] normals;
    private final int[] colors;
    public final boolean hasTranslucent;
    public final boolean hasOpaque;

    public MeshCache(MeshPart part, DamageSystem damage, double ox, double oy, double oz) {
        float[] src = part.positions;
        pos = new float[src.length];
        boolean deform = damage != null && part.deformable;
        for (int i = 0; i < src.length; i += 3) {
            double x = src[i], y = src[i + 1], z = src[i + 2];
            if (deform) {
                V3 d = damage.displacement(x, y, z);
                x += d.x;
                y += d.y;
                z += d.z;
            }
            pos[i] = (float) (x - ox);
            pos[i + 1] = (float) (y - oy);
            pos[i + 2] = (float) (z - oz);
        }
        colors = part.colors;
        normals = new float[colors.length * 3];
        float[] n = new float[3];
        boolean t = false, o = false;
        for (int q = 0; q < colors.length; q++) {
            MeshBuilder.normal(pos, q * 12, n);
            normals[q * 3] = n[0];
            normals[q * 3 + 1] = n[1];
            normals[q * 3 + 2] = n[2];
            if ((colors[q] >>> 24) < 255) t = true;
            else o = true;
        }
        hasTranslucent = t;
        hasOpaque = o;
    }

    private static final float[] U = {0, 1, 1, 0};
    private static final float[] V = {0, 0, 1, 1};

    public void draw(MatrixStack.Entry entry, VertexConsumer consumer, int light, boolean translucentPass) {
        Matrix4f m = entry.getPositionMatrix();
        Matrix3f nm = entry.getNormalMatrix();
        for (int q = 0; q < colors.length; q++) {
            int c = colors[q];
            int a = c >>> 24;
            if ((a < 255) != translucentPass) continue;
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float nx = normals[q * 3], ny = normals[q * 3 + 1], nz = normals[q * 3 + 2];
            int o = q * 12;
            for (int v = 0; v < 4; v++) {
                consumer.vertex(m, pos[o + v * 3], pos[o + v * 3 + 1], pos[o + v * 3 + 2])
                        .color(r, g, b, a)
                        .texture(U[v], V[v])
                        .overlay(OverlayTexture.DEFAULT_UV)
                        .light(light)
                        .normal(nm, nx, ny, nz)
                        .next();
            }
        }
    }
}
