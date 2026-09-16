package net.momirealms.sparrow.ui.plugin.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitEntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
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
    void bukkitEntityTasksUseTheMainThreadScheduler() {
        BukkitEntityExecutor executor = new BukkitEntityExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        SchedulerTask task = executor.run(this.entity, runs::incrementAndGet, retired::incrementAndGet);

        assertTrue(executor.isOwnedByCurrentRegion(this.entity));
        assertEquals(0, runs.get());
        this.server.getScheduler().performOneTick();

        assertEquals(1, runs.get());
        assertEquals(0, retired.get());
        task.cancel();

        assertTrue(task.cancelled());
    }

    @Test
    void zeroDelayRegionTaskRunsInlineOnTheMainThread() {
        BukkitExecutor executor = new BukkitExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        SchedulerTask task = executor.runLater(runs::incrementAndGet, 0);

        assertEquals(1, runs.get());
        assertFalse(task.cancelled());
        task.cancel();

        assertTrue(task.cancelled());
    }

    @Test
    void unavailableEntityIsRejectedOnTheMainThread() {
        BukkitEntityExecutor executor = new BukkitEntityExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        this.entityValid = false;
        SchedulerTask task = executor.run(this.entity, runs::incrementAndGet, retired::incrementAndGet);

        assertFalse(executor.isOwnedByCurrentRegion(this.entity));
        assertNull(task);
        assertEquals(0, runs.get());
        assertEquals(0, retired.get());
    }

    @Test
    void entityRetiredWhileWaitingForTheMainThreadUsesTheRetiredPath() throws InterruptedException {
        BukkitEntityExecutor executor = new BukkitEntityExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        AtomicReference<SchedulerTask> submitted = new AtomicReference<>();
        Thread submitter = new Thread(() -> submitted.set(executor.run(this.entity, runs::incrementAndGet, retired::incrementAndGet)));
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
        BukkitEntityExecutor executor = new BukkitEntityExecutor(this.plugin);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger retired = new AtomicInteger();
        SchedulerTask task = executor.runAtFixedRate(this.entity, runs::incrementAndGet, retired::incrementAndGet, 1, 1);
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
        BukkitEntityExecutor executor = new BukkitEntityExecutor(this.plugin);
        AtomicInteger retired = new AtomicInteger();
        AtomicReference<SchedulerTask> submitted = new AtomicReference<>();
        this.entityValid = false;
        Thread submitter = new Thread(() -> submitted.set(executor.runAtFixedRate(this.entity, () -> {}, retired::incrementAndGet, 1, 1)));
        submitter.start();
        submitter.join();
        this.server.getScheduler().performOneTick();

        assertNotNull(submitted.get());
        assertEquals(1, retired.get());
        assertTrue(submitted.get().cancelled());
    }
}
