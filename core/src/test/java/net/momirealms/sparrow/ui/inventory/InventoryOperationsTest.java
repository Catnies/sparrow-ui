package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryOperationsTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> {
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void setItemOverwritesAndClearsWithoutStackLimit() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSize(0, 5);

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(reason(), 0, diamonds(50)));
        assertEquals(50, ItemUtils.amountOf(inventory.itemAt(0)));
        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(reason(), 0, null));
        assertNull(inventory.itemAt(0));
    }

    @Test
    void putItemFillsEmptyMergesSimilarAndRejectsDissimilar() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSize(0, 10);
        AddResult first = inventory.tryPutItem(reason(), 0, diamonds(15));

        assertInstanceOf(TransactionResult.Committed.class, first.result());
        assertEquals(5, first.remaining());
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(0)));
        AddResult second = inventory.tryPutItem(reason(), 0, diamonds(3));

        assertEquals(3, second.remaining());
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(0)));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        AddResult third = inventory.tryPutItem(reason(), 0, new ItemStack(Material.EMERALD, 4));

        assertInstanceOf(TransactionResult.Committed.class, third.result());
        assertEquals(4, third.remaining());
        assertEquals(0, postCalls.get());
    }

    @Test
    void modifyItemReceivesCloneAndAppliesResult() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.trySetItem(reason(), 0, diamonds(5));

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryModifyItem(reason(), 0, current -> {
            current.setAmount(1);
            return new ItemStack(Material.EMERALD, 7);
        }));

        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(7, inventory.itemAt(0).getAmount());
        inventory.trySetItem(reason(), 0, null);

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryModifyItem(reason(), 0, current -> {
            assertNull(current);
            return diamonds(2);
        }));

        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    void changeAmountClampsToEffectiveRangeAndClearsAtZero() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.setMaxStackSize(0, 10);
        inventory.trySetItem(reason(), 0, diamonds(5));

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, 100));
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(0)));
        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, -100));
        assertNull(inventory.itemAt(0));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, 3));
        inventory.trySetItem(reason(), 0, diamonds(4));

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, 0));
        assertEquals(1, postCalls.get());
    }

    @Test
    void addMergesPartialStacksBeforeEmptySlotsInConfiguredOrder() {
        VirtualInventory inventory = new VirtualInventory(4);
        inventory.setMaxStackSizes(new int[]{10, 10, 10, 10});
        inventory.setIterationOrder(OperationCategory.ADD, SlotOrder.of(3, 2, 1, 0));
        inventory.trySetItem(reason(), 1, diamonds(7));
        AddResult result = inventory.tryAdd(reason(), diamonds(6));

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(0, result.remaining());
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(3)));
        assertNull(inventory.itemAt(2));
        assertNull(inventory.itemAt(0));
    }

    @Test
    void addReportsRemainingWhenInventoryCannotHoldEverything() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{5, 5});
        inventory.trySetItem(reason(), 0, diamonds(4));
        AddResult result = inventory.tryAdd(reason(), diamonds(9));

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(3, result.remaining());
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(1)));
        AddResult overflow = inventory.tryAdd(reason(), diamonds(2));

        assertInstanceOf(TransactionResult.Committed.class, overflow.result());
        assertEquals(2, overflow.remaining());
    }

    @Test
    void placementRulesRunOnlyForStructurallyViableAddSlots() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSizes(new int[]{10, 10, 10});
        inventory.trySetItem(reason(), 0, new ItemStack(Material.EMERALD, 1));
        inventory.trySetItem(reason(), 1, diamonds(10));
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        AddResult result = inventory.tryAdd(reason(), diamonds(5));

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(5, result.remaining());
        assertEquals(1, ruleCalls.get());
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
        assertNull(inventory.itemAt(2));
    }

    @Test
    void collectTakesPartialStacksBeforeFullStacks() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSizes(new int[]{10, 10, 10});
        inventory.trySetItem(reason(), 0, diamonds(10));
        inventory.trySetItem(reason(), 1, diamonds(3));
        inventory.trySetItem(reason(), 2, diamonds(10));
        CollectResult result = inventory.tryCollect(reason(), diamonds(1), 12);

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(12, result.collected());
        assertNull(inventory.itemAt(1));
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(10, ItemUtils.amountOf(inventory.itemAt(2)));
    }

    @Test
    void removeHonorsPredicateAndUpToAcrossSlots() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.trySetItem(reason(), 0, diamonds(4));
        inventory.trySetItem(reason(), 1, new ItemStack(Material.EMERALD, 5));
        inventory.trySetItem(reason(), 2, diamonds(6));
        RemoveResult result = inventory.tryRemove(reason(), stack -> stack.getType() == Material.DIAMOND, 7);

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(7, result.removed());
        assertNull(inventory.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(3, ItemUtils.amountOf(inventory.itemAt(2)));
    }

    @Test
    void clearEmptiesAllSlotsInOneTransaction() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.trySetItem(reason(), 0, diamonds(4));
        inventory.trySetItem(reason(), 2, new ItemStack(Material.EMERALD, 5));
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        TransactionResult result = inventory.tryClear(reason());

        TransactionResult.Committed committed = assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(1, committed.rootChanges().size());
        assertEquals(2, committed.rootChanges().getFirst().slotChanges().size());
        assertEquals(1, preCalls.get());
        assertEquals(1, postCalls.get());
        assertTrue(inventory.isEmpty());
    }

    @Test
    void clearOnEmptyInventoryCommitsWithoutEvents() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryClear(reason()));
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
    }

    @Test
    void simulationsAreSideEffectFreeAndMatchRealOperations() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{5, 5});
        inventory.trySetItem(reason(), 0, diamonds(4));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        ItemStack[] before = inventory.snapshot();
        int remaining = inventory.simulateAdd(diamonds(9));
        int collectable = inventory.simulateCollect(diamonds(1), 99);

        assertTrue(inventory.mayPlace(diamonds(6)));
        assertFalse(inventory.mayPlace(diamonds(7)));
        assertEquals(0, postCalls.get());
        ItemStack[] after = inventory.snapshot();
        for (int i = 0; i < before.length; i++) {
            assertEquals(ItemUtils.amountOf(before[i]), ItemUtils.amountOf(after[i]));
        }

        assertEquals(3, remaining);
        assertEquals(inventory.tryAdd(reason(), diamonds(9)).remaining(), remaining);
        assertEquals(4, collectable);
    }

    @Test
    void multiItemSimulationSharesOnePlannerSnapshot() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{5, 5});
        ItemStack diamonds = diamonds(7);
        ItemStack emeralds = new ItemStack(Material.EMERALD, 4);

        assertEquals(List.of(0, 4), ints(inventory.simulateAdd(List.of(diamonds, emeralds))));
        assertEquals(List.of(0, 4), ints(inventory.simulateAdd(diamonds, emeralds)));
        assertEquals(List.of(0, 4), ints(inventory.simulateAdd(new ItemStack[]{diamonds, emeralds})));
        assertEquals(List.of(), ints(inventory.simulateAdd()));
        assertFalse(inventory.mayPlace(diamonds, emeralds));
        assertFalse(inventory.mayPlace(new ItemStack[]{diamonds, emeralds}));
        assertTrue(inventory.mayPlace());
        assertFalse(inventory.mayPlace(diamonds, emeralds));
        assertTrue(inventory.mayPlace(diamonds(5), emeralds));
        assertTrue(inventory.isEmpty());
    }

    @Test
    void requestSimulationChecksRulesAndCapacitySimulationBypassesThem() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        AddResult put = inventory.tryPutItem(UpdateReason.External.INSTANCE, 0, diamonds(3));
        int simulated = inventory.simulateAdd(diamonds(3));
        boolean canHold = inventory.mayPlace(diamonds(3));

        assertEquals(3, put.remaining());
        assertEquals(0, simulated);
        assertTrue(canHold);
        assertEquals(1, ruleCalls.get());
        assertEquals(3, inventory.simulateTryAdd(diamonds(3)));
        assertEquals(2, ruleCalls.get());
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
        assertTrue(inventory.isEmpty());
    }

    @Test
    void placementRuleExceptionsPropagateBeforeEventsOrWrites() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            throw new IllegalStateException("rule-boom");
        });
        inventory.subscribePreUpdate(event -> preCalls.incrementAndGet());
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertThrows(IllegalStateException.class, () -> inventory.tryAdd(reason(), diamonds(2)));
        assertThrows(IllegalStateException.class, () -> inventory.simulateTryAdd(diamonds(2)));
        assertEquals(0, preCalls.get());
        assertEquals(0, postCalls.get());
        assertTrue(inventory.isEmpty());
    }

    @Test
    void programOperationsSeeNoPlayerOrWindowInAccessContext() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicReference<Player> seenPlayer = new AtomicReference<>();
        AtomicReference<Window> seenWindow = new AtomicReference<>();
        AtomicReference<ItemStack> seenItem = new AtomicReference<>();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            seenPlayer.set(placement.player());
            seenWindow.set(placement.window());
            seenItem.set(placement.addedItem());
            return true;
        });
        AddResult result = inventory.tryAdd(reason(), diamonds(3));

        assertInstanceOf(TransactionResult.Committed.class, result.result());
        assertEquals(1, ruleCalls.get());
        assertNull(seenPlayer.get());
        assertNull(seenWindow.get());
        assertEquals(Material.DIAMOND, seenItem.get().getType());
        assertEquals(3, seenItem.get().getAmount());

        assertEquals(0, inventory.simulateTryAdd(diamonds(2)));
        assertEquals(2, ruleCalls.get());
        assertNull(seenPlayer.get());
        assertNull(seenWindow.get());
    }

    @Test
    void handlersDoNotChangeOutcomeWhenNotCancelling() {
        VirtualInventory silent = new VirtualInventory(3);
        VirtualInventory observed = new VirtualInventory(3);
        observed.subscribePreUpdate(event -> {
        });
        observed.subscribePostUpdate(event -> {
        });
        for (VirtualInventory inventory : new VirtualInventory[]{silent, observed}) {
            inventory.trySetItem(reason(), 0, diamonds(97));
            inventory.tryAdd(reason(), diamonds(5));
            inventory.tryCollect(reason(), diamonds(1), 2);
        }
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(ItemUtils.amountOf(silent.itemAt(slot)), ItemUtils.amountOf(observed.itemAt(slot)));
        }
    }

    @Test
    void cancelledBulkOperationsReportZeroProgress() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.trySetItem(reason(), 0, diamonds(5));
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        AddResult add = inventory.tryAdd(reason(), diamonds(3));

        assertSame(TransactionResult.Cancelled.INSTANCE, add.result());
        assertEquals(3, add.remaining());
        CollectResult collect = inventory.tryCollect(reason(), diamonds(1), 4);

        assertSame(TransactionResult.Cancelled.INSTANCE, collect.result());
        assertEquals(0, collect.collected());
        RemoveResult remove = inventory.tryRemove(reason(), stack -> true, 4);

        assertSame(TransactionResult.Cancelled.INSTANCE, remove.result());
        assertEquals(0, remove.removed());
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    void committedDeltasOnlyContainChangedSlots() {
        VirtualInventory inventory = new VirtualInventory(4);
        inventory.setMaxStackSizes(new int[]{10, 10, 10, 10});
        inventory.trySetItem(reason(), 1, diamonds(9));
        AddResult result = inventory.tryAdd(reason(), diamonds(3));
        TransactionResult.Committed committed = assertInstanceOf(TransactionResult.Committed.class, result.result());
        List<SlotChange> deltas = committed.rootChanges().getFirst().slotChanges();

        assertEquals(2, deltas.size());
        assertEquals(1, deltas.get(0).slot());
        assertEquals(10, ItemUtils.amountOf(deltas.get(0).after()));
        assertEquals(0, deltas.get(1).slot());
        assertNull(deltas.get(1).before());
        assertEquals(2, ItemUtils.amountOf(deltas.get(1).after()));
    }

    @Test
    void singleSlotMethodsRejectOutOfBoundsSlots() {
        VirtualInventory inventory = new VirtualInventory(2);

        assertThrows(IndexOutOfBoundsException.class, () -> inventory.trySetItem(reason(), 2, diamonds(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.trySetItem(reason(), -1, null));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.setItem(reason(), 2, diamonds(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.tryPutItem(reason(), 2, diamonds(1)));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.tryPutItem(reason(), 2, new ItemStack(Material.AIR)));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.tryModifyItem(reason(), 2, current -> current));
        assertThrows(IndexOutOfBoundsException.class, () -> inventory.tryChangeAmount(reason(), 2, 1));
    }

    @Test
    void changeAmountPreservesOverstackedPiles() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.trySetItem(reason(), 0, diamonds(97));

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, -1));
        assertEquals(96, amountAt(inventory, 0));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, inventory.tryChangeAmount(reason(), 0, 1));
        assertEquals(96, amountAt(inventory, 0));
        assertEquals(0, postCalls.get());
    }

    @Test
    void overstackedPilesAreNeverMergedAndCountAsFullStacks() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.trySetItem(reason(), 0, diamonds(97));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        AddResult add = inventory.tryAdd(reason(), diamonds(5));

        assertEquals(0, add.remaining());
        assertEquals(97, amountAt(inventory, 0));
        assertEquals(5, amountAt(inventory, 1));
        assertEquals(1, postCalls.get());
        AddResult put = inventory.tryPutItem(reason(), 0, diamonds(3));

        assertEquals(3, put.remaining());
        assertEquals(1, postCalls.get());
        CollectResult collect = inventory.tryCollect(reason(), diamonds(1), 7);

        assertEquals(7, collect.collected());
        assertNull(inventory.itemAt(1));
        assertEquals(95, amountAt(inventory, 0));
    }

    @Test
    void conflictedBulkOperationsReportZeroProgressAndFullRemaining() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.trySetItem(reason(), 0, diamonds(5));
        inventory.subscribePreUpdate(event -> inventory.setItem(reason(), 1, diamonds(1)));
        AddResult add = inventory.tryAdd(reason(), diamonds(3));

        assertSame(TransactionResult.Conflicted.INSTANCE, add.result());
        assertEquals(3, add.remaining());
        CollectResult collect = inventory.tryCollect(reason(), diamonds(1), 3);

        assertSame(TransactionResult.Conflicted.INSTANCE, collect.result());
        assertEquals(0, collect.collected());
        RemoveResult remove = inventory.tryRemove(reason(), stack -> true, 3);

        assertSame(TransactionResult.Conflicted.INSTANCE, remove.result());
        assertEquals(0, remove.removed());
        assertEquals(5, amountAt(inventory, 0));
    }

    @Test
    void zeroSizedInventoryIsInertButValid() {
        VirtualInventory inventory = new VirtualInventory(0);

        assertEquals(0, inventory.size());
        assertEquals(0, inventory.snapshot().length);
        AddResult add = inventory.tryAdd(reason(), diamonds(3));

        assertInstanceOf(TransactionResult.Committed.class, add.result());
        assertEquals(3, add.remaining());
        assertEquals(0, inventory.simulateCollect(diamonds(1), 5));
        assertEquals(0, SlotOrder.natural(0).size());
        assertEquals(0, SlotOrder.of().size());
    }

    @Test
    void collectHonorsConfiguredIterationOrder() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSizes(new int[]{10, 10, 10});
        inventory.setIterationOrder(OperationCategory.COLLECT, SlotOrder.of(2, 1, 0));
        inventory.trySetItem(reason(), 0, diamonds(4));
        inventory.trySetItem(reason(), 2, diamonds(4));
        CollectResult result = inventory.tryCollect(reason(), diamonds(1), 5);

        assertEquals(5, result.collected());
        assertNull(inventory.itemAt(2));
        assertEquals(3, amountAt(inventory, 0));
    }

    @Test
    void simulateCollectMatchesRealCollect() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setMaxStackSizes(new int[]{10, 10, 10});
        inventory.trySetItem(reason(), 0, diamonds(10));
        inventory.trySetItem(reason(), 1, diamonds(3));
        int simulated = inventory.simulateCollect(diamonds(1), 8);

        assertEquals(8, simulated);
        assertEquals(simulated, inventory.tryCollect(reason(), diamonds(1), 8).collected());
    }

    @Test
    void mayPickupTakesTheRequiredAmountFromTheItemItself() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{10, 10});
        inventory.trySetItem(reason(), 0, diamonds(4));
        inventory.trySetItem(reason(), 1, diamonds(2));

        assertTrue(inventory.mayPickup(diamonds(6)));
        assertFalse(inventory.mayPickup(diamonds(7)));
        assertFalse(inventory.mayPickup(new ItemStack(Material.EMERALD, 1)));
        assertTrue(inventory.mayPickup(new ItemStack(Material.AIR)));
    }

    @Test
    void multiItemMayPickupSharesOnePlannerSnapshot() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{10, 10});
        inventory.trySetItem(reason(), 0, diamonds(6));
        inventory.trySetItem(reason(), 1, new ItemStack(Material.EMERALD, 3));

        assertFalse(inventory.mayPickup(diamonds(6), diamonds(6)));
        assertTrue(inventory.mayPickup(diamonds(4), diamonds(2)));
        assertTrue(inventory.mayPickup(diamonds(6), new ItemStack(Material.EMERALD, 3)));
        assertFalse(inventory.mayPickup(diamonds(6), new ItemStack(Material.EMERALD, 4)));
        assertTrue(inventory.mayPickup());
    }

    @Test
    void mayPickupLeavesContentsAndEventsUntouched() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{10, 10});
        inventory.trySetItem(reason(), 0, diamonds(5));
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertTrue(inventory.mayPickup(diamonds(5)));
        assertFalse(inventory.mayPickup(diamonds(3), diamonds(3)));
        assertEquals(0, postCalls.get());
        assertEquals(5, amountAt(inventory, 0));
        assertEquals(5, inventory.tryCollect(reason(), diamonds(1), 5).collected());
    }

    @Test
    void authoritativeWritesAlwaysFireEventsEvenWhenIdentical() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        inventory.trySetItem(reason(), 0, null);

        assertEquals(1, postCalls.get());
        inventory.trySetItem(reason(), 0, diamonds(2));
        inventory.tryModifyItem(reason(), 0, current -> current);

        assertEquals(3, postCalls.get());
    }

    @Test
    void randomizedOperationsConserveQuantity() {
        Random random = new Random(20260726L);
        for (int round = 0; round < 120; round++) {
            int size = 1 + random.nextInt(9);
            VirtualInventory inventory = new VirtualInventory(size);
            int[] maxes = new int[size];
            for (int i = 0; i < size; i++) {
                maxes[i] = 1 + random.nextInt(SparrowInventory.DEFAULT_MAX_STACK_SIZE);
            }
            inventory.setMaxStackSizes(maxes);
            inventory.setIterationOrder(OperationCategory.ADD, randomOrder(random, size));
            inventory.setIterationOrder(OperationCategory.COLLECT, randomOrder(random, size));
            inventory.setIterationOrder(OperationCategory.OTHER, randomOrder(random, size));
            for (int slot = 0; slot < size; slot++) {
                int roll = random.nextInt(3);
                if (roll == 1) {
                    inventory.trySetItem(reason(), slot, diamonds(1 + random.nextInt(maxes[slot])));
                } else if (roll == 2) {
                    inventory.trySetItem(reason(), slot, new ItemStack(Material.EMERALD, 1 + random.nextInt(maxes[slot])));
                }
            }
            int before = totalDiamonds(inventory);
            int operation = random.nextInt(3);
            if (operation == 0) {
                int amount = 1 + random.nextInt(150);
                AddResult result = inventory.tryAdd(reason(), diamonds(amount));

                assertInstanceOf(TransactionResult.Committed.class, result.result());
                assertEquals(before + amount - result.remaining(), totalDiamonds(inventory), "add conservation at round " + round);
            } else if (operation == 1) {
                int upTo = 1 + random.nextInt(150);
                CollectResult result = inventory.tryCollect(reason(), diamonds(1), upTo);

                assertInstanceOf(TransactionResult.Committed.class, result.result());
                assertEquals(before - result.collected(), totalDiamonds(inventory), "collect conservation at round " + round);
                assertTrue(result.collected() <= upTo);
            } else {
                int upTo = 1 + random.nextInt(150);
                RemoveResult result = inventory.tryRemove(reason(), stack -> stack.getType() == Material.DIAMOND, upTo);

                assertInstanceOf(TransactionResult.Committed.class, result.result());
                assertEquals(before - result.removed(), totalDiamonds(inventory), "remove conservation at round " + round);
                assertTrue(result.removed() <= upTo);
            }
        }
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static List<Integer> ints(int[] values) {
        ArrayList<Integer> boxed = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            boxed.add(values[i]);
        }
        return boxed;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static int amountAt(VirtualInventory inventory, int slot) {
        return ItemUtils.amountOf(inventory.itemAt(slot));
    }

    private static int totalDiamonds(VirtualInventory inventory) {
        int total = 0;
        ItemStack[] snapshot = inventory.snapshot();
        for (int i = 0; i < snapshot.length; i++) {
            if (snapshot[i] != null && snapshot[i].getType() == Material.DIAMOND) {
                total += snapshot[i].getAmount();
            }
        }
        return total;
    }

    private static SlotOrder randomOrder(Random random, int size) {
        List<Integer> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = slots.get(i);
        }
        return SlotOrder.of(order);
    }
}
