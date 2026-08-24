package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@FunctionalInterface
public interface LazyItemProvider {

    /**
     * 启动这一次解析并返回结果阶段.
     * <p><strong>返回值不能为 {@code null}.</strong> Future 解析出 {@code null} 也视为失败.
     *
     * @return 本次解析的结果阶段
     */
    CompletableFuture<? extends ItemProvider> resolve();

    /**
     * 创建在 Paper 全局异步调度器上执行同步解析函数的来源.
     *
     * @param supplier 同步解析函数, 可返回 {@code null} 表示解析失败
     * @return 异步解析来源
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
                            future.completeExceptionally(throwable);
                        }
                    }
            );
            return future;
        };
    }
}
