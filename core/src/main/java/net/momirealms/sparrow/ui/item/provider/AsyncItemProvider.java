package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@FunctionalInterface
public interface AsyncItemProvider {

    /**
     * 启动一次重算并返回 Future.
     * <strong>不得改动 Window、GUI、Inventory, 也不得额外请求刷新或同步.</strong>
     *
     * @param context 当前渲染上下文
     * @return 本次重算的结果阶段
     */
    CompletableFuture<? extends ItemStack> provide(@NotNull RenderContext context);

    /**
     * 把同步的渲染函数放到 Paper 全局异步调度器上执行.
     *
     * @param renderer 同步渲染函数, 可返回 {@code null} 表示这次重算失败
     * @return 把渲染函数提交到 Paper 全局异步调度器的提供器
     */
    @NotNull
    static AsyncItemProvider compute(@NotNull Function<? super RenderContext, ? extends ItemStack> renderer) {
        return context -> {
            CompletableFuture<ItemStack> future = new CompletableFuture<>();
            Bukkit.getAsyncScheduler().runNow(
                    SparrowUI.getInstance().getPlugin(),
                    ignoredTask -> {
                        try {
                            future.complete(renderer.apply(context));
                        } catch (Throwable throwable) {
                            // 渲染异常不能丢给调度器, 统一走 future 的异常通道
                            future.completeExceptionally(throwable);
                        }
                    }
            );
            return future;
        };
    }
}
