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
    private final Runnable retiredHandler; // 通道注销后在异步线程执行的收尾操作
    private final ArrayDeque<Command<?>> commands = new ArrayDeque<>(); // 待执行命令队列, 仅在锁内访问

    private boolean scheduled; // 是否已有实体调度任务待运行, 仅在锁内访问
    private boolean draining;  // 是否有 drain 正在执行, 仅在锁内访问
    private boolean retired;   // 通道是否已注销, 注销后新命令直接走注销路径

    /**
     * 为指定玩家创建命令通道.
     *
     * @param player 通道服务的玩家
     * @param scheduler Window 调度入口
     * @param retiredHandler 通道退役后在异步线程执行的收尾操作
     */
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
    @NotNull
    <T> CompletionStage<T> submit(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
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
    @NotNull
    <T> CompletionStage<T> submitDeferred(@NotNull Callable<T> action, @NotNull Callable<T> retiredAction) {
        return this.submit(action, retiredAction, false);
    }

    /**
     * 在锁内决定命令是立即 drain、安排实体调度还是按注销路径完成.
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
                this.scheduler.entity().run(this.player, this::runScheduled, this::retire);
            } catch (Throwable throwable) {
                this.fail(throwable);
            }
        }
        return command.stage();
    }

    /**
     * 注销命令通道并按 retired 路径完成所有尚未执行的命令.
     */
    void retire() {
        List<Command<?>> pending;
        synchronized (this) {
            // 重复注销直接忽略
            if (this.retired) {
                return;
            }
            this.retired = true;
            this.scheduled = false;
            pending = this.takePending();
        }
        // 待执行命令按注销路径完成, 收尾操作转到异步线程
        this.completeRetired(pending);
        this.scheduler.async().runNow(this.retiredHandler);
    }

    /**
     * 实体调度提交失败时注销通道, 并以异常完成所有待执行命令.
     *
     * @param failure 调度失败原因
     */
    private void fail(Throwable failure) {
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
        this.scheduler.async().runNow(() -> {
            for (int index = 0; index < pending.size(); index++) {
                pending.get(index).fail(failure);
            }
        });
        this.scheduler.async().runNow(this.retiredHandler);
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
     */
    private void drain() {
        while (true) {
            Command<?> command;
            List<Command<?>> retiredCommands = List.of();
            synchronized (this) {
                // 注销竞态: 收走剩余命令并释放 drain 权, 交给注销路径完成
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

    /**
     * 在异步线程按注销路径完成给定命令.
     *
     * @param pending 要完成的命令
     */
    private void completeRetired(List<Command<?>> pending) {
        if (pending.isEmpty()) return;
        this.scheduler.async().runNow(() -> {
            for (int index = 0; index < pending.size(); index++) {
                pending.get(index).retire();
            }
        });
    }

    /**
     * 队列中的一次命令及其正常、注销两种完成路径.
     */
    private static final class Command<T> {
        private final Callable<T> action; // 正常执行路径
        private final Callable<T> retiredAction; // 实体不可用后的替代路径
        private final CompletableFuture<T> completion = new CompletableFuture<>(); // 命令结果

        /**
         * 创建命令.
         *
         * @param action 正常执行路径
         * @param retiredAction 实体退役后的替代路径
         */
        private Command(Callable<T> action, Callable<T> retiredAction) {
            this.action = action;
            this.retiredAction = retiredAction;
        }

        /**
         * 返回命令结果的只读完成阶段.
         *
         * @return 完成阶段
         */
        private CompletionStage<T> stage() {
            return this.completion.minimalCompletionStage();
        }

        /**
         * 按正常路径执行并完成结果.
         */
        private void run() {
            this.complete(this.action);
        }

        /**
         * 按退役路径执行并完成结果.
         */
        private void retire() {
            this.complete(this.retiredAction);
        }

        /**
         * 以异常完成命令结果.
         *
         * @param throwable 失败原因
         */
        private void fail(Throwable throwable) {
            this.completion.completeExceptionally(throwable);
        }

        /**
         * 执行给定路径并把结果写入完成阶段.
         *
         * @param callable 要执行的操作
         */
        private void complete(Callable<T> callable) {
            try {
                this.completion.complete(callable.call());
            } catch (Throwable throwable) {
                // 操作失败只影响该命令的完成阶段, 不影响通道中其他命令
                this.completion.completeExceptionally(throwable);
            }
        }
    }
}
