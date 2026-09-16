package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.handle.EnchantmentMenuHandleImpl;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnchantmentMenuHandleTest {

    @Test
    void fullSynchronizationAlwaysContainsAllNativeDataSlots() {
        EnchantmentMenuHandleImpl.DataSlots dataSlots = new EnchantmentMenuHandleImpl.DataSlots();
        dataSlots.queue(true);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), queuedSlots(dataSlots));
        assertEquals(0, dataSlots.value(0));
        assertEquals(0, dataSlots.value(1));
        assertEquals(0, dataSlots.value(2));
        assertEquals(0, dataSlots.value(EnchantmentMenuHandleImpl.DataSlots.ENCHANTMENT_SEED_SLOT));
        assertEquals(-1, dataSlots.value(4));
        assertEquals(-1, dataSlots.value(5));
        assertEquals(-1, dataSlots.value(6));
        assertEquals(-1, dataSlots.value(7));
        assertEquals(-1, dataSlots.value(8));
        assertEquals(-1, dataSlots.value(9));
        dataSlots.commit();
        dataSlots.queue(false);

        assertEquals(List.of(), queuedSlots(dataSlots));
    }

    @Test
    void incrementalSynchronizationRetainsOnlyChangedOptionAndSeedSlotsUntilCommit() {
        EnchantmentMenuHandleImpl.DataSlots dataSlots = new EnchantmentMenuHandleImpl.DataSlots();
        dataSlots.setOption(1, 7, 42, 11);
        dataSlots.setEnchantmentSeed(73);
        dataSlots.queue(false);

        assertEquals(List.of(1, 3, 5, 8), queuedSlots(dataSlots));
        assertEquals(7, dataSlots.value(1));
        assertEquals(73, dataSlots.value(3));
        assertEquals(42, dataSlots.value(5));
        assertEquals(11, dataSlots.value(8));
        dataSlots.queue(false);

        assertEquals(List.of(1, 3, 5, 8), queuedSlots(dataSlots));
        dataSlots.commit();
        dataSlots.setOption(1, 7, 42, 11);
        dataSlots.setEnchantmentSeed(73);
        dataSlots.queue(false);

        assertEquals(List.of(), queuedSlots(dataSlots));
        dataSlots.setOption(1, 0, -1, -1);
        dataSlots.queue(false);

        assertEquals(List.of(1, 5, 8), queuedSlots(dataSlots));
        assertEquals(0, dataSlots.value(1));
        assertEquals(-1, dataSlots.value(5));
        assertEquals(-1, dataSlots.value(8));
    }

    @Test
    void clientPredictionInvalidationQueuesAllNativeDataSlotsUntilCommit() {
        EnchantmentMenuHandleImpl.DataSlots dataSlots = new EnchantmentMenuHandleImpl.DataSlots();
        dataSlots.setOption(0, 1, 42, 3);
        dataSlots.setOption(1, 2, -1, -1);
        dataSlots.setEnchantmentSeed(73);
        dataSlots.queue(false);
        dataSlots.commit();
        dataSlots.notifyUpdateEnchantmentOptions();
        dataSlots.queue(false);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), queuedSlots(dataSlots));
        int[] restoredClientData = new int[EnchantmentMenuHandleImpl.DataSlots.DATA_SLOT_COUNT];
        for (int slot = dataSlots.nextQueuedSlot(0); slot >= 0; slot = dataSlots.nextQueuedSlot(slot + 1)) {
            restoredClientData[slot] = dataSlots.value(slot);
        }

        assertArrayEquals(new int[] {1, 2, 0, 73, 42, -1, -1, 3, -1, -1}, restoredClientData);
        dataSlots.queue(false);

        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), queuedSlots(dataSlots));
        dataSlots.commit();
        dataSlots.queue(false);

        assertEquals(List.of(), queuedSlots(dataSlots));
    }

    @Test
    void invalidOptionAndDataSlotIndicesAreRejected() {
        EnchantmentMenuHandleImpl.DataSlots dataSlots = new EnchantmentMenuHandleImpl.DataSlots();

        assertThrows(IndexOutOfBoundsException.class, () -> dataSlots.setOption(-1, 1, -1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dataSlots.setOption(3, 1, -1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> dataSlots.value(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> dataSlots.value(10));
    }

    private static List<Integer> queuedSlots(EnchantmentMenuHandleImpl.DataSlots dataSlots) {
        ArrayList<Integer> slots = new ArrayList<>(EnchantmentMenuHandleImpl.DataSlots.DATA_SLOT_COUNT);
        for (
                int slot = dataSlots.nextQueuedSlot(0);
                slot >= 0;
                slot = dataSlots.nextQueuedSlot(slot + 1)
        ) {
            slots.add(slot);
        }
        return slots;
    }
}
