package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 将任意线程提交的 Window 命令线性化到一个玩家的实体线程.
 * 同一时刻最多一个 drain 在运行, 因而生命周期、渲染与协议状态不会跨线程交错.
 */
final class PlayerCommandLane {
    private final Player player;
    private final WindowScheduler scheduler;
    private final Runnable retiredHandler;
    private final ArrayDeque<Command<?>> commands = new ArrayDeque<>();

    private boolean scheduled;
    private boolean draining;
    private boolean retired;

    PlayerCommandLane(Player player, WindowScheduler scheduler, Runnable retiredHandler) {
        this.player = player;
        this.scheduler = scheduler;
        this.retiredHandler = retiredHandler;
    }

    /**
     * 提交可在当前实体线程内联执行的命令.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体退役后执行的替代操作
     * @param <T> 操作结果类型
     * @return 对应操作的完成阶段
     */
    <T> @NotNull CompletionStage<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.submit(action, retiredAction, true);
    }

    /**
     * 提交必须在后续实体 tick 执行的命令.
     * 用于 Bukkit 回调等不能在当前调用栈内递归改变 Window 生命周期的场景.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体退役后执行的替代操作
     * @param <T> 操作结果类型
     * @return 对应操作的完成阶段
     */
    <T> @NotNull CompletionStage<T> submitDeferred(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.submit(action, retiredAction, false);
    }

    /**
     * 在锁内决定命令是立即 drain、安排实体调度还是按退役路径完成.
     * 调度器调用发生在锁外, 避免 retired 回调与命令队列锁形成重入关系.
     */
    private <T> CompletionStage<T> submit(Callable<T> action, Callable<T> retiredAction, boolean allowInline) {
        Command<T> command = new Command<>(action, retiredAction);
        boolean runNow = false;
        boolean schedule = false;
        boolean retireNow = false;
        boolean owned = this.scheduler.entity().isOwnedByCurrentRegion(this.player);

        synchronized (this) {
            if (this.retired) {
                retireNow = true;
            } else {
                this.commands.addLast(command);
                if (allowInline && owned && !this.draining && !this.scheduled) {
                    this.draining = true;
                    runNow = true;
                } else if (!this.draining && !this.scheduled) {
                    this.scheduled = true;
                    schedule = true;
                }
            }
        }

        if (retireNow) {
            this.completeRetired(List.of(command));
        } else if (runNow) {
            this.drain();
        } else if (schedule) {
            try {
                this.scheduler.entity().run(this.player, this::runScheduled, this::retire);
            } catch (Throwable throwable) {
                this.fail(throwable);
            }
        }
        return command.stage();
    }

    /**
     * 退役命令通道并按 retired 路径完成所有尚未执行的命令.
     */
    void retire() {
        List<Command<?>> pending;
        synchronized (this) {
            if (this.retired) {
                return;
            }
            this.retired = true;
            this.scheduled = false;
            pending = this.takePending();
        }
        this.completeRetired(pending);
        this.scheduler.async().runNow(this.retiredHandler);
    }

    private void fail(Throwable failure) {
        List<Command<?>> pending;
        synchronized (this) {
            if (this.retired) {
                return;
            }
            this.retired = true;
            this.scheduled = false;
            pending = this.takePending();
        }
        this.scheduler.async().runNow(() -> {
            for (int index = 0; index < pending.size(); index++) {
                pending.get(index).fail(failure);
            }
        });
        this.scheduler.async().runNow(this.retiredHandler);
    }

    private void runScheduled() {
        synchronized (this) {
            this.scheduled = false;
            if (this.retired || this.draining) {
                return;
            }
            this.draining = true;
        }
        this.drain();
    }

    /**
     * 依次执行已入队命令, 并在退役竞态中把剩余命令转交给 retired 完成器.
     */
    private void drain() {
        while (true) {
            Command<?> command;
            List<Command<?>> retiredCommands = List.of();
            synchronized (this) {
                if (this.retired) {
                    retiredCommands = this.takePending();
                    this.draining = false;
                    command = null;
                } else {
                    command = this.commands.pollFirst();
                    if (command == null) {
                        this.draining = false;
                    }
                }
            }

            if (!retiredCommands.isEmpty()) {
                this.completeRetired(retiredCommands);
            }
            if (command == null) {
                return;
            }
            command.run();
        }
    }

    private List<Command<?>> takePending() {
        if (this.commands.isEmpty()) {
            return List.of();
        }
        ArrayList<Command<?>> pending = new ArrayList<>(this.commands);
        this.commands.clear();
        return List.copyOf(pending);
    }

    private void completeRetired(List<Command<?>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        this.scheduler.async().runNow(() -> {
            for (int index = 0; index < pending.size(); index++) {
                pending.get(index).retire();
            }
        });
    }

    /**
     * 队列中的一次命令及其正常、退役两种完成路径.
     */
    private static final class Command<T> {
        private final Callable<T> action;
        private final Callable<T> retiredAction;
        private final CompletableFuture<T> completion = new CompletableFuture<>();

        private Command(Callable<T> action, Callable<T> retiredAction) {
            this.action = action;
            this.retiredAction = retiredAction;
        }

        private CompletionStage<T> stage() {
            return this.completion.minimalCompletionStage();
        }

        private void run() {
            this.complete(this.action);
        }

        private void retire() {
            this.complete(this.retiredAction);
        }

        private void fail(Throwable throwable) {
            this.completion.completeExceptionally(throwable);
        }

        private void complete(Callable<T> callable) {
            try {
                this.completion.complete(callable.call());
            } catch (Throwable throwable) {
                this.completion.completeExceptionally(throwable);
            }
        }
    }
}
