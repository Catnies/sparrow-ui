package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@FunctionalInterface
public interface LazyItemProvider {

    /**
     * 启动这一次解析并返回结果阶段.
     * <p>解析出 {@code null} 视为失败, 此时保留占位内容.
     *
     * @return 本次解析的结果阶段
     */
    CompletionStage<? extends ItemProvider> resolve();

    /**
     * 把同步的解析函数放到 Paper 全局异步调度器上执行.
     *
     * @param supplier 同步解析函数, 可返回 {@code null} 表示解析失败
     * @return 把解析函数提交到 Paper 全局异步调度器的提供器
     */
    @NotNull
    static LazyItemProvider compute(@NotNull Supplier<? extends ItemProvider> supplier) {
        return () -> {
            CompletableFuture<ItemProvider> future = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(
                    SparrowUI.getInstance().getPlugin(),
                    ignoredTask -> {
                        try {
                            future.complete(supplier.get());
                        } catch (Throwable throwable) {
                            // 解析异常不能丢给调度器, 统一走 future 的异常通道
                            future.completeExceptionally(throwable);
                        }
                    }
            );
            return future;
        };
    }
}
