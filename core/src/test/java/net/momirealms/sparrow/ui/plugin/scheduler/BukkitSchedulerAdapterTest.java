package net.momirealms.sparrow.ui.plugin.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.scheduler.BukkitSchedulerAdapter;
import net.momirealms.sparrow.ui.scheduler.SchedulerAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitSchedulerAdapterTest {

    private ServerMock server;
    private Plugin plugin;
    private Entity entity;
    private boolean entityValid;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin();
        this.entityValid = true;
        this.entity = (Entity) Proxy.newProxyInstance(
                BukkitSchedulerAdapterTest.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isValid" -> this.entityValid;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "SchedulerTestEntity";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void mainThreadDelayedTaskWaitsForTheNextTick() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        executor.runDelayed(runs::incrementAndGet);
        assertEquals(0, runs.get());

        this.server.getScheduler().performOneTick();
        assertEquals(1, runs.get());
    }

    @Test
    void bukkitRegionTasksUseMainThreadTimingAndCancellation() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        var world = this.server.addSimpleWorld("scheduler-test");
        executor.run(runs::incrementAndGet, world, -3, 7);
        assertEquals(1, runs.get());

        SchedulerTask task = executor.runLater(runs::incrementAndGet, 2, world, -3, 7);
        task.cancel();
        this.server.getScheduler().performTicks(3);
        assertEquals(1, runs.get());
        assertTrue(task.cancelled());
    }

    @Test
    void asyncEntryRunsWithoutServerTicksAndSupportsSelfCancellation() throws InterruptedException {
        SchedulerAdapter scheduler = new BukkitSchedulerAdapter(this.plugin);
        CountDownLatch executed = new CountDownLatch(1);
        CountDownLatch repeated = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            assertNotNull(scheduler.platform());

            scheduler.async().execute(() -> {
                worker.set(Thread.currentThread());
                executed.countDown();
            });
            assertTrue(executed.await(5, TimeUnit.SECONDS));
            assertFalse(Thread.currentThread() == worker.get());

            SchedulerTask task = scheduler.asyncRepeating(handle -> {
                handle.cancel();
                repeated.countDown();
            }, 1, 60, TimeUnit.SECONDS);
            assertTrue(repeated.await(5, TimeUnit.SECONDS));
            assertTrue(task.cancelled());
        } finally {
            scheduler.shutdownScheduler();
            scheduler.shutdownExecutor();
        }
    }

    @Test
    void bukkitEntityTasksUseTheMainThreadScheduler() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        SchedulerTask task = executor.runLater(runs::incrementAndGet, retired::incrementAndGet, 0, this.entity);

        assertTrue(executor.isOwnedByCurrentRegion(this.entity));
        assertEquals(0, runs.get());
        this.server.getScheduler().performOneTick();

        assertEquals(1, runs.get());
        assertEquals(0, retired.get());
        task.cancel();

        assertTrue(task.cancelled());
    }

    @Test
    void zeroDelayMainThreadTaskRunsInlineOnTheMainThread() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        SchedulerTask task = executor.runLater(runs::incrementAndGet, 0);

        assertEquals(1, runs.get());
        assertTrue(task.cancelled());
        task.cancel();

        assertTrue(task.cancelled());
    }

    @Test
    void unavailableEntityIsRejectedOnTheMainThread() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        this.entityValid = false;
        SchedulerTask task = executor.runLater(runs::incrementAndGet, retired::incrementAndGet, 0, this.entity);

        assertFalse(executor.isOwnedByCurrentRegion(this.entity));
        assertNull(task);
        assertEquals(0, runs.get());
        assertEquals(0, retired.get());
    }

    @Test
    void entityRetiredWhileWaitingForTheMainThreadUsesTheRetiredPath() throws InterruptedException {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        AtomicReference<SchedulerTask> submitted = new AtomicReference<>();
        Thread submitter = new Thread(() -> submitted.set(executor.runLater(runs::incrementAndGet, retired::incrementAndGet, 0, this.entity)));
        submitter.start();
        submitter.join();
        this.entityValid = false;
        this.server.getScheduler().performOneTick();

        assertNotNull(submitted.get());
        assertEquals(0, runs.get());
        assertEquals(1, retired.get());
    }

    @Test
    void repeatingTaskCancelsAndRetiresOnceWhenTheEntityBecomesUnavailable() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        SchedulerTask task = executor.runRepeating(runs::incrementAndGet, retired::incrementAndGet, 1, 1, this.entity);
        this.server.getScheduler().performOneTick();
        this.entityValid = false;
        this.server.getScheduler().performTicks(2);

        assertNotNull(task);
        assertEquals(1, runs.get());
        assertEquals(1, retired.get());
        assertTrue(task.cancelled());
    }

    @Test
    void offThreadRepeatingSubmissionChecksAvailabilityOnTheMainThread() throws InterruptedException {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger retired = new AtomicInteger();
        AtomicReference<SchedulerTask> submitted = new AtomicReference<>();
        this.entityValid = false;
        Thread submitter = new Thread(() -> submitted.set(executor.runRepeating(() -> {}, retired::incrementAndGet, 1, 1, this.entity)));
        submitter.start();
        submitter.join();
        this.server.getScheduler().performOneTick();

        assertNotNull(submitted.get());
        assertEquals(1, retired.get());
        assertTrue(submitted.get().cancelled());
    }
}
