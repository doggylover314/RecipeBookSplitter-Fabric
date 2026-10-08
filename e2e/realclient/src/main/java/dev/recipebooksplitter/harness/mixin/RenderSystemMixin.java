package dev.recipebooksplitter.harness.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.recipebooksplitter.harness.Harness;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the wait of the frame rate limiter, so that frame work time = runTick time minus this wait. */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"))
    private static void rbsh$limiterStart(int fps, CallbackInfo ci) {
        Harness.onLimiterStart();
    }

    @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
    private static void rbsh$limiterEnd(int fps, CallbackInfo ci) {
        Harness.onLimiterEnd();
    }
}
