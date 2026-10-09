package dev.beamphysics.client;

import dev.beamphysics.VehicleRegistry;
import dev.beamphysics.VehicleType;
import dev.beamphysics.entity.DetachedPartEntity;
import dev.beamphysics.math.Quat;
import dev.beamphysics.model.MeshPart;
import dev.beamphysics.physics.DamageSystem;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.WeakHashMap;

/** Draws a torn-off panel or wheel, keeping the dents it had when it left the car. */
public class DetachedPartRenderer extends EntityRenderer<DetachedPartEntity> {
    private static final class Cache {
        String key;
        MeshCache mesh;
        boolean wheel;
        boolean upright;
    }

    private final Map<DetachedPartEntity, Cache> caches = new WeakHashMap<>();

    public DetachedPartRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.5f;
    }

    @Override
    public Identifier getTexture(DetachedPartEntity entity) { return VehicleRenderer.TEXTURE; }

    private Cache cache(DetachedPartEntity entity) {
        NbtCompound data = entity.data();
        String key = data.getString("Vehicle") + "/" + data.getString("Part");
        Cache c = caches.get(entity);
        if (c != null && c.key.equals(key)) return c;
        c = new Cache();
        c.key = key;
        if (data.getString("Part").isEmpty()) {
            caches.put(entity, c);
            return c;
        }
        VehicleType type = VehicleRegistry.get(data.getString("Vehicle"));
        if (entity.isWheel()) {
            c.wheel = true;
            c.mesh = new MeshCache(type.model().wheel, null, 0, 0, 0);
        } else {
            MeshPart part = type.model().part(data.getString("Part"));
            if (part != null) {
                DamageSystem dmg = new DamageSystem(type.config);
                if (data.contains("Lattice")) dmg.setLatticeBytes(data.getByteArray("Lattice"));
                double[] centre = part.centroid();
                c.mesh = new MeshCache(part, dmg, centre[0], centre[1], centre[2]);
                String name = part.name;
                c.upright = name.startsWith("door") || name.equals("tailgate");
            }
        }
        caches.put(entity, c);
        return c;
    }

    @Override
    public void render(DetachedPartEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        Cache c = cache(entity);
        if (c.mesh == null) return;
        Quat base = entity.baseRotation();
        float tumble = (float) Math.toRadians(entity.prevTumble + (entity.tumble - entity.prevTumble) * tickDelta);
        matrices.push();
        matrices.translate(0, c.wheel ? 0.5 : 0.22, 0);
        Quaternionf q = new Quaternionf((float) base.x, (float) base.y, (float) base.z, (float) base.w);
        if (c.wheel) {
            q.rotateX(tumble);
        } else if (c.upright) {
            // doors and the tailgate stand upright on the car: fall over onto their face
            String part = entity.partName();
            if (part.equals("tailgate")) q.rotateX(tumble + (float) Math.PI / 2);
            else q.rotateZ(tumble + (float) Math.PI / 2);
        } else {
            q.rotateX(tumble);
        }
        matrices.multiply(q);
        VertexConsumer opaque = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(VehicleRenderer.TEXTURE));
        if (c.mesh.hasOpaque) c.mesh.draw(matrices.peek(), opaque, light, false);
        if (c.mesh.hasTranslucent) {
            VertexConsumer glass = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(VehicleRenderer.TEXTURE));
            c.mesh.draw(matrices.peek(), glass, light, true);
        }
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }
}
