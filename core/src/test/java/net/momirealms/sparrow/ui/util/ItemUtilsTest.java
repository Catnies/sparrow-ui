package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUtilsTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void normalizesMissingItemsToEmptyStacks() {
        assertTrue(ItemUtils.copyOrEmpty(null).isEmpty());
        assertTrue(ItemUtils.copyOrEmpty(ItemStack.empty()).isEmpty());
    }

    @Test
    void returnsAnIndependentCopyOfNonEmptyItems() {
        ItemStack source = new ItemStack(Material.DIAMOND, 3);
        ItemStack copy = ItemUtils.copyOrEmpty(source);
        copy.setAmount(1);

        assertNotSame(source, copy);
        assertEquals(3, source.getAmount());
    }

    @Test
    void nullIfEmptyCollapsesEmptyRepresentationsToNull() {
        assertNull(ItemUtils.nullIfEmpty(null));
        assertNull(ItemUtils.nullIfEmpty(new ItemStack(Material.AIR)));
        assertNull(ItemUtils.nullIfEmpty(zeroAmountDiamond()));
        ItemStack negative = new ItemStack(Material.DIAMOND, 1);
        negative.setAmount(-2);

        assertNull(ItemUtils.nullIfEmpty(negative));
    }

    @Test
    void nullIfEmptyKeepsNonEmptyStacksUntouched() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 3);

        assertSame(stack, ItemUtils.nullIfEmpty(stack));
    }

    @Test
    void recognizesEmptyStacks() {
        assertTrue(ItemUtils.isNullOrEmpty(null));
        assertTrue(ItemUtils.isNullOrEmpty(new ItemStack(Material.AIR)));
        assertTrue(ItemUtils.isNullOrEmpty(zeroAmountDiamond()));
        assertFalse(ItemUtils.isNullOrEmpty(new ItemStack(Material.DIAMOND, 1)));
    }

    @Test
    void clonesIndependentCopies() {
        assertNull(ItemUtils.copyOrNull(null));
        ItemStack source = new ItemStack(Material.DIAMOND, 3);
        ItemStack copy = ItemUtils.copyOrNull(source);

        assertNotSame(source, copy);
        assertEquals(source, copy);
        copy.setAmount(1);

        assertEquals(3, source.getAmount());
    }

    @Test
    void similarityRequiresBothStacks() {
        ItemStack diamond = new ItemStack(Material.DIAMOND, 1);

        assertFalse(ItemUtils.isSimilar(null, null));
        assertFalse(ItemUtils.isSimilar(diamond, null));
        assertFalse(ItemUtils.isSimilar(null, diamond));
    }

    @Test
    void similarityIgnoresAmountButNotType() {
        assertTrue(ItemUtils.isSimilar(new ItemStack(Material.DIAMOND, 1), new ItemStack(Material.DIAMOND, 42)));
        assertFalse(ItemUtils.isSimilar(new ItemStack(Material.DIAMOND, 1), new ItemStack(Material.EMERALD, 1)));
    }

    @Test
    void amountOfTreatsEmptyAsZero() {
        assertEquals(0, ItemUtils.amountOf(null));
        assertEquals(0, ItemUtils.amountOf(new ItemStack(Material.AIR)));
        assertEquals(7, ItemUtils.amountOf(new ItemStack(Material.DIAMOND, 7)));
    }

    @Test
    void handleComparisonTreatsEveryEmptyRepresentationAsEmpty() {
        assertTrue(ItemUtils.isHandleContentEqual(ItemStackProxy.EMPTY, null));
        assertTrue(ItemUtils.isHandleContentEqual(ItemStackProxy.EMPTY, new ItemStack(Material.AIR)));
        assertTrue(ItemUtils.isHandleContentEqual(handleOf(zeroAmountDiamond()), null));
        assertFalse(ItemUtils.isHandleContentEqual(ItemStackProxy.EMPTY, new ItemStack(Material.DIAMOND, 1)));
    }

    @Test
    void handleComparisonMatchesTypeAndAmount() {
        Object handle = handleOf(new ItemStack(Material.DIAMOND, 5));

        assertTrue(ItemUtils.isHandleContentEqual(handle, new ItemStack(Material.DIAMOND, 5)));
        assertFalse(ItemUtils.isHandleContentEqual(handle, new ItemStack(Material.DIAMOND, 4)));
        assertFalse(ItemUtils.isHandleContentEqual(handle, new ItemStack(Material.EMERALD, 5)));
        assertFalse(ItemUtils.isHandleContentEqual(handle, null));
    }

    @Test
    void handleComparisonAgreesWithTheBukkitLevelOne() {
        ItemStack[] samples = {
                null,
                new ItemStack(Material.AIR),
                zeroAmountDiamond(),
                new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.DIAMOND, 5),
                new ItemStack(Material.EMERALD, 5)
        };
        for (int a = 0; a < samples.length; a++) {
            for (int b = 0; b < samples.length; b++) {
                Object handle = samples[a] == null ? ItemStackProxy.EMPTY : handleOf(samples[a]);

                assertEquals(
                        ItemUtils.isContentEqual(samples[a], samples[b]),
                        ItemUtils.isHandleContentEqual(handle, samples[b]),
                        "样本 " + a + " 与 " + b + " 的两种比法结论不一致"
                );
            }
        }
    }

    private static Object handleOf(ItemStack item) {
        return ItemUtils.getItemStackHandle(item);
    }

    private static ItemStack zeroAmountDiamond() {
        ItemStack stack = new ItemStack(Material.DIAMOND, 1);
        stack.setAmount(0);
        return stack;
    }
}
