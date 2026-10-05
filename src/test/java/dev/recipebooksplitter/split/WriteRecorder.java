package dev.recipebooksplitter.split;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Sits in front of the encoder and records what a connection writes, and how our send guard looked at that moment. */
final class WriteRecorder extends ChannelOutboundHandlerAdapter {
    // Written on the event loop, read by the test thread.
    final List<Object> messages = new CopyOnWriteArrayList<>();
    /** For each write, the packet our code was re-sending at that moment (null if the write is not ours). */
    final List<Object> resent = new CopyOnWriteArrayList<>();
    /** For each write, whether it carried a promise of its own (a send listener) instead of the shared void promise. */
    final List<Boolean> withListener = new CopyOnWriteArrayList<>();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        messages.add(msg);
        resent.add(RecipeBookSendInterceptor.currentlyResent());
        withListener.add(!promise.isVoid());
        ctx.write(msg, promise);
    }
}
