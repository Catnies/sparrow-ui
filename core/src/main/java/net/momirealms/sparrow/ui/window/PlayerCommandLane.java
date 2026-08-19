package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class PlayerCommandLane {
    private final Player player;
    private final WindowScheduler scheduler;
    private final Consumer<PlayerCommandLane> retiredHandler;           // 通道注销后的收尾操作
    private final ArrayDeque<Command<?>> commands = new ArrayDeque<>(); // 待执行命令队列, 仅在锁内访问

    private boolean scheduled; // 是否已有实体调度任务待运行, 仅在锁内访问
    private boolean draining;  // 是否有 drain 正在执行, 仅在锁内访问
    private boolean retired;   // 通道是否已注销, 注销后新命令直接走注销路径
    private @Nullable Runnable terminal; // 关停时交给正在 drain 的线程执行的收尾命令, 仅在锁内访问

    PlayerCommandLane(Player player, WindowScheduler scheduler, Consumer<PlayerCommandLane> retiredHandler) {
        this.player = player;
        this.scheduler = scheduler;
        this.retiredHandler = retiredHandler;
    }

    // 判断通道是否属于同一个 Player 实例
    boolean belongsTo(@NotNull Player player) {
        return this.player == player;
    }

    /**
     * 提交可在当前实体线程内联执行的命令.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体退役后执行的替代操作
     * @param <T> 操作结果类型
     * @return 对应操作的完成阶段
     */
    @NotNull
    <T> CompletionStage<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.submit(action, retiredAction, true);
    }

    /**
     * 提交必须在后续 Entity tick 执行的命令.
     * 用于 Bukkit 回调等不能在当前改变 Window 生命周期的场景.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体退役后执行的替代操作
     * @param <T> 操作结果类型
     * @return 对应操作的完成阶段
     */
    @NotNull
    <T> CompletionStage<T> submitDeferred(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.submit(action, retiredAction, false);
    }

    /**
     * 在锁内决定命令是立即 drain, 安排实体调度还是按注销路径完成.
     * 调度器调用发生在锁外, 避免 retired 回调与命令队列锁形成重入关系.
     *
     * @param action 玩家仍可调度时执行的操作
     * @param retiredAction 玩家实体不可用时执行的替代操作
     * @param allowInline 当前线程拥有实体区域时是否允许立即内联执行
     * @param <T> 操作结果类型
     * @return 对应操作的完成阶段
     */
    private <T> CompletionStage<T> submit(Callable<T> action, Callable<T> retiredAction, boolean allowInline) {
        Command<T> command = new Command<>(action, retiredAction);
        boolean runNow = false;
        boolean schedule = false;
        boolean retireNow = false;
        boolean owned = this.scheduler.entity().isOwnedByCurrentRegion(this.player);

        // 决定命令的走向
        synchronized (this) {
            if (this.retired) {
                retireNow = true;
            } else {
                this.commands.addLast(command);
                if (allowInline && owned && !this.draining && !this.scheduled) {
                    // 当前线程就是实体线程且通道空闲, 可以立即内联执行
                    this.draining = true;
                    runNow = true;
                } else if (!this.draining && !this.scheduled) {
                    // 通道空闲但无法内联, 安排一次实体调度来接管
                    this.scheduled = true;
                    schedule = true;
                }
            }
        }

        // 注销完成与调度器调用都在锁外执行
        if (retireNow) {
            this.completeRetired(List.of(command));
        } else if (runNow) {
            this.drain();
        } else if (schedule) {
            try {
                // Paper 对已退役实体的调度直接返回 null, run 与 retired 回调都不会被调用, 拒绝必须由提交方转成注销.
                if (this.scheduler.entity().run(this.player, this::runScheduled, this::retire) == null) {
                    this.retire();
                }
            } catch (Throwable throwable) {
                this.fail(throwable);
            }
        }
        return command.stage();
    }

    /**
     * 注销命令通道: 之后不再接收新命令, 注销回调与尚未执行的命令都按 retired 路径收尾.
     * <p>注销可以来自任意线程(过期通道被重连后的新 Player 顶掉时尤其如此), 因此收尾同样走 {@link #terminate}
     * 的执行权交接, 不会落在正在执行的命令中间.
     */
    void retire() {
        this.terminate(() -> this.retiredHandler.accept(this));
    }

    /**
     * 关停通道并交出最后一条收尾命令, 之后不再接收新命令.
     * <p>通道空闲时由当前线程接管执行权后直接执行收尾, 正在别处 drain 时把收尾交给那条线程在退出前执行,
     * 两条走向都保证收尾不与通道里的命令并发. 通道已经关停时不再收尾, 先到的那次已经接手.
     * <p>本方法不触发注销回调, 需要它的调用方自己放进 teardown.
     *
     * @param teardown 收尾命令
     */
    void terminate(@NotNull Runnable teardown) {
        List<Command<?>> pending;
        synchronized (this) {
            if (this.retired) {
                return;
            }
            this.retired = true;
            this.scheduled = false;
            if (this.draining) {
                // 正在执行命令的那条线程才是这名玩家的执行者, 收尾排在它后面
                this.terminal = teardown;
                return;
            }
            this.draining = true;
            pending = this.takePending();
        }
        this.runTerminal(teardown);
        this.completeRetired(pending);
    }

    // 执行收尾命令并交还 drain 权.
    private void runTerminal(@NotNull Runnable teardown) {
        try {
            teardown.run();
        } finally {
            synchronized (this) {
                this.draining = false;
            }
        }
    }

    /**
     * 在异步线程按注销路径完成给定命令.
     *
     * @param pending 要完成的命令
     */
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
     * 实体调度提交失败时注销通道, 并以异常完成所有待执行命令.
     *
     * @param failure 调度失败原因
     */
    void fail(@NotNull Throwable failure) {
        List<Command<?>> pending;
        synchronized (this) {
            // 已注销时失败不再需要传播
            if (this.retired) {
                return;
            }
            this.retired = true;
            this.scheduled = false;
            pending = this.takePending();
        }
        // 统一转到异步线程完成异常, 避免在调度器调用栈里触发调用方回调
        this.retiredHandler.accept(this);
        this.scheduler.async().runNow(() -> {
            for (int index = 0; index < pending.size(); index++) {
                pending.get(index).fail(failure);
            }
        });
    }

    /**
     * 实体调度回调: 接管 drain 权并开始执行队列中的命令.
     */
    private void runScheduled() {
        synchronized (this) {
            this.scheduled = false;
            // 等待调度期间通道已退役或已被内联 drain 接管, 无需再执行
            if (this.retired || this.draining) {
                return;
            }
            this.draining = true;
        }
        this.drain();
    }

    /**
     * 依次执行已入队命令, 并在注销竞态中把剩余命令转交给 retired 完成器.
     * <p>关停留下的收尾命令由本方法在交还 drain 权前执行.
     */
    private void drain() {
        while (true) {
            Command<?> command;
            Runnable terminal;
            List<Command<?>> retiredCommands = List.of();
            synchronized (this) {
                // 注销竞态, 收走剩余命令并释放 drain 权, 交给注销路径完成
                if (this.retired) {
                    retiredCommands = this.takePending();
                    terminal = this.terminal;
                    this.terminal = null;
                    // 收尾命令要在 drain 权内执行, 那时由 runTerminal 交还
                    this.draining = terminal != null;
                    command = null;
                } else {
                    terminal = null;
                    command = this.commands.pollFirst();
                    if (command == null) {
                        this.draining = false;
                    }
                }
            }

            if (terminal != null) {
                this.runTerminal(terminal);
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

    /**
     * 取出并清空全部待执行命令, 只能在锁内调用.
     *
     * @return 待执行命令的不可变快照
     */
    private List<Command<?>> takePending() {
        if (this.commands.isEmpty()) {
            return List.of();
        }
        ArrayList<Command<?>> pending = new ArrayList<>(this.commands);
        this.commands.clear();
        return List.copyOf(pending);
    }

    // 队列中的一次命令
    private static final class Command<T> {
        private final Callable<T> action; // 正常命令
        private final Callable<T> retiredAction; // 实体不可用后的替代命令
        private final CompletableFuture<T> completion = new CompletableFuture<>(); // 命令结果

        private Command(Callable<T> action, Callable<T> retiredAction) {
            this.action = action;
            this.retiredAction = retiredAction;
        }

        // 返回命令结果的只读完成阶段.
        private CompletionStage<T> stage() {
            return this.completion.minimalCompletionStage();
        }

        // 按正常命令执行并完成结果
        private void run() {
            this.complete(this.action);
        }

        // 按实体不可用路径执行并完成结果
        private void retire() {
            this.complete(this.retiredAction);
        }

        // 以异常完成命令结果
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
