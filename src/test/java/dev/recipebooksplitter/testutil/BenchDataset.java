package dev.recipebooksplitter.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/**
 * Recipe books shaped like the data pack of the e2e kit: small vanilla-like entries, followed by entries whose result
 * carries random text in custom_data. Needs {@link RecipeFixtures#bootstrap()}.
 */
public final class BenchDataset {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Item[] INGREDIENTS = {Items.STICK, Items.DIRT, Items.COBBLESTONE, Items.OAK_PLANKS, Items.SAND};
    private static final Item[] FILLER_RESULTS = {Items.OAK_PLANKS, Items.STICK, Items.TORCH, Items.CHEST, Items.BREAD,
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.BOWL, Items.PAPER, Items.BOOK};

    private BenchDataset() {}

    /**
     * Fixed seed, fixed result: the same arguments give the same entries.
     *
     * @param custom number of shapeless entries with a paper result whose custom_data is {@code {p0: <random text>, i:
     *               <index>}}; they come after the fillers, with the ids following theirs
     * @param pad length of the random text (letters and digits) of those entries
     * @param filler number of small shaped entries without custom data
     */
    public static List<Entry> book(int custom, int pad, int filler, long seed) {
        Random random = new Random(seed);
        List<Entry> entries = new ArrayList<>(custom + filler);
        int id = 0;
        for (int f = 0; f < filler; f++, id++) {
            entries.add(fillerEntry(id, INGREDIENTS[f % 5], INGREDIENTS[(f / 5) % 5], new ItemStack(FILLER_RESULTS[f % FILLER_RESULTS.length]), (byte) (f % 4)));
        }
        for (int i = 0; i < custom; i++, id++) {
            StringBuilder text = new StringBuilder(pad);
            for (int k = 0; k < pad; k++) {
                text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            CompoundTag tag = new CompoundTag();
            tag.putString("p0", text.toString());
            tag.putInt("i", i);
            ItemStack result = new ItemStack(Items.PAPER);
            result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            entries.add(customEntry(id, INGREDIENTS[i % 5], INGREDIENTS[(i / 5) % 5], result, (byte) (i % 4)));
        }
        return entries;
    }

    /** A shaped 3x3 recipe with two distinct ingredients, a group id and a category, like the vanilla ones. */
    private static Entry fillerEntry(int id, Item a, Item b, ItemStack result, byte flags) {
        List<SlotDisplay> slots = new ArrayList<>();
        List<Ingredient> requirements = new ArrayList<>();
        for (int k = 0; k < 9; k++) {
            Item item = k % 3 == 1 ? b : a;
            slots.add(new SlotDisplay.ItemSlotDisplay(item));
            requirements.add(Ingredient.of(item));
        }
        var display = new ShapedCraftingRecipeDisplay(3, 3, slots,
                new SlotDisplay.ItemStackSlotDisplay(result), new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        var contents = new RecipeDisplayEntry(new RecipeDisplayId(id), display, OptionalInt.of(id % 40),
                RecipeBookCategories.CRAFTING_BUILDING_BLOCKS, Optional.of(requirements));
        return new Entry(contents, flags);
    }

    private static Entry customEntry(int id, Item a, Item b, ItemStack result, byte flags) {
        var display = new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.ItemSlotDisplay(a), new SlotDisplay.ItemSlotDisplay(b)),
                new SlotDisplay.ItemStackSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        var contents = new RecipeDisplayEntry(new RecipeDisplayId(id), display, OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC, Optional.of(List.of(Ingredient.of(a), Ingredient.of(b))));
        return new Entry(contents, flags);
    }
}
