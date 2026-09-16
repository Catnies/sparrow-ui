package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.handle.FurnaceMenuHandleImpl;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FurnaceMenuHandleTest {

    @Test
    void fullSynchronizationAlwaysContainsAllNativeDataSlots() {
        FurnaceMenuHandleImpl.DataSlots dataSlots = new FurnaceMenuHandleImpl.DataSlots();
        dataSlots.queue(true);

        assertEquals(List.of(0, 1, 2, 3), queuedSlots(dataSlots));
        assertEquals(0, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.FUEL_REMAINING_SLOT));
        assertEquals(200, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.FUEL_TOTAL_SLOT));
        assertEquals(0, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.COOK_ELAPSED_SLOT));
        assertEquals(200, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.COOK_TOTAL_SLOT));
        dataSlots.commit();
        dataSlots.queue(false);

        assertEquals(List.of(), queuedSlots(dataSlots));
    }

    @Test
    void incrementalSynchronizationRetainsDirtyNumeratorsUntilCommit() {
        FurnaceMenuHandleImpl.DataSlots dataSlots = new FurnaceMenuHandleImpl.DataSlots();
        dataSlots.setFuelProgress(0.25);
        dataSlots.setCookProgress(0.5);
        dataSlots.queue(false);

        assertEquals(List.of(0, 2), queuedSlots(dataSlots));
        assertEquals(50, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.FUEL_REMAINING_SLOT));
        assertEquals(100, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.COOK_ELAPSED_SLOT));
        dataSlots.queue(false);

        assertEquals(List.of(0, 2), queuedSlots(dataSlots));
        dataSlots.commit();
        dataSlots.setFuelProgress(0.251);
        dataSlots.setCookProgress(0.501);
        dataSlots.queue(false);

        assertEquals(List.of(), queuedSlots(dataSlots));
        dataSlots.setFuelProgress(0.0);
        dataSlots.setCookProgress(1.0);
        dataSlots.queue(false);

        assertEquals(List.of(0, 2), queuedSlots(dataSlots));
        assertEquals(0, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.FUEL_REMAINING_SLOT));
        assertEquals(200, dataSlots.value(FurnaceMenuHandleImpl.DataSlots.COOK_ELAPSED_SLOT));
    }

    @Test
    void unknownNativeDataSlotIsRejected() {
        FurnaceMenuHandleImpl.DataSlots dataSlots = new FurnaceMenuHandleImpl.DataSlots();

        assertThrows(IndexOutOfBoundsException.class, () -> dataSlots.value(4));
    }

    private static List<Integer> queuedSlots(FurnaceMenuHandleImpl.DataSlots dataSlots) {
        ArrayList<Integer> slots = new ArrayList<>(4);
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
