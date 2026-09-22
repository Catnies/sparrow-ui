package net.momirealms.sparrow.ui.example.util;

import net.momirealms.sparrow.ui.SparrowUI;
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
     * @param work 异步执行的任务
     * @param <T> 结果类型
     * @return 承载执行结果的 future
     */
    @NotNull
    public static <T> CompletableFuture<T> async(@NotNull Supplier<? extends T> work) {
        return CompletableFuture.supplyAsync(work::get, SparrowUI.getInstance().scheduler().async());
    }
}
