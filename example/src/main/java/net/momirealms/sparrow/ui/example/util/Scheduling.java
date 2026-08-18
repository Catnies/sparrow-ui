package net.momirealms.sparrow.ui.example.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class Scheduling {
    private Scheduling() {
    }

    /**
     * 在异步调度器中执行 work, 结果通过返回的 future 传递.
     * work 抛出的任意异常会使 future 以异常完成.
     *
     * @param plugin 调度任务所属插件
     * @param work 异步执行的任务
     * @param <T> 结果类型
     * @return 承载执行结果的 future
     */
    @NotNull
    public static <T> CompletableFuture<T> async(@NotNull Plugin plugin, @NotNull Supplier<? extends T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, ignoredTask -> {
            try {
                future.complete(work.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
