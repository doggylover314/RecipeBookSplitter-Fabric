package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the handling of all packets queued for a frame. */
@Mixin(PacketProcessor.class)
public abstract class PacketProcessorMixin {
    @Inject(method = "processQueuedPackets", at = @At("HEAD"))
    private void rbsh$packetsStart(CallbackInfo ci) {
        Harness.onPacketsStart();
    }

    @Inject(method = "processQueuedPackets", at = @At("RETURN"))
    private void rbsh$packetsEnd(CallbackInfo ci) {
        Harness.onPacketsEnd();
    }
}
