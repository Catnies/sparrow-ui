package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@FunctionalInterface
public interface ItemProvider {
    ImmediateItemProvider EMPTY = ItemProvider.sync(ignoredContext -> ItemUtils.copyOrEmpty(null));

    /**
     * 发起本次要显示物品的计算.
     * <p><strong>不得改动 Window、Pane、Inventory, 也不得额外请求刷新或同步.</strong>
     * <p>Future 及其成功结果都不得为 {@code null}; 空槽使用 {@link ItemStack#empty()}.
     *
     * @param context 当前渲染上下文
     * @return 本次渲染最终结果的 Future
     */
    @NotNull
    CompletableFuture<? extends ItemStack> provide(@NotNull RenderContext context);

    /**
     * 创建在调用线程立即完成的同步提供器.
     *
     * @param renderer 同步渲染函数
     * @return 同步提供器
     */
    @NotNull
    static ImmediateItemProvider sync(@NotNull Function<? super RenderContext, ? extends ItemStack> renderer) {
        Objects.requireNonNull(renderer, "renderer");
        return renderer::apply;
    }

    /**
     * 基于固定物品的渲染器.
     *
     * @param template 模板物品堆
     * @return 提供器
     */
    @NotNull
    static ImmediateItemProvider constant(@NotNull ItemStack template) {
        return new ItemWrapper(Objects.requireNonNull(template, "template"));
    }
}
