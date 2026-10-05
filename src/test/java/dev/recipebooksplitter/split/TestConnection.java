package dev.recipebooksplitter.split;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

/** A server-side {@link Connection} on an {@link EmbeddedChannel}: [encoder], recorder, packet_handler. */
record TestConnection(Connection connection, EmbeddedChannel channel, WriteRecorder recorder) implements AutoCloseable {
    /** @param encoder goes into the pipeline as "encoder"; null leaves the pipeline without one */
    static TestConnection create(@Nullable PacketEncoder<?> encoder) throws Exception {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        WriteRecorder recorder = new WriteRecorder();
        EmbeddedChannel channel = new EmbeddedChannel(false, false);
        // Outbound packets travel packet_handler -> recorder -> encoder.
        if (encoder != null) {
            channel.pipeline().addLast(HandlerNames.ENCODER, encoder);
        }
        channel.pipeline().addLast("recorder", recorder);
        channel.pipeline().addLast(HandlerNames.PACKET_HANDLER, connection);
        channel.register();
        return new TestConnection(connection, channel, recorder);
    }

    @Override
    public void close() {
        channel.finishAndReleaseAll();
    }
}
