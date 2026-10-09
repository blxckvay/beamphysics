package dev.beamphysics.client;

import dev.beamphysics.BeamPhysics;
import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.entity.VehicleSnapshot;
import dev.beamphysics.math.Quat;
import dev.beamphysics.math.V3;
import dev.beamphysics.model.MeshPart;
import dev.beamphysics.model.VehicleModel;
import dev.beamphysics.physics.DamageSystem;
import dev.beamphysics.physics.VehicleConfig;
import dev.beamphysics.physics.VehiclePhysics;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the vehicle from its physics state: body rotated by the rigid-body quaternion, each wheel
 * placed by its own suspension compression, panels bent by the damage lattice, loose panels
 * hanging on their hinges and torn-off ones simply absent.
 */
public class VehicleRenderer extends EntityRenderer<VehicleEntity> {
    public static final Identifier TEXTURE = new Identifier(BeamPhysics.MOD_ID, "textures/entity/vehicle.png");

    private static final class Cache {
        VehicleModel model;
        int latticeVersion = Integer.MIN_VALUE;
        MeshCache[] parts;
        int[] damageIndex;
        MeshCache wheel;
    }

    private final Map<VehicleEntity, Cache> caches = new WeakHashMap<>();

    public VehicleRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.6f;
        this.shadowOpacity = 0.6f;
    }

    @Override
    public Identifier getTexture(VehicleEntity entity) { return TEXTURE; }

    private Cache cache(VehicleEntity entity) {
        VehicleModel model = entity.vehicleType().model();
        DamageSystem damage = entity.physics().damage;
        Cache c = caches.get(entity);
        if (c == null || c.model != model) {
            c = new Cache();
            c.model = model;
            c.wheel = new MeshCache(model.wheel, null, 0, 0, 0);
            caches.put(entity, c);
        }
        if (c.latticeVersion != damage.latticeVersion) {
            c.latticeVersion = damage.latticeVersion;
            c.parts = new MeshCache[model.parts.size()];
            c.damageIndex = new int[model.parts.size()];
            for (int i = 0; i < c.parts.length; i++) {
                MeshPart part = model.parts.get(i);
                c.parts[i] = new MeshCache(part, damage, 0, 0, 0);
                c.damageIndex[i] = damage.partIndex(part.name);
            }
        }
        return c;
    }

    @Override
    public void render(VehicleEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        VehiclePhysics physics = entity.physics();
        VehicleConfig cfg = physics.cfg;
        DamageSystem damage = physics.damage;
        Cache cache = cache(entity);
        VehicleSnapshot prev = entity.prevSnapshot, cur = entity.snapshot;
        Quat rot = entity.renderRotation(tickDelta);

        // sample light a little above the floor pan so the car is not dark when it sits low
        int packedLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(),
                BlockPos.ofFloored(entity.getX(), entity.getY() + 1.2, entity.getZ()));

        float steer = cur == null ? 0 : VehicleSnapshot.lerp(prev.steer, cur.steer, tickDelta);

        matrices.push();
        matrices.multiply(new Quaternionf((float) rot.x, (float) rot.y, (float) rot.z, (float) rot.w));

        for (int pass = 0; pass < 2; pass++) {
            boolean translucent = pass == 1;
            VertexConsumer consumer = vertexConsumers.getBuffer(translucent
                    ? RenderLayer.getEntityTranslucent(TEXTURE) : RenderLayer.getEntityCutoutNoCull(TEXTURE));
            for (int i = 0; i < cache.parts.length; i++) {
                MeshCache mesh = cache.parts[i];
                if (translucent ? !mesh.hasTranslucent : !mesh.hasOpaque) continue;
                MeshPart part = cache.model.parts.get(i);
                int di = cache.damageIndex[i];
                if (di >= 0 && damage.partDetached[di]) continue;
                double angle = 0;
                if ("steering".equals(part.role)) {
                    angle = part.hingeAngleDeg * steer;
                } else if (di >= 0) {
                    double loose = damage.partLooseness(di);
                    angle = part.hingeAngleDeg * loose * loose;
                }
                if (angle != 0) {
                    matrices.push();
                    matrices.translate(part.pivot[0], part.pivot[1], part.pivot[2]);
                    matrices.multiply(new Quaternionf().rotateAxis((float) Math.toRadians(angle),
                            (float) part.hingeAxis[0], (float) part.hingeAxis[1], (float) part.hingeAxis[2]));
                    matrices.translate(-part.pivot[0], -part.pivot[1], -part.pivot[2]);
                    mesh.draw(matrices.peek(), consumer, packedLight, translucent);
                    matrices.pop();
                } else {
                    mesh.draw(matrices.peek(), consumer, packedLight, translucent);
                }
            }
            if (!translucent) {
                for (int w = 0; w < 4; w++) {
                    if (damage.wheelDetached[w]) continue;
                    float comp = cur == null ? (float) (cfg.mass * 9.81 / 4 / cfg.suspension.springStiffness)
                            : VehicleSnapshot.lerp(prev.compression[w], cur.compression[w], tickDelta);
                    float spin = cur == null ? 0 : VehicleSnapshot.lerp(prev.spin[w], cur.spin[w], tickDelta);
                    float ws = cur == null ? 0 : VehicleSnapshot.lerp(prev.wheelSteer[w], cur.wheelSteer[w], tickDelta);
                    double hubY = cfg.suspension.mountHeight - (cfg.suspension.length - comp);
                    float camber = (float) (damage.wheelCamber[w] * (w % 2 == 0 ? 1 : -1));
                    matrices.push();
                    matrices.translate(cfg.wheelX(w), hubY, cfg.wheelZ(w));
                    matrices.multiply(new Quaternionf().rotateY(ws).rotateZ(camber).rotateX(spin));
                    cache.wheel.draw(matrices.peek(), consumer, packedLight, false);
                    matrices.pop();
                }
            }
        }
        matrices.pop();

        if (ClientState.debug && cur != null) renderDebug(entity, cfg, rot, cur, tickDelta, matrices, vertexConsumers);
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    /** Suspension rays: green while the wheel touches the ground, red in the air. */
    private void renderDebug(VehicleEntity entity, VehicleConfig cfg, Quat rot, VehicleSnapshot cur, float tickDelta,
                             MatrixStack matrices, VertexConsumerProvider vertexConsumers) {
        VertexConsumer lines = vertexConsumers.getBuffer(RenderLayer.getLines());
        Matrix4f m = matrices.peek().getPositionMatrix();
        Matrix3f nm = matrices.peek().getNormalMatrix();
        V3 origin = entity.renderPosition(tickDelta);
        for (int w = 0; w < 4; w++) {
            V3 mount = rot.rotate(new V3(cfg.wheelX(w), cfg.suspension.mountHeight, cfg.wheelZ(w)));
            V3 end = new V3(cur.contactPoint[w][0] - origin.x, cur.contactPoint[w][1] - origin.y, cur.contactPoint[w][2] - origin.z);
            boolean contact = cur.contact[w];
            line(lines, m, nm, mount, end, contact ? 60 : 255, contact ? 255 : 60, 60);
            // slip indicator: a short horizontal tick whose length grows with slip
            double slip = Math.min(1.5, Math.abs(cur.slipRatio[w]) + Math.abs(cur.slipAngle[w]));
            V3 side = rot.rotate(new V3(w % 2 == 0 ? 1 : -1, 0, 0)).scale(0.3 + slip);
            line(lines, m, nm, end, end.add(side), 255, 220, 40);
        }
        V3 com = rot.rotate(V3.of(cfg.centerOfMass));
        line(lines, m, nm, com.add(-0.2, 0, 0), com.add(0.2, 0, 0), 80, 160, 255);
        line(lines, m, nm, com.add(0, -0.2, 0), com.add(0, 0.2, 0), 80, 160, 255);
        line(lines, m, nm, com.add(0, 0, -0.2), com.add(0, 0, 0.2), 80, 160, 255);
    }

    private static void line(VertexConsumer c, Matrix4f m, Matrix3f nm, V3 a, V3 b, int r, int g, int bl) {
        V3 d = b.sub(a).normalizeOr(V3.Y);
        c.vertex(m, (float) a.x, (float) a.y, (float) a.z).color(r, g, bl, 255).normal(nm, (float) d.x, (float) d.y, (float) d.z).next();
        c.vertex(m, (float) b.x, (float) b.y, (float) b.z).color(r, g, bl, 255).normal(nm, (float) d.x, (float) d.y, (float) d.z).next();
    }
}
