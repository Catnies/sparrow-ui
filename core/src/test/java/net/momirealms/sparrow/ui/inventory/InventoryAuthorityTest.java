package net.momirealms.sparrow.ui.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.storage.ExternalStorage;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class InventoryAuthorityTest {
    private List<Throwable> reportedExceptions;
    private UpdateReason playerReason;

    @BeforeEach
    void setUp() {
        this.playerReason = new PlayerUpdateReason.Click(MockBukkit.mock().addPlayer(), ClickType.LEFT, -1);
        this.reportedExceptions = new CopyOnWriteArrayList<>();
        SparrowUI.getInstance().setExceptionHandler((message, exception) -> this.reportedExceptions.add(exception));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        assertTrue(this.reportedExceptions.isEmpty(), this.reportedExceptions.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commands")
    void authorityBypassesPreRulesAndFrozenEvenWithPlayerReason(String name, BiConsumer<SparrowInventory, UpdateReason> command, int expectedAmount) {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3)});
        AtomicInteger preCalls = new AtomicInteger();
        AtomicInteger ruleCalls = new AtomicInteger();
        AtomicInteger postCalls = new AtomicInteger();
        inventory.frozen(true);
        inventory.setAccessRule(context -> {
            ruleCalls.incrementAndGet();
            return false;
        });
        inventory.subscribePreUpdate(event -> {
            preCalls.incrementAndGet();
            event.setCancelled(true);
        });
        inventory.subscribePostUpdate(event -> {
            assertSame(this.playerReason, event.reason());
            assertFalse(inventory.stateLock().lock().isHeldByCurrentThread());
            postCalls.incrementAndGet();
        });

        command.accept(inventory, this.playerReason);

        assertEquals(expectedAmount, inventory.itemAmount(0));
        assertEquals(0, preCalls.get());
        assertEquals(0, ruleCalls.get());
        assertEquals(1, postCalls.get());
    }

    static Stream<Arguments> commands() {
        return Stream.of(
                Arguments.of("setItem", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> inventory.setItem(reason, 0, diamonds(4)), 4),
                Arguments.of("putItem", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> assertEquals(0, inventory.putItem(reason, 0, diamonds(2))), 5),
                Arguments.of("modifyItem", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> inventory.modifyItem(reason, 0, current -> diamonds(current.getAmount() + 1)), 4),
                Arguments.of("changeAmount", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> assertEquals(1, inventory.changeAmount(reason, 0, 1)), 4),
                Arguments.of("add", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> assertEquals(0, inventory.add(reason, diamonds(2))), 5),
                Arguments.of("collect", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> assertEquals(1, inventory.collect(reason, diamonds(1), 1)), 2),
                Arguments.of("remove", (BiConsumer<SparrowInventory, UpdateReason>) (inventory, reason) -> assertEquals(1, inventory.remove(reason, item -> true, 1)), 2),
                Arguments.of("clear", (BiConsumer<SparrowInventory, UpdateReason>) SparrowInventory::clear, 0)
        );
    }

    @Test
    void countsClampingNoopsAndProgramOverloads() {
        VirtualInventory inventory = new VirtualInventory(2);
        inventory.setMaxStackSizes(new int[]{5, 5});
        AtomicInteger posts = new AtomicInteger();
        inventory.subscribePostUpdate(event -> {
            assertSame(UpdateReason.Program.INSTANCE, event.reason());
            posts.incrementAndGet();
        });
        assertEquals(3, inventory.putItem(0, diamonds(8)));
        assertEquals(0, inventory.putItem(1, diamonds(2)));
        assertEquals(5, inventory.add(diamonds(8)));
        assertEquals(7, inventory.collect(diamonds(1), 7));
        assertEquals(3, inventory.remove(item -> true, 10));
        assertTrue(inventory.isEmpty());
        assertEquals(5, posts.get());

        inventory.clear();
        assertEquals(0, inventory.changeAmount(0, 1));
        assertEquals(0, inventory.collect(diamonds(1), 3));
        assertEquals(0, inventory.remove(item -> true, 0));
        assertEquals(0, inventory.add(new ItemStack(Material.AIR)));
        assertEquals(5, posts.get());

        inventory.setItem(0, diamonds(3));
        assertEquals(2, inventory.changeAmount(0, Integer.MAX_VALUE));
        assertEquals(0, inventory.changeAmount(0, 1));
        assertEquals(-5, inventory.changeAmount(0, Integer.MIN_VALUE));
        inventory.setItem(0, diamonds(9));
        assertEquals(-1, inventory.changeAmount(0, -1));
        assertEquals(0, inventory.changeAmount(0, 1));
        assertEquals(8, inventory.itemAmount(0));
    }

    @Test
    void explicitAssignmentsCopyInputsAndKeepOldSnapshots() {
        VirtualInventory inventory = new VirtualInventory(1);
        ItemStack input = diamonds(3);
        inventory.setItem(0, input);
        ItemStack[] snapshot = inventory.unsafeSnapshot();
        input.setAmount(30);
        AtomicReference<ItemStack> modified = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        inventory.modifyItem(0, current -> {
            assertTrue(inventory.stateLock().lock().isHeldByCurrentThread());
            calls.incrementAndGet();
            current.setAmount(4);
            modified.set(current);
            return current;
        });
        modified.get().setAmount(40);
        assertEquals(1, calls.get());
        assertEquals(3, snapshot[0].getAmount());
        assertEquals(4, inventory.itemAmount(0));

        AtomicInteger posts = new AtomicInteger();
        inventory.subscribePostUpdate(event -> posts.incrementAndGet());
        inventory.setItem(0, diamonds(4));
        inventory.modifyItem(0, current -> current);
        inventory.setItem(0, null);
        inventory.setItem(0, null);
        assertEquals(4, posts.get());
    }

    @Test
    void callbackFailurePublishesNoPartialStateAndReleasesTheLock() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3), diamonds(4)});
        ItemStack[] original = inventory.snapshot();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        inventory.subscribePostUpdate(event -> posts.incrementAndGet());

        assertThrows(IllegalArgumentException.class, () -> inventory.remove(item -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalArgumentException("matcher");
            }
            return true;
        }, 10));
        assertThrows(IllegalArgumentException.class, () -> inventory.modifyItem(0, current -> {
            current.setAmount(40);
            throw new IllegalArgumentException("modifier");
        }));
        assertArrayEquals(original, inventory.snapshot());
        assertEquals(0, posts.get());
        assertFalse(inventory.stateLock().lock().isHeldByCurrentThread());
        inventory.clear();
        assertTrue(inventory.isEmpty());
        assertEquals(1, posts.get());
    }

    @Test
    void twoQueuedIncrementsReadAfterAcquiringTheSharedLock() throws Exception {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3)});
        ReentrantLock lock = inventory.stateLock().lock();
        FutureTask<Integer> first = new FutureTask<>(() -> inventory.changeAmount(0, 1));
        FutureTask<Integer> second = new FutureTask<>(() -> inventory.changeAmount(0, 1));
        Thread firstThread = new Thread(first);
        Thread secondThread = new Thread(second);
        lock.lock();
        try {
            firstThread.start();
            secondThread.start();
            awaitQueued(lock, firstThread);
            awaitQueued(lock, secondThread);
            assertEquals(3, inventory.itemAmount(0));
        } finally {
            lock.unlock();
        }
        assertEquals(1, first.get(5, TimeUnit.SECONDS));
        assertEquals(1, second.get(5, TimeUnit.SECONDS));
        assertEquals(5, inventory.itemAmount(0));
    }

    @Test
    void waitingModifierObservesThePrecedingCommandsNewItem() throws Exception {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3)});
        CountDownLatch calculating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> inventory.modifyItem(0, current -> {
                calculating.countDown();
                await(release);
                return new ItemStack(Material.EMERALD, 7);
            }));
            FutureTask<Void> second = new FutureTask<>(() -> {
                inventory.modifyItem(0, current -> {
                    calls.incrementAndGet();
                    assertEquals(new ItemStack(Material.EMERALD, 7), current);
                    current.setAmount(current.getAmount() + 1);
                    return current;
                });
                return null;
            });
            Thread secondThread = new Thread(second);
            try {
                await(calculating);
                secondThread.start();
                awaitQueued(inventory.stateLock().lock(), secondThread);
                assertEquals(0, calls.get());
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get());
        assertEquals(new ItemStack(Material.EMERALD, 8), inventory.itemAt(0));
    }

    @Test
    void staleRequestDoesNotOverwriteAuthorityOrReplayPre() throws Exception {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3)});
        CountDownLatch pre = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            calls.incrementAndGet();
            pre.countDown();
            await(release);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> inventory.tryChangeAmount(0, 10));
            try {
                await(pre);
                assertEquals(1, inventory.changeAmount(0, 1));
            } finally {
                release.countDown();
            }
            assertSame(TransactionResult.Conflicted.INSTANCE, request.get(5, TimeUnit.SECONDS));
        }
        assertEquals(4, inventory.itemAmount(0));
        assertEquals(1, calls.get());
    }

    @Test
    void nestedAuthorityPostKeepsBatchOrderAndIncreasingVersions() {
        VirtualInventory inventory = new VirtualInventory(1);
        inventory.serialPostDispatch(true);
        List<String> delivery = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        inventory.subscribePostUpdate(event -> {
            assertFalse(inventory.stateLock().lock().isHeldByCurrentThread());
            int amount = event.slotChanges().getFirst().unsafeAfter().getAmount();
            delivery.add("first:" + amount);
            versions.add(event.version());
            if (amount == 1) {
                inventory.changeAmount(0, 1);
            }
        });
        inventory.subscribePostUpdate(event -> delivery.add("second:" + event.slotChanges().getFirst().unsafeAfter().getAmount()));

        inventory.setItem(0, diamonds(1));

        assertEquals(List.of("first:1", "second:1", "first:2", "second:2"), delivery);
        assertTrue(versions.get(1) > versions.get(0));
        assertEquals(2, inventory.itemAmount(0));
    }

    @Test
    void bukkitWrapperTakesActualItemsAndBypassesPre() {
        VirtualInventory inventory = new VirtualInventory(1);
        AtomicInteger pre = new AtomicInteger();
        inventory.subscribePreUpdate(event -> {
            pre.incrementAndGet();
            event.setCancelled(true);
        });
        CraftInventory wrapper = (CraftInventory) inventory.asBukkitInventory();
        Container container = wrapper.getInventory();
        wrapper.setItem(0, diamonds(7));
        assertEquals(3, ItemUtils.amountOf(container.removeItem(0, 3).getBukkitStack()));
        assertEquals(4, inventory.itemAmount(0));
        assertEquals(4, ItemUtils.amountOf(container.removeItemNoUpdate(0).getBukkitStack()));
        assertNull(inventory.itemAt(0));
        assertTrue(container.removeItemNoUpdate(0).isEmpty());
        wrapper.setItem(0, diamonds(2));
        container.clearContent();
        assertTrue(inventory.isEmpty());
        assertEquals(0, pre.get());
    }

    @Test
    void concurrentWrapperTakesDoNotReturnTheSameItemsTwice() throws Exception {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(10)});
        Container container = ((CraftInventory) inventory.asBukkitInventory()).getInventory();
        FutureTask<Integer> first = new FutureTask<>(() -> ItemUtils.amountOf(container.removeItem(0, 7).getBukkitStack()));
        FutureTask<Integer> second = new FutureTask<>(() -> ItemUtils.amountOf(container.removeItemNoUpdate(0).getBukkitStack()));
        Thread firstThread = new Thread(first);
        Thread secondThread = new Thread(second);
        ReentrantLock lock = inventory.stateLock().lock();
        lock.lock();
        try {
            firstThread.start();
            secondThread.start();
            awaitQueued(lock, firstThread);
            awaitQueued(lock, secondThread);
        } finally {
            lock.unlock();
        }
        assertEquals(10, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        assertTrue(inventory.isEmpty());
    }

    @Test
    void referencingAuthorityRefreshesAndWritesCurrentStorageOnTheCallingThread() {
        CraftInventory chest = new CraftInventory(new SimpleContainer(2));
        chest.setItem(0, diamonds(3));
        ReferencingInventory inventory = ReferencingInventory.fromContents(chest);
        inventory.subscribePreUpdate(event -> fail("authority must skip Pre"));
        List<UpdateReason> reasons = new ArrayList<>();
        Thread owner = Thread.currentThread();
        inventory.subscribePostUpdate(event -> {
            assertSame(owner, Thread.currentThread());
            reasons.add(event.reason());
        });
        chest.setItem(0, diamonds(6));

        assertEquals(1, inventory.changeAmount(0, 1));
        assertEquals(7, chest.getItem(0).getAmount());
        inventory.clear();
        assertNull(chest.getItem(0));
        assertEquals(List.of(UpdateReason.External.INSTANCE, UpdateReason.Program.INSTANCE, UpdateReason.Program.INSTANCE), reasons);
    }

    @Test
    void retiredAuthorityThrowsAndRequestsStillReportConflict() {
        ReferencingInventory inventory = ReferencingInventory.of(new TestStorage());
        inventory.retire();
        assertThrows(IllegalStateException.class, () -> inventory.setItem(0, diamonds(1)));
        assertThrows(IllegalStateException.class, inventory::clear);
        assertSame(TransactionResult.Conflicted.INSTANCE, inventory.trySetItem(0, diamonds(1)));
    }

    @Test
    void externalLandingFailurePropagatesAndReleasesPostTicket() {
        TestStorage storage = new TestStorage();
        ReferencingInventory inventory = ReferencingInventory.of(storage);
        AtomicInteger posts = new AtomicInteger();
        inventory.subscribePostUpdate(event -> posts.incrementAndGet());
        inventory.updateChannelIfPresent().serialPostDispatch(true);
        storage.fail = true;

        assertThrows(IllegalStateException.class, () -> inventory.setItem(0, diamonds(1)));
        assertEquals(1, posts.get());
        storage.fail = false;
        inventory.setItem(0, diamonds(2));
        assertEquals(2, storage.item.getAmount());
        assertEquals(2, posts.get());
    }

    @Test
    void authorityCallbackCannotWriteAnyInventory() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(3), null});
        VirtualInventory other = new VirtualInventory(1);
        ItemStack[] original = inventory.snapshot();
        AtomicInteger posts = new AtomicInteger();
        inventory.subscribePostUpdate(event -> posts.incrementAndGet());
        other.subscribePostUpdate(event -> posts.incrementAndGet());

        // 写回自己, 外层会按定下来的基准构造状态并盖掉这一笔
        assertThrows(IllegalStateException.class, () -> inventory.modifyItem(0, current -> {
            inventory.setItem(1, diamonds(1));
            return current;
        }));
        // 写别的 Inventory, 会在固定锁序之外多拿一把锁
        assertThrows(IllegalStateException.class, () -> inventory.modifyItem(0, current -> {
            other.setItem(0, diamonds(1));
            return current;
        }));
        // 请求路径同样挡住
        assertThrows(IllegalStateException.class, () -> inventory.remove(item -> {
            inventory.trySetItem(1, diamonds(1));
            return true;
        }, 10));

        assertArrayEquals(original, inventory.snapshot());
        assertTrue(other.isEmpty());
        assertEquals(0, posts.get());
        assertFalse(inventory.stateLock().lock().isHeldByCurrentThread());
    }

    @Test
    void authorityScopeEndsWithTheCommand() {
        VirtualInventory inventory = new VirtualInventory(1);

        assertThrows(IllegalStateException.class, () -> inventory.modifyItem(0, current -> {
            inventory.setItem(0, diamonds(1));
            return current;
        }));
        inventory.setItem(0, diamonds(2));
        assertEquals(2, inventory.itemAmount(0));
        inventory.modifyItem(0, current -> diamonds(current.getAmount() + 1));
        assertEquals(3, inventory.itemAmount(0));
    }

    @Test
    void preHandlerCanStillIssueAuthorityCommands() {
        VirtualInventory inventory = new VirtualInventory(new ItemStack[]{diamonds(1)});
        VirtualInventory ledger = new VirtualInventory(1);
        inventory.subscribePreUpdate(event -> ledger.setItem(0, diamonds(7)));

        assertInstanceOf(TransactionResult.Committed.class, inventory.trySetItem(0, diamonds(2)));
        assertEquals(2, inventory.itemAmount(0));
        assertEquals(7, ledger.itemAmount(0));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitQueued(ReentrantLock lock, Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(thread) && thread.isAlive() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(lock.hasQueuedThread(thread), "writer never queued for the inventory lock");
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static final class TestStorage implements ExternalStorage {
        private ItemStack item;
        private boolean fail;

        @Override
        public int size() {
            return 1;
        }

        @Override
        public ItemStack read(int slot) {
            return this.item;
        }

        @Override
        public void write(int slot, ItemStack item) {
            if (this.fail) {
                throw new IllegalStateException("write failed");
            }
            this.item = item;
        }

        @Override
        public int maxStackSize(int slot) {
            return 64;
        }
    }
}
