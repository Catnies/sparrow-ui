package net.momirealms.sparrow.ui.inventory.transaction;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.TestInventory;
import net.momirealms.sparrow.ui.inventory.TransactionResult;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryChange;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTransactionsTest {

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
    void cancelledTransactionLeavesStateUntouchedAndSkipsPost() {
        TestInventory inventory = new TestInventory(1);
        ItemStack[] stateBefore = inventory.unsafeSnapshot();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> event.setCancelled(true));
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        TransactionResult result = set(inventory, 0, diamonds(3));

        assertSame(TransactionResult.Cancelled.INSTANCE, result);
        assertSame(stateBefore, inventory.unsafeSnapshot());
        assertNull(inventory.itemAt(0));
        assertEquals(0, postCalls.get());
    }

    @Test
    void laterInventoryCanClearCancellation() {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        first.subscribePreUpdate(event -> event.setCancelled(true));
        second.subscribePreUpdate(event -> {
            assertTrue(event.cancelled());
            event.setCancelled(false);
        });
        PlannedRoot firstPlanned = first.openPlan();
        PlannedRoot secondPlanned = second.openPlan();
        TransactionResult result = InventoryTransactions.commit(UpdateReason.Program.INSTANCE, List.of(
                scope(firstPlanned, new SlotChange(0, firstPlanned.planned()[0], diamonds(3))),
                scope(secondPlanned, new SlotChange(0, secondPlanned.planned()[0], diamonds(4)))
        ), false);

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(3, ItemUtils.amountOf(first.itemAt(0)));
        assertEquals(4, ItemUtils.amountOf(second.itemAt(0)));
    }

    @Test
    void conflictedCommitLeavesStateUntouched() {
        TestInventory inventory = new TestInventory(1);
        PlannedRoot stalePlanned = inventory.openPlan();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(5)));
        ItemStack[] stateAfterFirst = inventory.unsafeSnapshot();
        SlotChange delta = new SlotChange(0, stalePlanned.planned()[0], diamonds(9));
        TransactionResult result = commit(scope(stalePlanned, delta));

        assertSame(TransactionResult.Conflicted.INSTANCE, result);
        assertSame(stateAfterFirst, inventory.unsafeSnapshot());
        assertEquals(5, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, postCalls.get());
    }

    @Test
    @Timeout(10)
    void concurrentSharedSlotIncrementsDoNotLoseUpdates() throws InterruptedException {
        TestInventory inventory = new TestInventory(1);
        int threads = 4;
        int perThread = 20;
        runConcurrently(threads, worker -> () -> {
            for (int i = 0; i < perThread; i++) {
                incrementUntilCommitted(inventory, 0);
            }
        });

        assertEquals(threads * perThread, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    @Timeout(10)
    void concurrentIndependentSlotIncrementsRetryThroughSnapshotConflicts() throws InterruptedException {
        int threads = 4;
        int perThread = 20;
        TestInventory inventory = new TestInventory(threads);
        runConcurrently(threads, worker -> () -> {
            for (int i = 0; i < perThread; i++) {
                incrementUntilCommitted(inventory, worker);
            }
        });
        for (int slot = 0; slot < threads; slot++) {
            assertEquals(perThread, ItemUtils.amountOf(inventory.itemAt(slot)));
        }
    }

    @Test
    void versionSourceAdvancesThroughSameMillisecondAndClockRollback() {
        AtomicLong clock = new AtomicLong(1_000L);
        VersionSource source = new VersionSource(clock::get);

        assertEquals(1_000L, source.next());
        assertEquals(1_001L, source.next());
        clock.set(900L);

        assertEquals(1_002L, source.next());
        clock.set(2_000L);

        assertEquals(2_000L, source.next());
    }

    @Test
    void sequentialPostEventsCarryIncreasingTransactionVersions() {
        TestInventory inventory = new TestInventory(1);
        List<Long> observedVersions = new ArrayList<>();
        inventory.subscribePostUpdate(event -> observedVersions.add(event.version()));
        for (int amount = 1; amount <= 3; amount++) {
            assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(amount)));
        }

        assertEquals(3, observedVersions.size());
        assertTrue(observedVersions.get(0) > 0L);
        assertTrue(observedVersions.get(0) < observedVersions.get(1));
        assertTrue(observedVersions.get(1) < observedVersions.get(2));
    }

    @Test
    void explicitEquivalentWriteStillReceivesANewVersionAndPost() {
        TestInventory inventory = new TestInventory(1);
        List<Long> observedVersions = new ArrayList<>();
        inventory.subscribePostUpdate(event -> observedVersions.add(event.version()));

        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(2)));
        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(2)));
        assertEquals(2, observedVersions.size());
        assertTrue(observedVersions.get(0) < observedVersions.get(1));
    }

    @Test
    void crossRootPostEventsShareOneTransactionVersion() {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        AtomicLong firstVersion = new AtomicLong();
        AtomicLong secondVersion = new AtomicLong();
        first.subscribePostUpdate(event -> firstVersion.set(event.version()));
        second.subscribePostUpdate(event -> secondVersion.set(event.version()));
        PlannedRoot firstPlanned = first.openPlan();
        PlannedRoot secondPlanned = second.openPlan();

        assertInstanceOf(TransactionResult.Committed.class, InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        scope(firstPlanned, new SlotChange(0, firstPlanned.planned()[0], diamonds(2))),
                        scope(secondPlanned, new SlotChange(0, secondPlanned.planned()[0], diamonds(3)))
                ),
                false
        ));

        assertTrue(firstVersion.get() > 0L);
        assertEquals(firstVersion.get(), secondVersion.get());
    }

    @Test
    @Timeout(10)
    void concurrentCommitsReceiveDistinctVersionsThatOrderStateTransitions() throws InterruptedException {
        TestInventory inventory = new TestInventory(1);
        List<InventoryPostUpdateEvent> observedEvents = Collections.synchronizedList(new ArrayList<>());
        inventory.subscribePostUpdate(observedEvents::add);
        int threads = 4;
        int perThread = 20;
        runConcurrently(threads, worker -> () -> {
            for (int i = 0; i < perThread; i++) {
                incrementUntilCommitted(inventory, 0);
            }
        });
        int commits = threads * perThread;

        assertEquals(commits, observedEvents.size());
        List<InventoryPostUpdateEvent> sorted = new ArrayList<>(observedEvents);
        sorted.sort(Comparator.comparingLong(InventoryPostUpdateEvent::version));
        for (int i = 0; i < commits; i++) {
            assertEquals(i + 1, amountOfSingleDelta(sorted.get(i)));
            if (i > 0) {
                assertTrue(sorted.get(i - 1).version() < sorted.get(i).version());
            }
        }
    }

    @Test
    @Timeout(10)
    void serialPostDispatchBlocksTheLaterCommitterAndKeepsCommitOrder() throws InterruptedException {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.serialPostDispatch(true);
        List<Integer> observedAmounts = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEventEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        AtomicBoolean secondCommitReturned = new AtomicBoolean();
        inventory.subscribePostUpdate(event -> {
            if (amountOfSingleDelta(event) == 1) {
                firstEventEntered.countDown();
                awaitQuietly(releaseFirstEvent);
            }
            observedAmounts.add(amountOfSingleDelta(event));
        });
        Thread firstCommitter = new Thread(() -> setSlot(inventory, 0, diamonds(1)), "serial-first");
        firstCommitter.start();
        firstEventEntered.await();
        Thread secondCommitter = new Thread(() -> {
            setSlot(inventory, 1, diamonds(2));
            secondCommitReturned.set(true);
        }, "serial-second");
        secondCommitter.start();
        try {
            secondCommitter.join(300);

            assertFalse(secondCommitReturned.get());
            assertEquals(List.of(), List.copyOf(observedAmounts));
        } finally {
            releaseFirstEvent.countDown();
            firstCommitter.join();
            secondCommitter.join();
        }

        assertEquals(List.of(1, 2), List.copyOf(observedAmounts));
    }

    @Test
    @Timeout(10)
    void blockedPostDoesNotPreventAnotherCommitterFromRunningItsOwnPost() throws InterruptedException {
        TestInventory inventory = new TestInventory(2);
        List<Integer> observedAmounts = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEventEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        CountDownLatch secondEventEntered = new CountDownLatch(1);
        AtomicReference<Thread> firstEventThread = new AtomicReference<>();
        AtomicReference<Thread> secondEventThread = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        inventory.subscribePostUpdate(event -> {
            int amount = amountOfSingleDelta(event);
            if (amount == 1) {
                firstEventThread.set(Thread.currentThread());
                firstEventEntered.countDown();
                awaitQuietly(releaseFirstEvent);
            } else {
                secondEventThread.set(Thread.currentThread());
                secondEventEntered.countDown();
            }
            observedAmounts.add(amount);
        });
        Thread firstCommitter = new Thread(() -> commitAndCapture(inventory, 0, diamonds(1), firstFailure), "first-committer");
        firstCommitter.start();
        firstEventEntered.await();
        Thread secondCommitter = new Thread(() -> commitAndCapture(inventory, 1, diamonds(2), secondFailure), "second-committer");
        secondCommitter.start();
        try {
            assertTrue(secondEventEntered.await(5, TimeUnit.SECONDS));
            secondCommitter.join();

            assertTrue(firstCommitter.isAlive());
            assertEquals(List.of(2), List.copyOf(observedAmounts));
        } finally {
            releaseFirstEvent.countDown();
            firstCommitter.join();
            secondCommitter.join();
        }

        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertSame(firstCommitter, firstEventThread.get());
        assertSame(secondCommitter, secondEventThread.get());
        assertEquals(List.of(2, 1), List.copyOf(observedAmounts));
    }

    @Test
    @Timeout(10)
    void crossRootPostWaitsForEveryLandButDoesNotBlockALaterPost() throws InterruptedException {
        CountDownLatch crossRootLandEntered = new CountDownLatch(1);
        CountDownLatch releaseCrossRootLand = new CountDownLatch(1);
        ReferencingInventory first = ReferencingInventory.of(new BlockingWriteStorage(crossRootLandEntered, releaseCrossRootLand));
        TestInventory second = new TestInventory(1);
        List<InventoryPostUpdateEvent> observedEvents = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> crossRootFailure = new AtomicReference<>();
        second.subscribePostUpdate(observedEvents::add);
        PlannedRoot plannedFirst = first.openPlanForWrite();
        PlannedRoot plannedSecond = second.openPlan();
        Thread crossRootCommitter = new Thread(() -> {
            try {
                assertInstanceOf(TransactionResult.Committed.class, InventoryTransactions.commit(
                        UpdateReason.Program.INSTANCE,
                        List.of(
                                new TransactionScope(plannedFirst, List.of(new SlotChange(0, plannedFirst.planned()[0], diamonds(1)))),
                                scope(plannedSecond, new SlotChange(0, plannedSecond.planned()[0], diamonds(1)))
                        ),
                        false
                ));
            } catch (Throwable throwable) {
                crossRootFailure.set(throwable);
            }
        }, "cross-root-committer");
        crossRootCommitter.start();
        crossRootLandEntered.await();
        try {
            assertInstanceOf(TransactionResult.Committed.class, set(second, 0, diamonds(2)));
            assertEquals(1, observedEvents.size());
            assertEquals(1, observedEvents.getFirst().rootChanges().size());
        } finally {
            releaseCrossRootLand.countDown();
            crossRootCommitter.join();
        }
        if (crossRootFailure.get() != null) {
            throw new AssertionError("cross-root commit failed", crossRootFailure.get());
        }

        assertEquals(2, observedEvents.size());
        assertEquals(2, observedEvents.getLast().rootChanges().size());
        assertTrue(observedEvents.getLast().version() < observedEvents.getFirst().version());
    }

    @Test
    void postHandlerExceptionsAreIsolatedFromOtherHandlersAndLaterTransactions() {
        TestInventory inventory = new TestInventory(1);
        AtomicInteger survivorCalls = new AtomicInteger();
        inventory.subscribePostUpdate(event -> {
            throw new IllegalStateException("post-boom");
        });
        inventory.subscribePostUpdate(event -> survivorCalls.incrementAndGet());

        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(1)));
        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(2)));
        assertEquals(2, survivorCalls.get());
        assertEquals(2, this.reportedExceptions.size());
        assertEquals(2, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    void nestedPostWaitsForTheEntireOuterTransactionBatch() {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        AtomicBoolean handlingFirstSubscriber = new AtomicBoolean();
        List<String> order = new ArrayList<>();
        first.subscribePostUpdate(event -> {
            int amount = amountOfSingleDelta(event);

            assertTrue(handlingFirstSubscriber.compareAndSet(false, true));
            try {
                order.add("first-a-" + amount);
                if (amount == 1) {
                    assertInstanceOf(TransactionResult.Committed.class, set(first, 0, diamonds(2)));
                    order.add("nested-return");
                }
            } finally {
                handlingFirstSubscriber.set(false);
            }
        });
        first.subscribePostUpdate(event -> order.add("first-b-" + amountOfSingleDelta(event)));
        second.subscribePostUpdate(event -> order.add("second-" + amountOfSingleDelta(event)));
        PlannedRoot firstPlanned = first.openPlan();
        PlannedRoot secondPlanned = second.openPlan();

        assertInstanceOf(TransactionResult.Committed.class, InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        scope(firstPlanned, new SlotChange(0, firstPlanned.planned()[0], diamonds(1))),
                        scope(secondPlanned, new SlotChange(0, secondPlanned.planned()[0], diamonds(1)))
                ),
                false
        ));

        assertEquals(List.of(
                "first-a-1",
                "nested-return",
                "first-b-1",
                "second-1",
                "first-a-2",
                "first-b-2"
        ), order);
    }

    @Test
    void preHandlerExceptionsAreReportedWithoutCancellingTheTransaction() {
        TestInventory inventory = new TestInventory(1);
        inventory.subscribePreUpdate(event -> {
            throw new IllegalStateException("pre-boom");
        });

        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(4)));
        assertEquals(4, ItemUtils.amountOf(inventory.itemAt(0)));
        assertEquals(1, this.reportedExceptions.size());
    }

    @Test
    void conflictedCrossInventoryCommitLeavesEveryInventoryUntouched() {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        PlannedRoot plannedFirst = first.openPlan();
        PlannedRoot stalePlannedSecond = second.openPlan();

        assertInstanceOf(TransactionResult.Committed.class, set(second, 0, diamonds(5)));
        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        scope(plannedFirst, new SlotChange(0, plannedFirst.planned()[0], diamonds(1))),
                        scope(stalePlannedSecond, new SlotChange(0, stalePlannedSecond.planned()[0], diamonds(2)))
                ),
                false
        );

        assertSame(TransactionResult.Conflicted.INSTANCE, result);
        assertSame(plannedFirst.planned(), first.unsafeSnapshot());
        assertNull(first.itemAt(0));
        assertEquals(5, ItemUtils.amountOf(second.itemAt(0)));
    }

    @Test
    @Timeout(10)
    void oppositeScopeOrderTransactionsNeverDeadlock() throws InterruptedException {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        int perThread = 20;
        runConcurrently(2, worker -> () -> {
            for (int i = 0; i < perThread; i++) {
                while (true) {
                    PlannedRoot plannedFirst = first.openPlan();
                    PlannedRoot plannedSecond = second.openPlan();
                    ItemStack[] firstContents = plannedFirst.planned();
                    ItemStack[] secondContents = plannedSecond.planned();
                    TransactionScope firstScope = scope(
                            plannedFirst, new SlotChange(0, firstContents[0], diamonds(ItemUtils.amountOf(firstContents[0]) + 1))
                    );
                    TransactionScope secondScope = scope(
                            plannedSecond, new SlotChange(0, secondContents[0], diamonds(ItemUtils.amountOf(secondContents[0]) + 1))
                    );
                    List<TransactionScope> scopes = worker == 0
                            ? List.of(firstScope, secondScope)
                            : List.of(secondScope, firstScope);
                    if (InventoryTransactions.commit(UpdateReason.Program.INSTANCE, scopes, false)
                            instanceof TransactionResult.Committed) {
                        break;
                    }
                }
            }
        });

        assertEquals(2 * perThread, ItemUtils.amountOf(first.itemAt(0)));
        assertEquals(2 * perThread, ItemUtils.amountOf(second.itemAt(0)));
    }

    @Test
    void bypassPreSkipsHandlersButStillFiresPost() {
        TestInventory inventory = new TestInventory(1);
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        inventory.subscribePostUpdate(event -> postCalls.incrementAndGet());
        PlannedRoot basis = inventory.openPlan();
        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(scope(basis, new SlotChange(0, basis.planned()[0], diamonds(6)))),
                true
        );

        assertInstanceOf(TransactionResult.Committed.class, result);
        assertEquals(0, preCalls.get());
        assertEquals(1, postCalls.get());
        assertEquals(6, ItemUtils.amountOf(inventory.itemAt(0)));
    }

    @Test
    @Timeout(10)
    void snapshotsNeverObserveTornMultiSlotWrites() throws InterruptedException {
        int size = 4;
        int rounds = 200;
        TestInventory inventory = new TestInventory(filled(size, 1));
        AtomicReference<AssertionError> readerFailure = new AtomicReference<>();
        CountDownLatch writerDone = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                for (int round = 0; round < rounds; round++) {
                    int amount = (round % 2 == 0) ? 2 : 1;
                    PlannedRoot basis = inventory.openPlan();
                    ItemStack[] planned = basis.planned();
                    List<SlotChange> deltas = new ArrayList<>(size);
                    for (int slot = 0; slot < size; slot++) {
                        deltas.add(new SlotChange(slot, planned[slot], diamonds(amount)));
                    }

                    assertInstanceOf(TransactionResult.Committed.class, commit(
                            new TransactionScope(basis, deltas)
                    ));
                }
            } finally {
                writerDone.countDown();
            }
        }, "torn-write-writer");
        Thread reader = new Thread(() -> {
            while (writerDone.getCount() > 0 && readerFailure.get() == null) {
                ItemStack[] snapshot = inventory.snapshot();
                int expected = ItemUtils.amountOf(snapshot[0]);
                for (int slot = 1; slot < snapshot.length; slot++) {
                    if (ItemUtils.amountOf(snapshot[slot]) != expected) {
                        readerFailure.set(new AssertionError("torn snapshot: " + describeAmounts(snapshot)));
                        return;
                    }
                }
            }
        }, "torn-write-reader");
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        if (readerFailure.get() != null) {
            throw readerFailure.get();
        }
    }

    @Test
    void changePayloadFollowsScopeDeclarationOrderNotLockOrder() {
        TestInventory first = new TestInventory(1);
        TestInventory second = new TestInventory(1);
        AtomicReference<List<InventoryChange>> observedChanges = new AtomicReference<>();
        second.subscribePostUpdate(event -> observedChanges.set(event.rootChanges()));
        PlannedRoot plannedFirst = first.openPlan();
        PlannedRoot plannedSecond = second.openPlan();
        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        scope(plannedSecond, new SlotChange(0, plannedSecond.planned()[0], diamonds(2))),
                        scope(plannedFirst, new SlotChange(0, plannedFirst.planned()[0], diamonds(1)))
                ),
                false
        );
        List<InventoryChange> changes = assertInstanceOf(TransactionResult.Committed.class, result).rootChanges();

        assertSame(second, changes.get(0).inventory());
        assertSame(first, changes.get(1).inventory());
        List<InventoryChange> postChanges = observedChanges.get();

        assertSame(second, postChanges.get(0).inventory());
        assertSame(first, postChanges.get(1).inventory());
    }

    @Test
    void landFailurePropagatesAfterPublishingPostEvents() {
        IllegalStateException expected = new IllegalStateException("platform write failed");
        ReferencingInventory inventory = ReferencingInventory.of(new ThrowingWriteStorage(expected));
        AtomicInteger postEvents = new AtomicInteger();
        AtomicLong postVersion = new AtomicLong();
        inventory.subscribePostUpdate(event -> {
            postVersion.set(event.version());
            postEvents.incrementAndGet();
        });

        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> inventory.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(2))));

        assertNull(inventory.itemAt(0));
        assertEquals(1, postEvents.get());
        assertTrue(postVersion.get() > 0L);
    }

    @Test
    void committedCallbackStillRunsBeforePostWhenLandFails() {
        IllegalStateException expected = new IllegalStateException("platform write failed");
        ReferencingInventory inventory = ReferencingInventory.of(new ThrowingWriteStorage(expected));
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicInteger postEvents = new AtomicInteger();
        inventory.subscribePostUpdate(event -> {
            assertEquals(1, callbackCalls.get());
            postEvents.incrementAndGet();
        });
        PlannedRoot basis = inventory.openPlanForWrite();

        assertSame(expected, assertThrows(IllegalStateException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                new TransactionDraft(List.of(new TransactionScope(basis, List.of(new SlotChange(0, basis.planned()[0], diamonds(2)))))),
                null,
                false,
                callbackCalls::incrementAndGet,
                List.of(),
                () -> true
        )));

        assertEquals(1, callbackCalls.get());
        assertEquals(1, postEvents.get());
        assertNull(inventory.itemAt(0));
    }

    @Test
    void landFailureDoesNotSkipLaterRoots() {
        IllegalStateException expected = new IllegalStateException("first platform write failed");
        ReferencingInventory first = ReferencingInventory.of(new ThrowingWriteStorage(expected));
        AtomicInteger laterWrites = new AtomicInteger();
        ReferencingInventory second = ReferencingInventory.of(new RecordingWriteStorage(laterWrites));
        AtomicInteger postEvents = new AtomicInteger();
        first.subscribePostUpdate(event -> postEvents.incrementAndGet());
        PlannedRoot plannedFirst = first.openPlanForWrite();
        PlannedRoot plannedSecond = second.openPlanForWrite();

        assertSame(expected, assertThrows(IllegalStateException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        new TransactionScope(plannedFirst, List.of(new SlotChange(0, plannedFirst.planned()[0], diamonds(1)))),
                        new TransactionScope(plannedSecond, List.of(new SlotChange(0, plannedSecond.planned()[0], diamonds(1))))
                ),
                false
        )));

        assertEquals(1, laterWrites.get());
        assertEquals(1, postEvents.get());
        assertNull(first.itemAt(0));
        assertEquals(1, ItemUtils.amountOf(second.itemAt(0)));
    }

    @Test
    void multipleLandFailuresAreAggregatedAfterEveryRootRuns() {
        IllegalStateException firstFailure = new IllegalStateException("first platform write failed");
        IllegalArgumentException secondFailure = new IllegalArgumentException("second platform write failed");
        ReferencingInventory first = ReferencingInventory.of(new ThrowingWriteStorage(firstFailure));
        ReferencingInventory second = ReferencingInventory.of(new ThrowingWriteStorage(secondFailure));
        PlannedRoot plannedFirst = first.openPlanForWrite();
        PlannedRoot plannedSecond = second.openPlanForWrite();

        assertSame(firstFailure, assertThrows(IllegalStateException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        new TransactionScope(plannedFirst, List.of(new SlotChange(0, plannedFirst.planned()[0], diamonds(1)))),
                        new TransactionScope(plannedSecond, List.of(new SlotChange(0, plannedSecond.planned()[0], diamonds(1))))
                ),
                false
        )));

        assertEquals(1, firstFailure.getSuppressed().length);
        assertSame(secondFailure, firstFailure.getSuppressed()[0]);
        assertNull(first.itemAt(0));
        assertNull(second.itemAt(0));
    }

    @Test
    void landNeverMutatesInstancesReadFromAPureContentStorage() {
        MemoryStorage storage = new MemoryStorage(1);
        ItemStack original = diamonds(2);
        storage.items[0] = original;
        ReferencingInventory inventory = ReferencingInventory.of(storage);

        assertInstanceOf(TransactionResult.Committed.class,
                inventory.trySetItem(UpdateReason.Program.INSTANCE, 0, diamonds(5)));

        assertEquals(2, original.getAmount());
        assertNotSame(original, storage.items[0]);
        assertEquals(5, storage.items[0].getAmount());
    }

    @Test
    void cancelledCrossInventoryMoveLeavesBothSidesUntouched() {
        VirtualInventory source = new VirtualInventory(1);
        VirtualInventory target = new VirtualInventory(1);
        source.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(5));
        source.subscribePreUpdate(event -> event.setCancelled(true));
        PlannedRoot sourcePlan = source.openPlanForWrite();
        PlannedRoot targetPlan = target.openPlanForWrite();
        TransactionResult result = InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(
                        new TransactionScope(sourcePlan, List.of(new SlotChange(0, sourcePlan.planned()[0], null))),
                        new TransactionScope(targetPlan, List.of(new SlotChange(0, null, sourcePlan.planned()[0])))
                ),
                false
        );

        assertInstanceOf(TransactionResult.Cancelled.class, result);
        assertEquals(5, ItemUtils.amountOf(source.itemAt(0)));
        assertNull(target.itemAt(0));
    }

    @Test
    @Timeout(10)
    void subscriptionSnapshotSkipsClosedAndDoesNotIncludeReplacementSubscriber() throws InterruptedException {
        CountDownLatch landEntered = new CountDownLatch(1);
        CountDownLatch releaseLand = new CountDownLatch(1);
        ReferencingInventory inventory = ReferencingInventory.of(new BlockingWriteStorage(landEntered, releaseLand));
        AtomicInteger closedSubscriberCalls = new AtomicInteger();
        AtomicInteger replacementSubscriberCalls = new AtomicInteger();
        AtomicReference<Throwable> commitFailure = new AtomicReference<>();
        Subscription subscription = inventory.subscribePostUpdate(event -> closedSubscriberCalls.incrementAndGet());
        Thread committer = new Thread(() -> {
            try {
                inventory.setItem(UpdateReason.Program.INSTANCE, 0, diamonds(1));
            } catch (Throwable throwable) {
                commitFailure.set(throwable);
            }
        }, "subscription-snapshot-committer");
        committer.start();
        landEntered.await();
        subscription.close();
        inventory.subscribePostUpdate(event -> replacementSubscriberCalls.incrementAndGet());
        releaseLand.countDown();
        committer.join();
        if (commitFailure.get() != null) {
            throw new AssertionError("commit failed", commitFailure.get());
        }

        assertEquals(0, closedSubscriberCalls.get());
        assertEquals(0, replacementSubscriberCalls.get());
        assertInstanceOf(TransactionResult.Committed.class,
                inventory.trySetItem(UpdateReason.Program.INSTANCE, 0, diamonds(2)));

        assertEquals(1, replacementSubscriberCalls.get());
    }

    @Test
    void subscriptionsAddedDuringPreBeginWithTheNextTransaction() {
        TestInventory inventory = new TestInventory(1);
        AtomicInteger postEvents = new AtomicInteger();
        inventory.subscribePreUpdate(event -> inventory.subscribePostUpdate(ignoredEvent -> postEvents.incrementAndGet()));

        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(1)));
        assertEquals(0, postEvents.get());
        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(2)));
        assertEquals(1, postEvents.get());
        assertInstanceOf(TransactionResult.Committed.class, set(inventory, 0, diamonds(3)));
        assertEquals(3, postEvents.get());
    }

    @Test
    void rejectsInvalidTransactionShapes() {
        TestInventory inventory = new TestInventory(1);
        PlannedRoot basis = inventory.openPlan();
        SlotChange delta = new SlotChange(0, basis.planned()[0], diamonds(1));
        org.bukkit.inventory.Inventory chest = org.bukkit.Bukkit.createInventory(null, 9);
        ReferencingInventory firstMirror = ReferencingInventory.fromContents(chest);
        ReferencingInventory secondMirror = ReferencingInventory.fromContents(chest);
        PlannedRoot firstPlan = firstMirror.openPlanForWrite();
        PlannedRoot secondPlan = secondMirror.openPlanForWrite();
        List<TransactionScope> conflictingMirrors = List.of(
                new TransactionScope(firstPlan, List.of(new SlotChange(0, null, diamonds(1)))),
                new TransactionScope(secondPlan, List.of(new SlotChange(0, null, diamonds(2))))
        );

        assertThrows(IllegalArgumentException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE, List.of(), false
        ));

        assertThrows(IllegalArgumentException.class, () -> commit(
                new TransactionScope(basis, List.of())
        ));

        assertThrows(IllegalArgumentException.class, () -> commit(
                scope(basis, new SlotChange(1, null, diamonds(1)))
        ));

        assertThrows(IllegalArgumentException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE,
                List.of(scope(basis, delta), scope(basis, delta)),
                false
        ));

        assertThrows(IllegalArgumentException.class, () -> InventoryTransactions.commit(
                UpdateReason.Program.INSTANCE, conflictingMirrors, false
        ));
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static ItemStack[] filled(int size, int amount) {
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            contents[i] = new ItemStack(Material.DIAMOND, amount);
        }
        return contents;
    }

    private static TransactionScope scope(PlannedRoot basis, SlotChange delta) {
        return new TransactionScope(basis, List.of(delta));
    }

    private static TransactionResult commit(TransactionScope scope) {
        return InventoryTransactions.commit(UpdateReason.Program.INSTANCE, List.of(scope), false);
    }

    private static TransactionResult setSlot(SparrowInventory inventory, int slot, ItemStack item) {
        PlannedRoot basis = inventory.openPlan();
        return commit(scope(basis, new SlotChange(slot, basis.planned()[slot], item)));
    }

    private static TransactionResult set(TestInventory inventory, int slot, ItemStack item) {
        PlannedRoot basis = inventory.openPlan();
        return commit(scope(basis, new SlotChange(slot, basis.planned()[slot], item)));
    }

    private static void commitAndCapture(
            TestInventory inventory,
            int slot,
            ItemStack item,
            AtomicReference<Throwable> failure
    ) {
        try {
            assertInstanceOf(TransactionResult.Committed.class, set(inventory, slot, item));
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private static void incrementUntilCommitted(TestInventory inventory, int slot) {
        while (true) {
            PlannedRoot basis = inventory.openPlan();
            ItemStack[] planned = basis.planned();
            int next = ItemUtils.amountOf(planned[slot]) + 1;
            if (commit(scope(basis, new SlotChange(slot, planned[slot], diamonds(next))))
                    instanceof TransactionResult.Committed) {
                return;
            }
        }
    }

    private static int amountOfSingleDelta(InventoryPostUpdateEvent event) {
        List<SlotChange> deltas = event.slotChanges();

        assertEquals(1, deltas.size());
        return ItemUtils.amountOf(deltas.getFirst().after());
    }

    private static void runConcurrently(int threads, WorkerFactory factory) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int worker = 0; worker < threads; worker++) {
            Runnable body = factory.create(worker);
            Thread thread = new Thread(() -> {
                awaitQuietly(start);
                try {
                    body.run();
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }, "inventory-worker-" + worker);
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("worker failed", failure.get());
        }
    }

    private static String describeAmounts(ItemStack[] snapshot) {
        StringBuilder amounts = new StringBuilder("[");
        for (int i = 0; i < snapshot.length; i++) {
            if (i > 0) {
                amounts.append(", ");
            }
            amounts.append(ItemUtils.amountOf(snapshot[i]));
        }
        return amounts.append(']').toString();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test latch", exception);
        }
    }

    private interface WorkerFactory {
        Runnable create(int worker);
    }

    private static class MemoryStorage implements ExternalStorage {
        final ItemStack[] items;
        private MemoryStorage(int size) {
            this.items = new ItemStack[size];
        }
        @Override
        public int size() {
            return this.items.length;
        }
        @Override
        public ItemStack read(int slot) {
            return this.items[slot];
        }
        @Override
        public void write(int slot, ItemStack item) {
            this.items[slot] = item;
        }
        @Override
        public int maxStackSize(int slot) {
            return 99;
        }
    }

    private static final class ThrowingWriteStorage extends MemoryStorage {
        private final RuntimeException failure;
        private ThrowingWriteStorage(RuntimeException failure) {
            super(1);
            this.failure = failure;
        }
        @Override
        public void write(int slot, ItemStack item) {
            throw this.failure;
        }
    }

    private static final class BlockingWriteStorage extends MemoryStorage {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private BlockingWriteStorage(CountDownLatch entered, CountDownLatch release) {
            super(1);
            this.entered = entered;
            this.release = release;
        }
        @Override
        public void write(int slot, ItemStack item) {
            this.entered.countDown();
            awaitQuietly(this.release);
            super.write(slot, item);
        }
    }

    private static final class RecordingWriteStorage extends MemoryStorage {
        private final AtomicInteger writes;
        private RecordingWriteStorage(AtomicInteger writes) {
            super(1);
            this.writes = writes;
        }
        @Override
        public void write(int slot, ItemStack item) {
            this.writes.incrementAndGet();
            super.write(slot, item);
        }
    }
}
