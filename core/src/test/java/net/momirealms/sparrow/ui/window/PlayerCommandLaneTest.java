package net.momirealms.sparrow.ui.window;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaEntityExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandLaneTest {

    private static final ScheduledTask ACCEPTED_TASK = new ScheduledTask() {
        @Override
        public Plugin getOwningPlugin() {
            return SparrowUiTestRuntime.plugin();
        }
        @Override
        public boolean isRepeatingTask() {
            return false;
        }
        @Override
        public CancelledState cancel() {
            return CancelledState.CANCELLED_BY_CALLER;
        }
        @Override
        public ExecutionState getExecutionState() {
            return ExecutionState.IDLE;
        }
    };

    @AfterEach
    void tearDown() {
        SparrowUiTestRuntime.restoreOwnership();
    }

    @Test
    void offThreadCommandsStayOrderedUntilTheEntityTaskRuns() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        PlayerCommandLane lane = lane(entityExecutor, ignoredLane -> {});
        List<Integer> order = new ArrayList<>();
        CompletionStage<Integer> first = lane.submit(() -> {
            order.add(1);
            return 1;
        }, () -> -1);
        CompletionStage<Integer> second = lane.submit(() -> {
            order.add(2);
            return 2;
        }, () -> -2);

        assertTrue(order.isEmpty());
        assertFalse(first.toCompletableFuture().isDone());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(List.of(1, 2), order);
        assertEquals(1, first.toCompletableFuture().join());
        assertEquals(2, second.toCompletableFuture().join());
    }

    @Test
    void entityThreadSubmissionCanCompleteInline() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        entityExecutor.owned = true;
        PlayerCommandLane lane = lane(entityExecutor, ignoredLane -> {});
        CompletionStage<String> result = lane.submit(() -> "done", () -> "retired");

        assertEquals("done", result.toCompletableFuture().join());
        assertTrue(entityExecutor.tasks.isEmpty());
    }

    @Test
    void deferredEntitySubmissionWaitsForItsScheduledTurnAndKeepsLaterCommandsBehindIt() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        entityExecutor.owned = true;
        PlayerCommandLane lane = lane(entityExecutor, ignoredLane -> {});
        List<Integer> order = new ArrayList<>();
        CompletionStage<Integer> deferred = lane.submitDeferred(() -> {
            order.add(1);
            return 1;
        }, () -> -1);
        CompletionStage<Integer> later = lane.submit(() -> {
            order.add(2);
            return 2;
        }, () -> -2);

        assertTrue(order.isEmpty());
        assertEquals(1, entityExecutor.tasks.size());
        entityExecutor.runNext();

        assertEquals(List.of(1, 2), order);
        assertEquals(1, deferred.toCompletableFuture().join());
        assertEquals(2, later.toCompletableFuture().join());
    }

    @Test
    void acceptedSchedulingUsesRetiredCallbackAndFinalizesOnce() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        List<String> order = new ArrayList<>();
        PlayerCommandLane lane = lane(entityExecutor, ignoredLane -> order.add("finalizer"));
        CompletionStage<String> first = lane.submit(
                () -> "first-ran",
                () -> {
                    order.add("first-retired");
                    return "first-retired";
                }
        );
        CompletionStage<String> second = lane.submit(
                () -> "second-ran",
                () -> {
                    order.add("second-retired");
                    return "second-retired";
                }
        );
        entityExecutor.retireNext();
        lane.retire();

        assertEquals("first-retired", first.toCompletableFuture().join());
        assertEquals("second-retired", second.toCompletableFuture().join());
        assertEquals(List.of("finalizer", "first-retired", "second-retired"), order);
        assertEquals(0, SparrowUiTestRuntime.asyncRuns(), "退役完成留在当前线程, 插件停用时异步任务会被 Paper 取消");
    }

    @Test
    void schedulingFailureFailsPendingStageAndReportsTheCause() {
        ControlledEntityExecutor entityExecutor = new ControlledEntityExecutor();
        IllegalStateException failure = new IllegalStateException("scheduler failed");
        entityExecutor.failure = failure;
        AtomicReference<PlayerCommandLane> finalizedLane = new AtomicReference<>();
        PlayerCommandLane lane = lane(entityExecutor, finalizedLane::set);
        CompletionStage<String> result = lane.submit(() -> "ran", () -> "retired");
        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> result.toCompletableFuture().join()
        );

        assertSame(failure, completionFailure.getCause());
        assertSame(lane, finalizedLane.get());
        assertEquals(0, SparrowUiTestRuntime.asyncRuns(), "失败完成留在当前线程, 插件停用时异步任务会被 Paper 取消");
    }

    private static PlayerCommandLane lane(
            ControlledEntityExecutor entityExecutor,
            Consumer<PlayerCommandLane> retiredHandler
    ) {
        SparrowUiTestRuntime.installOwnership(entityExecutor::isOwnedByCurrentRegion);
        return new PlayerCommandLane(
                entityExecutor.player,
                new FoliaEntityExecutor(SparrowUiTestRuntime.plugin()),
                retiredHandler
        );
    }

    private static final class ControlledEntityExecutor implements EntityScheduler {
        private final ArrayDeque<ScheduledInvocation> tasks = new ArrayDeque<>();
        private final Player player;
        private boolean owned;
        private boolean accept = true;
        private RuntimeException failure;
        private ControlledEntityExecutor() {
            this.player = (Player) Proxy.newProxyInstance(
                    PlayerCommandLaneTest.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getScheduler" -> this;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                        case "toString" -> "ScheduledPlayer";
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
            );
        }
        private boolean isOwnedByCurrentRegion() {
            return this.owned;
        }
        @Override
        public boolean execute(@NonNull Plugin plugin, @NonNull Runnable task, @NonNull Runnable retired, long delay) {
            throw new UnsupportedOperationException();
        }
        @Override
        public ScheduledTask run(@NonNull Plugin plugin, @NonNull Consumer<ScheduledTask> task, @NonNull Runnable retired) {
            if (this.failure != null) {
                throw this.failure;
            }
            if (!this.accept) {
                return null;
            }
            this.tasks.addLast(new ScheduledInvocation(() -> task.accept(ACCEPTED_TASK), retired));
            return ACCEPTED_TASK;
        }
        @Override
        public ScheduledTask runDelayed(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long delay
        ) {
            throw new UnsupportedOperationException();
        }
        @Override
        public ScheduledTask runAtFixedRate(
                @NonNull Plugin plugin,
                @NonNull Consumer<ScheduledTask> task,
                @NonNull Runnable retired,
                long initialDelay,
                long period
        ) {
            throw new UnsupportedOperationException();
        }
        private void runNext() {
            this.owned = true;
            try {
                this.tasks.removeFirst().task().run();
            } finally {
                this.owned = false;
            }
        }
        private void retireNext() {
            this.tasks.removeFirst().retired().run();
        }
    }

    private record ScheduledInvocation(Runnable task, Runnable retired) {
    }
}
