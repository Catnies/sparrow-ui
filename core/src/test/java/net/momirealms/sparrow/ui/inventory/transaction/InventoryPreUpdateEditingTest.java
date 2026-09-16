package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.TransactionResult;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryPreUpdateEditingTest {

    private List<Throwable> reportedExceptions;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.reportedExceptions = new CopyOnWriteArrayList<>();
        SparrowUI.getInstance().setExceptionHandler((message, throwable) -> this.reportedExceptions.add(throwable));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void rootCoordinatesCanRewriteAndExpandAParticipatingRootWriteSet() {
        VirtualInventory inventory = new VirtualInventory(3);
        AtomicReference<InventoryPostUpdateEvent> receivedPost = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> {
            event.setAfter(inventory, 0, diamonds(4));
            event.setAfter(inventory, 2, diamonds(16));
        });
        inventory.subscribePostUpdate(receivedPost::set);
        TransactionResult result = inventory.trySetItem(reason(), 0, new ItemStack(Material.DIRT));

        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(16, ItemUtils.amountOf(inventory.itemAt(2)));
        List<InventoryChange> committed = assertInstanceOf(TransactionResult.Committed.class, result).rootChanges();

        assertEquals(List.of(0, 2), slotsOf(committed.getFirst().slotChanges()));
        assertEquals(Material.DIAMOND, committed.getFirst().slotChanges().get(0).after().getType());
        InventoryPostUpdateEvent post = receivedPost.get();

        assertEquals(List.of(0, 2), slotsOf(post.slotChanges()));
        assertEquals(16, ItemUtils.amountOf(post.changeAt(2).after()));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void editsRefreshCurrentEventQueriesAndBecomeVisibleToLaterHandlers() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicReference<List<Integer>> laterHandlerSlots = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> {
            assertEquals(Material.DIRT, event.netAddedItems().getFirst().getType());
            ItemStack rewritten = diamonds(16);
            event.setAfter(inventory, 0, rewritten);
            event.setAfter(inventory, 1, diamonds(8));
            rewritten.setAmount(1);

            assertEquals(16, ItemUtils.amountOf(event.changeAt(0).after()));
            assertEquals(List.of(0, 1), slotsOf(event.slotChanges()));
            assertEquals(Material.DIAMOND, event.netAddedItems().getFirst().getType());
        });
        inventory.subscribePreUpdate(event -> {
            laterHandlerSlots.set(slotsOf(event.rootChanges().getFirst().slotChanges()));

            assertEquals(16, ItemUtils.amountOf(event.changeAt(0).after()));
            assertEquals(8, ItemUtils.amountOf(event.changeAt(1).after()));
        });
        TransactionResult result = inventory.trySetItem(reason(), 0, new ItemStack(Material.DIRT));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(List.of(0, 1), laterHandlerSlots.get());
        assertEquals(16, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(8, ItemUtils.amountOf(inventory.itemAt(1)));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void preEditsDoNotRecursivelyNotifyNewlyIncludedInventoriesButPostStillFires() {
        VirtualInventory first = new VirtualInventory(1);
        VirtualInventory second = new VirtualInventory(1);
        AtomicInteger secondPreCalls = new AtomicInteger();
        AtomicReference<InventoryPostUpdateEvent> secondPost = new AtomicReference<>();
        first.subscribePreUpdate(event -> {
            assertTrue(event.include(second));
            event.setAfter(second, 0, diamonds(16));
        });
        second.subscribePreUpdate(event -> secondPreCalls.incrementAndGet());
        second.subscribePostUpdate(secondPost::set);
        TransactionResult result = first.trySetItem(reason(), 0, new ItemStack(Material.DIRT));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(0, secondPreCalls.get());
        assertEquals(16, ItemUtils.amountOf(second.itemAt(0)));
        InventoryPostUpdateEvent post = secondPost.get();

        assertEquals(List.of(0), slotsOf(post.slotChanges()));
        assertEquals(List.of(0), slotsOf(post.rootChanges().getLast().slotChanges()));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void failedPreHandlerDiscardsAllOfItsCandidateEdits() {
        VirtualInventory inventory = new VirtualInventory(2);
        AtomicReference<List<Integer>> laterHandlerSlots = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> {
            event.setAfter(inventory, 1, diamonds(16));
            throw new IllegalStateException("pre-edit-boom");
        });
        inventory.subscribePreUpdate(event -> laterHandlerSlots.set(slotsOf(event.rootChanges().getFirst().slotChanges())));
        TransactionResult result = inventory.trySetItem(reason(), 0, diamonds(1));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(List.of(0), laterHandlerSlots.get());
        assertNull(inventory.itemAt(1));
        assertEquals(1, this.reportedExceptions.size());
    }

    @Test
    void failedPreHandlerDiscardsItsCancellation() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicReference<Boolean> laterHandlerSawCancelled = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> {
            event.setCancelled(true);
            throw new IllegalStateException("pre-cancel-boom");
        });
        inventory.subscribePreUpdate(event -> laterHandlerSawCancelled.set(event.cancelled()));
        TransactionResult result = inventory.trySetItem(reason(), 0, diamonds(1));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(Boolean.FALSE, laterHandlerSawCancelled.get());
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, this.reportedExceptions.size());
    }

    @Test
    void failedPreHandlerCannotClearAnEarlierCancellation() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicReference<Boolean> laterHandlerSawCancelled = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        inventory.subscribePreUpdate(event -> {
            event.setCancelled(false);
            throw new IllegalStateException("pre-uncancel-boom");
        });
        inventory.subscribePreUpdate(event -> laterHandlerSawCancelled.set(event.cancelled()));
        TransactionResult result = inventory.trySetItem(reason(), 0, diamonds(1));

        assertSame(TransactionResult.Cancelled.INSTANCE, result);
        assertEquals(Boolean.TRUE, laterHandlerSawCancelled.get());
        assertNull(inventory.itemAt(0));
        assertEquals(1, this.reportedExceptions.size());
    }

    @Test
    void laterHandlerCanClearCancellation() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        inventory.subscribePreUpdate(event -> {
            assertTrue(event.cancelled());
            event.setCancelled(false);
        });
        TransactionResult result = inventory.trySetItem(reason(), 0, diamonds(1));

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void preSetAfterMayWriteAnItemRejectedByAccessRule() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        inventory.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return placement.addedItem().getType() == Material.DIAMOND;
        });
        inventory.subscribePreUpdate(event -> event.setAfter(0, new ItemStack(Material.EMERALD, 2)));
        TransactionResult result = inventory.tryAdd(reason(), diamonds(1)).result();

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(Material.EMERALD, inventory.itemAt(0).getType());
        assertEquals(2, inventory.itemAt(0).getAmount());
        assertEquals(1, ruleCalls.get());
    }

    @Test
    void preEventCannotEditAfterItsHandlerReturns() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicReference<InventoryPreUpdateEvent> saved = new AtomicReference<>();
        inventory.subscribePreUpdate(saved::set);

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(reason(), 0, diamonds(1)));
        InventoryPreUpdateEvent event = saved.get();

        assertSame(inventory, event.rootChanges().getFirst().inventory());
        assertThrows(IllegalStateException.class, () -> event.setAfter(inventory, 0, diamonds(2)));
        assertThrows(IllegalStateException.class, () -> event.setAfter(0, diamonds(2)));
        assertEquals(1, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void includedRootJoinsTheSameTransactionWithoutTakingPartInThisPreRound() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicInteger vaultPreCalls = new AtomicInteger();
        AtomicReference<InventoryPostUpdateEvent> vaultPost = new AtomicReference<>();
        dropbox.subscribePreUpdate(event -> {
            assertTrue(event.include(vault));
            event.setAfter(vault, 0, diamonds(4));
        });
        vault.subscribePreUpdate(event -> vaultPreCalls.incrementAndGet());
        vault.subscribePostUpdate(vaultPost::set);
        TransactionResult result = dropbox.trySetItem(reason(), 0, new ItemStack(Material.DIRT, 4));
        List<InventoryChange> committed = assertInstanceOf(TransactionResult.Committed.class, result).rootChanges();

        assertEquals(2, committed.size());
        assertSame(dropbox, committed.get(0).inventory());
        assertSame(vault, committed.get(1).inventory());
        assertEquals(Material.DIRT, dropbox.itemAt(0).getType());
        assertEquals(4, ItemUtils.amountOf(vault.itemAt(0)));
        assertEquals(0, vaultPreCalls.get());
        assertEquals(List.of(0), slotsOf(vaultPost.get().slotChanges()));
        assertNull(vaultPost.get().changeAt(0).before());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void cancellingAfterAnIncludeLeavesTheNewRootUntouchedToo() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicInteger vaultPostCalls = new AtomicInteger();
        dropbox.subscribePreUpdate(event -> {
            event.include(vault);
            event.setAfter(vault, 0, diamonds(4));
            event.setCancelled(true);
        });
        vault.subscribePostUpdate(event -> vaultPostCalls.incrementAndGet());
        TransactionResult result = dropbox.trySetItem(reason(), 0, new ItemStack(Material.DIRT, 4));

        assertSame(TransactionResult.Cancelled.INSTANCE, result);
        assertNull(dropbox.itemAt(0));
        assertNull(vault.itemAt(0));
        assertEquals(0, vaultPostCalls.get());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void includingWithoutWritingAnySlotIsTheSameAsNotIncludingAtAll() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicInteger vaultPostCalls = new AtomicInteger();
        dropbox.subscribePreUpdate(event -> assertTrue(event.include(vault)));
        vault.subscribePostUpdate(event -> vaultPostCalls.incrementAndGet());
        TransactionResult result = dropbox.trySetItem(reason(), 0, diamonds(1));
        List<InventoryChange> committed = assertInstanceOf(TransactionResult.Committed.class, result).rootChanges();

        assertEquals(1, committed.size());
        assertSame(dropbox, committed.getFirst().inventory());
        assertEquals(0, vaultPostCalls.get());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void includeIsARequiredAndDeliberateStepBeforeWritingANewRoot() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        AtomicReference<Boolean> secondInclude = new AtomicReference<>();
        dropbox.subscribePreUpdate(event -> {
            rejected.set(assertThrows(IllegalArgumentException.class, () -> event.setAfter(vault, 0, diamonds(4))));

            assertTrue(event.include(vault));
            secondInclude.set(event.include(vault));

            assertEquals(Boolean.FALSE, event.include(dropbox));
            event.setAfter(vault, 0, diamonds(4));
        });

        assertInstanceOf(TransactionResult.Committed.class, dropbox.trySetItem(reason(), 0, diamonds(1)));
        assertInstanceOf(IllegalArgumentException.class, rejected.get());
        assertEquals(Boolean.FALSE, secondInclude.get());
        assertEquals(4, ItemUtils.amountOf(vault.itemAt(0)));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void includedRootAcceptsItemsItsOwnAccessRuleWouldReject() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicInteger ruleCalls = new AtomicInteger();
        vault.setAccessRule(placement -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        dropbox.subscribePreUpdate(event -> {
            event.include(vault);
            event.setAfter(vault, 0, diamonds(4));
        });

        assertInstanceOf(TransactionResult.Committed.class, dropbox.trySetItem(reason(), 0, diamonds(1)));
        assertEquals(4, ItemUtils.amountOf(vault.itemAt(0)));
        assertEquals(0, ruleCalls.get());
    }

    @Test
    void failedHandlerDiscardsItsIncludeAlongWithItsEdits() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        AtomicReference<List<InventoryChange>> laterHandlerRoots = new AtomicReference<>();
        dropbox.subscribePreUpdate(event -> {
            event.include(vault);
            event.setAfter(vault, 0, diamonds(4));
            throw new IllegalStateException("pre-include-boom");
        });
        dropbox.subscribePreUpdate(event -> laterHandlerRoots.set(event.rootChanges()));

        assertInstanceOf(TransactionResult.Committed.class, dropbox.trySetItem(reason(), 0, diamonds(1)));
        assertEquals(1, laterHandlerRoots.get().size());
        assertNull(vault.itemAt(0));
        assertEquals(1, this.reportedExceptions.size());
    }

    @Test
    void lockOrderIsRecomputedFromTheFinalWriteSetSoAnEarlierRootCanBeAddedLast() {
        VirtualInventory vault = new VirtualInventory(1);
        VirtualInventory dropbox = new VirtualInventory(1);

        assertTrue(vault.openPlan().stateLock().order() < dropbox.openPlan().stateLock().order());
        dropbox.subscribePreUpdate(event -> {
            event.include(vault);
            event.setAfter(vault, 0, diamonds(4));
        });

        assertInstanceOf(TransactionResult.Committed.class, dropbox.trySetItem(reason(), 0, diamonds(1)));
        assertEquals(1, ItemUtils.amountOf(dropbox.itemAt(0)));
        assertEquals(4, ItemUtils.amountOf(vault.itemAt(0)));
    }

    @Test
    void concurrentWriteToAnIncludedRootConflictsTheWholeTransaction() {
        VirtualInventory dropbox = new VirtualInventory(1);
        VirtualInventory vault = new VirtualInventory(1);
        dropbox.subscribePreUpdate(event -> {
            event.include(vault);
            event.setAfter(vault, 0, diamonds(4));
        });
        PlannedRoot basis = dropbox.openPlan();
        TransactionResult result = InventoryTransactions.commit(
                reason(),
                new TransactionDraft(List.of(new TransactionScope(basis, List.of(new SlotChange(0, basis.planned()[0], diamonds(1)))))),
                null,
                false,
                null,
                List.of(),
                () -> {
                    vault.setItem(reason(), 0, new ItemStack(Material.EMERALD));
                    return true;
                }
        );

        assertSame(TransactionResult.Conflicted.INSTANCE, result);
        assertNull(dropbox.itemAt(0));
        assertEquals(Material.EMERALD, vault.itemAt(0).getType());
    }

    @Test
    void afterReadsUntouchedBaselineAndEditsAsIndependentCopies() {
        VirtualInventory inventory = new VirtualInventory(3);
        inventory.setItem(1, diamonds(8));
        inventory.subscribePreUpdate(event -> {
            assertEquals(diamonds(2), event.after(0));
            assertEquals(diamonds(8), event.after(inventory, 1));
            assertNull(event.after(2));

            event.after(0).setAmount(40);
            event.after(1).setAmount(40);
            assertEquals(diamonds(2), event.after(0));
            assertEquals(diamonds(8), event.after(1));

            event.setAfter(0, diamonds(3));
            event.setAfter(1, null);
            assertEquals(diamonds(3), event.after(0));
            assertNull(event.after(1));
        });
        inventory.subscribePreUpdate(event -> {
            assertEquals(diamonds(3), event.after(0));
            assertNull(event.after(1));
            assertNull(event.after(2));
        });

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(0, diamonds(2)));
        assertEquals(diamonds(3), inventory.itemAt(0));
        assertNull(inventory.itemAt(1));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void afterRequiresExplicitParticipationAndDoesNotAddWrites() {
        VirtualInventory input = new VirtualInventory(1);
        VirtualInventory included = new VirtualInventory(2);
        included.setItem(1, diamonds(4));
        AtomicInteger includedPre = new AtomicInteger();
        AtomicInteger includedPost = new AtomicInteger();
        included.subscribePreUpdate(event -> includedPre.incrementAndGet());
        included.subscribePostUpdate(event -> includedPost.incrementAndGet());
        input.subscribePreUpdate(event -> {
            assertThrows(IllegalArgumentException.class, () -> event.after(included, 0));
            assertEquals(1, event.rootChanges().size());
            assertThrows(IndexOutOfBoundsException.class, () -> event.after(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> event.after(1));

            assertTrue(event.include(included));
            assertNull(event.after(included, 0));
            assertEquals(diamonds(4), event.after(included, 1));
            event.after(included, 1).setAmount(60);
            assertEquals(diamonds(4), event.after(included, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> event.after(included, 2));
        });

        TransactionResult.Committed result = assertInstanceOf(TransactionResult.Committed.class, input.trySetItem(0, diamonds(1)));

        assertEquals(1, result.rootChanges().size());
        assertEquals(diamonds(4), included.itemAt(1));
        assertEquals(0, includedPre.get());
        assertEquals(0, includedPost.get());
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void afterRetainsIncludedBaselineWhenLiveInventoryChanges() {
        VirtualInventory input = new VirtualInventory(1);
        VirtualInventory included = new VirtualInventory(2);
        included.setItem(1, diamonds(4));
        input.subscribePreUpdate(event -> {
            event.include(included);
            event.setAfter(included, 0, diamonds(1));
            included.setItem(1, diamonds(9));

            assertEquals(diamonds(4), event.after(included, 1));
            assertEquals(diamonds(1), event.after(included, 0));
        });

        assertSame(TransactionResult.Conflicted.INSTANCE, input.trySetItem(0, diamonds(2)));
        assertNull(input.itemAt(0));
        assertNull(included.itemAt(0));
        assertEquals(diamonds(9), included.itemAt(1));
        assertEquals(0, this.reportedExceptions.size());
    }

    @Test
    void afterRetainsItsEventSnapshotAfterLaterHandlersAndLiveWrites() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicReference<InventoryPreUpdateEvent> saved = new AtomicReference<>();
        inventory.subscribePreUpdate(event -> {
            event.setAfter(0, diamonds(3));
            saved.set(event);
        });
        inventory.subscribePreUpdate(event -> {
            assertEquals(diamonds(3), event.after(0));
            event.setAfter(0, diamonds(5));
        });

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(0, diamonds(2)));
        assertEquals(diamonds(5), inventory.itemAt(0));
        inventory.setItem(0, diamonds(9));

        InventoryPreUpdateEvent event = saved.get();
        assertEquals(diamonds(3), event.after(0));
        event.after(0).setAmount(20);
        assertEquals(diamonds(3), event.after(0));
        assertThrows(IllegalStateException.class, () -> event.setAfter(0, diamonds(6)));
        assertEquals(diamonds(9), inventory.itemAt(0));
        assertEquals(0, this.reportedExceptions.size());
    }

    private static UpdateReason reason() {
        return UpdateReason.Program.INSTANCE;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static List<Integer> slotsOf(List<SlotChange> changes) {
        List<Integer> slots = new ArrayList<>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            slots.add(changes.get(i).slot());
        }
        return List.copyOf(slots);
    }
}
