package net.momirealms.sparrow.ui.item.provider;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@FunctionalInterface
public interface ItemProvider {
    ImmediateItemProvider EMPTY = ItemProvider.sync(ignoredContext -> ItemStack.empty());

    /**
     * 发起本次要显示物品的计算.
     * <p><strong>不得改动 Window, Pane, Inventory, 也不得额外请求刷新或同步.</strong>
     * <p>Future 及其成功结果都不得为 {@code null}. 完成后的 ItemStack 由渲染层只读使用,
     * Provider 不应再修改它.
     *
     * @param context 当前渲染上下文
     * @return 本次渲染最终结果的 Future
     */
    @NotNull
    CompletableFuture<ItemStack> provide(@NotNull RenderContext context);

    /**
     * 创建在渲染调用线程立即完成的同步 Provider.
     *
     * @param renderer 同步渲染函数
     * @return 同步提供器
     */
    @NotNull
    static ImmediateItemProvider sync(@NotNull Function<RenderContext, ItemStack> renderer) {
        return renderer::apply;
    }

    /**
     * 创建固定显示同一物品的同步 Provider.
     * <p>模板会在创建时复制一次, 后续渲染复用这份只读副本.
     *
     * @param template 模板物品堆
     * @return 固定内容 Provider
     */
    @NotNull
    static ImmediateItemProvider constant(@NotNull ItemStack template) {
        return new ItemWrapper(template);
    }
}
