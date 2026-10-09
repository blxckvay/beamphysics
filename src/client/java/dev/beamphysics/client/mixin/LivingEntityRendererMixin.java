package dev.beamphysics.client.mixin;

import dev.beamphysics.entity.VehicleEntity;
import dev.beamphysics.math.Quat;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tilts seated passengers with the car body so they pitch and roll together with it. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "setupTransforms", at = @At("HEAD"))
    private void beamphysics$tiltWithVehicle(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta, CallbackInfo ci) {
        if (entity.getVehicle() instanceof VehicleEntity vehicle) {
            Quat rot = vehicle.renderRotation(tickDelta);
            Quat tilt = rot.mul(Quat.fromMcYaw(rot.mcYaw()).conj());
            matrices.multiply(new Quaternionf((float) tilt.x, (float) tilt.y, (float) tilt.z, (float) tilt.w));
        }
    }
}
