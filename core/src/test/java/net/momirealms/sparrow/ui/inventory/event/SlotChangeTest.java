package net.momirealms.sparrow.ui.inventory.event;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotChangeTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void constructorIsolatesInputsAndNormalizesEmpty() {
        ItemStack input = diamonds(3);

        assertNotSame(input, new SlotChange(0, null, input).unsafeAfter());
        assertNotSame(input, new SlotChange(0, input, null).unsafeBefore());
        assertNull(new SlotChange(0, null, ItemStack.empty()).unsafeAfter());
    }

    @Test
    void identifiesAddedItemsAndAmount() {
        SlotChange inserted = new SlotChange(0, null, diamonds(3));
        SlotChange increased = new SlotChange(0, diamonds(2), diamonds(5));

        assertTrue(inserted.isAdd());
        assertEquals(3, inserted.addedAmount());
        assertEquals(0, inserted.removedAmount());
        assertFalse(inserted.isRemove());
        assertFalse(inserted.isReplacement());
        assertTrue(increased.isAdd());
        assertEquals(3, increased.addedAmount());
        assertEquals(0, increased.removedAmount());
    }

    @Test
    void identifiesRemovedItemsAndAmount() {
        SlotChange cleared = new SlotChange(0, diamonds(5), null);
        SlotChange decreased = new SlotChange(0, diamonds(5), diamonds(2));

        assertTrue(cleared.isRemove());
        assertEquals(5, cleared.removedAmount());
        assertEquals(0, cleared.addedAmount());
        assertFalse(cleared.isAdd());
        assertFalse(cleared.isReplacement());
        assertTrue(decreased.isRemove());
        assertEquals(3, decreased.removedAmount());
        assertEquals(0, decreased.addedAmount());
    }

    @Test
    void replacementReportsBothGrossFlowsAndUnchangedReportsZero() {
        SlotChange replacement = new SlotChange(0, diamonds(2), new ItemStack(Material.EMERALD, 4));
        SlotChange unchanged = new SlotChange(0, diamonds(2), diamonds(2));

        assertTrue(replacement.isReplacement());
        assertTrue(replacement.isAdd());
        assertTrue(replacement.isRemove());
        assertEquals(4, replacement.addedAmount());
        assertEquals(2, replacement.removedAmount());
        assertFalse(unchanged.isReplacement());
        assertFalse(unchanged.isAdd());
        assertFalse(unchanged.isRemove());
        assertEquals(0, unchanged.addedAmount());
        assertEquals(0, unchanged.removedAmount());
    }

    @Test
    void classifiesAddOnlyRemoveOnlyReplacementAndUnchanged() {
        SlotChange addOnly = new SlotChange(0, diamonds(2), diamonds(5));
        SlotChange removeOnly = new SlotChange(0, diamonds(5), diamonds(2));
        SlotChange replacement = new SlotChange(0, diamonds(2), new ItemStack(Material.EMERALD, 4));
        SlotChange unchanged = new SlotChange(0, diamonds(2), diamonds(2));

        assertTrue(addOnly.isAddOnly());
        assertFalse(addOnly.isRemoveOnly());
        assertFalse(addOnly.isUnchanged());
        assertFalse(removeOnly.isAddOnly());
        assertTrue(removeOnly.isRemoveOnly());
        assertFalse(removeOnly.isUnchanged());
        assertFalse(replacement.isAddOnly());
        assertFalse(replacement.isRemoveOnly());
        assertFalse(replacement.isUnchanged());
        assertFalse(unchanged.isAddOnly());
        assertFalse(unchanged.isRemoveOnly());
        assertTrue(unchanged.isUnchanged());
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
