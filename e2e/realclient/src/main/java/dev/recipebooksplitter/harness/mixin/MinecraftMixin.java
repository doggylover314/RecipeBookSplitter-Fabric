package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts frames and ticks; queued packets are handled at the start of a frame. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void rbsh$frame(boolean advanceGameTime, CallbackInfo ci) {
        Harness.onFrame();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rbsh$tick(CallbackInfo ci) {
        Harness.onTick((Minecraft) (Object) this);
    }
}
