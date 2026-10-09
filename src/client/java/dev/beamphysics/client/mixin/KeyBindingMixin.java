package dev.beamphysics.client.mixin;

import dev.beamphysics.client.ClientInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While driving, F / Q / E / R / C belong to the car, not to swap-hands, drop or inventory. */
@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Inject(method = "onKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void beamphysics$onKeyPressed(InputUtil.Key key, CallbackInfo ci) {
        if (ClientInput.shouldCapture(key)) ci.cancel();
    }

    @Inject(method = "setKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void beamphysics$setKeyPressed(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        if (pressed && ClientInput.shouldCapture(key)) ci.cancel();
    }
}
