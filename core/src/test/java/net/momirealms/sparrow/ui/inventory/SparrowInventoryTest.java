package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.transaction.InventoryTransactions;
import net.momirealms.sparrow.ui.inventory.transaction.PlannedRoot;
import net.momirealms.sparrow.ui.inventory.transaction.TransactionScope;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparrowInventoryTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void constructorNormalizesAndSnapshotsInitialContents() {
        ItemStack diamond = new ItemStack(Material.DIAMOND, 3);
        ItemStack zeroAmount = new ItemStack(Material.DIAMOND, 1);
        zeroAmount.setAmount(0);
        ItemStack[] initial = {new ItemStack(Material.AIR), null, zeroAmount, diamond};
        TestInventory inventory = new TestInventory(initial);

        assertNull(inventory.itemAt(0));
        assertNull(inventory.itemAt(1));
        assertNull(inventory.itemAt(2));
        assertEquals(new ItemStack(Material.DIAMOND, 3), inventory.itemAt(3));
        diamond.setAmount(1);
        initial[3] = null;

        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(3)));
    }

    @Test
    void readPathsReturnIndependentClones() {
        TestInventory inventory = new TestInventory(new ItemStack[]{new ItemStack(Material.DIAMOND, 5)});
        ItemStack first = inventory.itemAt(0);
        first.setAmount(1);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        ItemStack[] snapshot = inventory.snapshot();
        snapshot[0].setAmount(2);

        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    void itemAtRejectsOutOfBoundsSlots() {
        TestInventory inventory = new TestInventory(2);

        assertThrows(IndexOutOfBoundsException.class, () -> inventory.itemAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.itemAt(2));
    }

    @Test
    void defaultQueriesInspectSnapshotContents() {
        TestInventory inventory = new TestInventory(new ItemStack[]{
                new ItemStack(Material.DIAMOND, 64),
                new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.EMERALD, 2),
                null
        });

        assertFalse(inventory.isFull());
        assertFalse(inventory.isEmpty());
        assertTrue(inventory.hasEmptySlot());
        assertTrue(inventory.contains(item -> item.getType() == Material.EMERALD));
        assertTrue(inventory.containsSimilar(new ItemStack(Material.DIAMOND)));
        assertEquals(2, inventory.count(item -> item.getType() == Material.DIAMOND));
        assertEquals(2, inventory.countSimilar(new ItemStack(Material.DIAMOND)));
        assertTrue(inventory.hasItem(0));
        assertFalse(inventory.hasItem(3));
        assertEquals(64, inventory.itemAmount(0));
        assertEquals(0, inventory.itemAmount(3));
        assertTrue(new TestInventory(new ItemStack[]{new ItemStack(Material.DIAMOND, 64)}).isFull());
        assertTrue(new TestInventory(1).isEmpty());
    }

    @Test
    void bukkitInventoryEventsAreEnabledByDefaultAndRemainMutable() {
        TestInventory inventory = new TestInventory(1);

        assertTrue(inventory.fireBukkitInventoryEvents());
        inventory.fireBukkitInventoryEvents(false);

        assertFalse(inventory.fireBukkitInventoryEvents());
        inventory.fireBukkitInventoryEvents(true);

        assertTrue(inventory.fireBukkitInventoryEvents());
    }

    @Test
    void closedSubscriptionsStopReceivingPostEvents() {
        TestInventory inventory = new TestInventory(1);
        AtomicInteger received = new AtomicInteger();
        Subscription subscription = inventory.subscribePostUpdate(event -> received.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, commitSet(inventory, new ItemStack(Material.DIAMOND, 1)));
        assertEquals(1, received.get());
        subscription.close();

        assertInstanceOf(TransactionResult.Committed.class, commitSet(inventory, new ItemStack(Material.DIAMOND, 2)));
        assertEquals(1, received.get());
    }

    @Test
    void accessRulesCombineGlobalAndSlotConstraints() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicInteger globalCalls = new AtomicInteger();
        AtomicInteger slotCalls = new AtomicInteger();
        inventory.setAccessRule(context -> {
            globalCalls.incrementAndGet();
            assertEquals(8, context.addedAmount());
            return true;
        });
        inventory.setAccessRule(0, context -> {
            slotCalls.incrementAndGet();
            return false;
        });
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.trySetItem(0, diamonds(8)));
        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(1, diamonds(8)));
        assertEquals(2, globalCalls.get());
        assertEquals(1, slotCalls.get());
        inventory.setAccessRule(context -> false);
        inventory.setAccessRule(0, context -> true);
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.trySetItem(0, diamonds(8)));
        inventory.setAccessRule(null);
        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(0, diamonds(8)));
    }

    @Test
    void accessRuleSettersClearOnlyTheirOwnConstraint() {
        VirtualInventory inventory = new VirtualInventory(2);
        AccessRule global = context -> false;
        AccessRule local = context -> false;
        inventory.setAccessRule(global);
        inventory.setAccessRule(0, local);
        assertSame(global, inventory.getAccessRule());
        assertSame(local, inventory.getAccessRule(0));
        inventory.setAccessRule(0, null);
        assertNull(inventory.getAccessRule(0));
        assertSame(TransactionResult.Cancelled.INSTANCE, inventory.trySetItem(0, diamonds(1)));
        inventory.setAccessRule(null);
        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(0, diamonds(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.getAccessRule(2));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setAccessRule(2, local));
    }

    private static TransactionResult commitSet(TestInventory inventory, ItemStack item) {
        PlannedRoot basis = inventory.openPlan();
        SlotChange delta = new SlotChange(0, basis.planned()[0], item);
        return InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(new TransactionScope(basis, List.of(delta))),
                false
        );
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }
}
