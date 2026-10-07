package dev.recipebooksplitter.testutil;

import dev.recipebooksplitter.split.EntrySizer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * Recipe entries and a bootstrapped play protocol for tests. Entries only use built-in registries, so a static
 * {@link RegistryAccess} is enough to encode and decode them.
 */
public final class RecipeFixtures {
    private static RegistryAccess access;
    private static ProtocolInfo<ClientGamePacketListener> protocol;

    private RecipeFixtures() {}

    /** Bootstraps Minecraft's registries once and binds the clientbound play protocol to them. */
    public static synchronized void bootstrap() {
        if (protocol == null) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access));
        }
    }

    public static RegistryAccess access() {
        return access;
    }

    public static ProtocolInfo<ClientGamePacketListener> protocol() {
        return protocol;
    }

    /** Writes packets like {@code PacketEncoder.encode} does: the protocol codec, which writes the packet id first. */
    @SuppressWarnings("unchecked")
    public static EntrySizer.PacketWriter writer() {
        return (packet, out) -> protocol.codec().encode(out, (Packet<? super ClientGamePacketListener>) packet);
    }

    /** The caller releases the returned buffer. */
    public static ByteBuf encode(Packet<?> packet) throws Exception {
        ByteBuf out = Unpooled.buffer();
        writer().write(packet, out);
        return out;
    }

    /** Consumes and releases {@code frame}. */
    public static Packet<?> decode(ByteBuf frame) {
        try {
            return protocol.codec().decode(frame);
        } finally {
            frame.release();
        }
    }

    /** Hex of the entry's wire encoding; two entries are the same on the wire iff their renderings are equal. */
    public static String render(RecipeDisplayEntry contents) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            RecipeDisplayEntry.STREAM_CODEC.encode(buf, contents);
            return HexFormat.of().formatHex(ByteBufUtil.getBytes(buf));
        } finally {
            buf.release();
        }
    }

    /**
     * @param padBytes size of an opaque custom_data payload on the result item, to give entries a controllable size
     */
    public static ClientboundRecipeBookAddPacket.Entry entry(int displayId, int padBytes, byte flags) {
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("pad", new byte[padBytes]);
        return entryWithCustomData(displayId, tag, flags);
    }

    /** Like {@link #entry}, but the pad is random bytes (the same for the same id), which DEFLATE cannot compress. */
    public static ClientboundRecipeBookAddPacket.Entry incompressibleEntry(int displayId, int padBytes, byte flags) {
        byte[] pad = new byte[padBytes];
        new Random(displayId).nextBytes(pad);
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("pad", pad);
        return entryWithCustomData(displayId, tag, flags);
    }

    /** An entry whose result item carries {@code tag} as custom_data. */
    public static ClientboundRecipeBookAddPacket.Entry entryWithCustomData(int displayId, CompoundTag tag, byte flags) {
        ItemStack result = new ItemStack(Items.PAPER);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        var display = new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.ItemSlotDisplay(Items.STICK), new SlotDisplay.ItemSlotDisplay(Items.DIAMOND)),
                new SlotDisplay.ItemStackSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        var contents = new RecipeDisplayEntry(
                new RecipeDisplayId(displayId),
                display,
                displayId % 3 == 0 ? OptionalInt.of(displayId % 5) : OptionalInt.empty(),
                displayId % 2 == 0 ? RecipeBookCategories.CRAFTING_MISC : RecipeBookCategories.CRAFTING_BUILDING_BLOCKS,
                displayId % 4 == 0
                        ? Optional.empty()
                        : Optional.of(List.of(Ingredient.of(Items.STICK), Ingredient.of(Items.DIAMOND))));
        return new ClientboundRecipeBookAddPacket.Entry(contents, flags);
    }

    /** Entries with ids 0..count-1, 2000 to 5000 bytes each, and all four flag combinations. */
    public static List<ClientboundRecipeBookAddPacket.Entry> entries(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> entry(i, 2000 + (i * 37) % 3000, (byte) (i % 4)))
                .toList();
    }
}
