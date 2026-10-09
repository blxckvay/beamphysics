package dev.beamphysics.client.mixin;

import dev.beamphysics.client.VehicleCamera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds camera roll (vanilla cameras only have yaw and pitch) for the in-car views. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;getPitch()F", ordinal = 0))
    private void beamphysics$roll(float tickDelta, long limitTime, MatrixStack matrices, CallbackInfo ci) {
        float roll = VehicleCamera.currentRoll;
        if (roll != 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
    }
}
