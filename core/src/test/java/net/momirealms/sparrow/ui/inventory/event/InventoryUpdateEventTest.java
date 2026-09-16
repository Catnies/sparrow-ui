package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryUpdateEventTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void exposesInventoryAndFindsChangesByLogicalSlot() {
        VirtualInventory inventory = new VirtualInventory(3);
        SlotChange change = new SlotChange(1, null, diamonds(3));
        InventoryPostUpdateEvent event = post(inventory, List.of(change));

        assertSame(inventory, event.inventory());
        assertEquals(1L, event.version());
        assertSame(change, event.changeAt(1));
        assertNull(event.changeAt(0));
        assertThrows(IndexOutOfBoundsException.class, () -> event.changeAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> event.changeAt(3));
    }

    @Test
    void classifiesNetAdditionRemovalMixedAndNoChange() {
        VirtualInventory inventory = new VirtualInventory(2);
        InventoryPostUpdateEvent addition = post(inventory, List.of(new SlotChange(0, null, diamonds(3))));
        InventoryPostUpdateEvent removal = post(inventory, List.of(
                new SlotChange(0, diamonds(5), null),
                new SlotChange(1, null, diamonds(3))
        ));
        InventoryPostUpdateEvent mixed = post(inventory, List.of(
                new SlotChange(0, diamonds(2), new ItemStack(Material.EMERALD, 4))
        ));
        InventoryPostUpdateEvent noChange = post(inventory, List.of(
                new SlotChange(0, diamonds(5), null),
                new SlotChange(1, null, diamonds(5))
        ));

        assertEquals(InventoryNetChange.ADDITION, addition.netChange());
        assertEquals(3, addition.netAddedItems().getFirst().getAmount());
        assertTrue(addition.netRemovedItems().isEmpty());
        assertEquals(InventoryNetChange.REMOVAL, removal.netChange());
        assertEquals(2, removal.netRemovedItems().getFirst().getAmount());
        assertTrue(removal.netAddedItems().isEmpty());
        assertEquals(InventoryNetChange.MIXED, mixed.netChange());
        assertEquals(Material.EMERALD, mixed.netAddedItems().getFirst().getType());
        assertEquals(Material.DIAMOND, mixed.netRemovedItems().getFirst().getType());
        assertEquals(InventoryNetChange.NONE, noChange.netChange());
        assertTrue(noChange.netAddedItems().isEmpty());
        assertTrue(noChange.netRemovedItems().isEmpty());
    }

    @Test
    void netItemListsAreUnmodifiableIndependentCopies() {
        VirtualInventory inventory = new VirtualInventory(1);
        InventoryPostUpdateEvent event = post(inventory, List.of(new SlotChange(0, null, diamonds(3))));
        List<ItemStack> first = event.netAddedItems();
        first.getFirst().setAmount(1);

        assertEquals(3, event.netAddedItems().getFirst().getAmount());
        assertThrows(UnsupportedOperationException.class, () -> first.add(diamonds(1)));
    }

    @Test
    void netItemsMergeSimilarAmountsAndKeepValidStackSizes() {
        VirtualInventory inventory = new VirtualInventory(2);
        InventoryPostUpdateEvent event = post(inventory, List.of(
                new SlotChange(0, null, diamonds(40)),
                new SlotChange(1, null, diamonds(40))
        ));
        List<ItemStack> added = event.netAddedItems();

        assertEquals(2, added.size());
        assertEquals(64, added.get(0).getAmount());
        assertEquals(16, added.get(1).getAmount());
    }

    @Test
    void classifiesChangedSlotsAndOnlyDirections() {
        VirtualInventory inventory = new VirtualInventory(4);
        List<SlotChange> changes = List.of(
                new SlotChange(0, diamonds(2), diamonds(5)),
                new SlotChange(1, diamonds(5), diamonds(2)),
                new SlotChange(2, diamonds(2), new ItemStack(Material.EMERALD, 4)),
                new SlotChange(3, diamonds(2), diamonds(2))
        );
        InventoryPostUpdateEvent event = post(inventory, changes);
        InventoryChange rootChange = event.rootChanges().getFirst();

        assertEquals(List.of(changes.get(0), changes.get(2)), event.slotChanges(SlotChange::isAdd));
        assertEquals(List.of(changes.get(1), changes.get(2)), event.slotChanges(SlotChange::isRemove));
        assertEquals(List.of(changes.get(0)), event.slotChanges(SlotChange::isAddOnly));
        assertEquals(List.of(changes.get(1)), event.slotChanges(SlotChange::isRemoveOnly));
        assertEquals(List.of(changes.get(3)), event.slotChanges(SlotChange::isUnchanged));
        assertEquals(List.of(changes.get(2)), event.slotChanges(SlotChange::isReplacement));
        assertFalse(event.isAddOnly());
        assertFalse(event.isRemoveOnly());
        assertEquals(event.slotChanges(SlotChange::isAdd), rootChange.slotChanges(SlotChange::isAdd));
        assertEquals(event.slotChanges(SlotChange::isRemove), rootChange.slotChanges(SlotChange::isRemove));
        assertEquals(event.slotChanges(SlotChange::isAddOnly), rootChange.slotChanges(SlotChange::isAddOnly));
        assertEquals(event.slotChanges(SlotChange::isRemoveOnly), rootChange.slotChanges(SlotChange::isRemoveOnly));
        assertEquals(event.slotChanges(SlotChange::isUnchanged), rootChange.slotChanges(SlotChange::isUnchanged));
        assertEquals(event.slotChanges(SlotChange::isReplacement), rootChange.slotChanges(SlotChange::isReplacement));
        assertFalse(rootChange.isAddOnly());
        assertFalse(rootChange.isRemoveOnly());
        assertThrows(UnsupportedOperationException.class, () -> event.slotChanges(SlotChange::isAdd).add(changes.get(3)));
    }

    @Test
    void onlyDirectionIgnoresUnchangedSlotsAndRejectsEmptyChanges() {
        VirtualInventory inventory = new VirtualInventory(2);
        List<SlotChange> unchanged = List.of(new SlotChange(1, diamonds(2), diamonds(2)));
        InventoryPostUpdateEvent addOnly = post(inventory, List.of(
                new SlotChange(0, diamonds(2), diamonds(5)),
                unchanged.getFirst()
        ));
        InventoryPostUpdateEvent removeOnly = post(inventory, List.of(
                new SlotChange(0, diamonds(5), diamonds(2)),
                unchanged.getFirst()
        ));
        InventoryPostUpdateEvent unchangedOnly = post(inventory, unchanged);
        InventoryPostUpdateEvent empty = post(inventory, List.of());

        assertTrue(addOnly.isAddOnly());
        assertFalse(addOnly.isRemoveOnly());
        assertTrue(removeOnly.isRemoveOnly());
        assertFalse(removeOnly.isAddOnly());
        assertFalse(unchangedOnly.isAddOnly());
        assertFalse(unchangedOnly.isRemoveOnly());
        assertFalse(empty.isAddOnly());
        assertFalse(empty.isRemoveOnly());
        assertTrue(addOnly.rootChanges().getFirst().isAddOnly());
        assertTrue(removeOnly.rootChanges().getFirst().isRemoveOnly());
    }

    @Test
    void preEventExposesTheSameQueriesAndTracksCancellation() {
        VirtualInventory inventory = new VirtualInventory(1);
        SlotChange change = new SlotChange(0, null, diamonds(2));
        List<SlotChange> slotChanges = List.of(change);
        InventoryPreUpdateEvent event = new InventoryPreUpdateEvent(
                inventory,
                UpdateReason.Program.INSTANCE,
                List.of(new TransactionScope(inventory.openPlan(), slotChanges)),
                false,
                null,
                null
        );

        assertSame(inventory, event.inventory());
        assertSame(change, event.changeAt(0));
        assertEquals(InventoryNetChange.ADDITION, event.netChange());
        event.setCancelled(true);

        assertTrue(event.cancelled());
        event.setCancelled(false);

        assertFalse(event.cancelled());
    }

    private static InventoryPostUpdateEvent post(VirtualInventory inventory, List<SlotChange> slotChanges) {
        return new InventoryPostUpdateEvent(
                inventory,
                UpdateReason.Program.INSTANCE,
                List.of(new TransactionScope(inventory.openPlan(), slotChanges)),
                1L
        );
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
