package net.momirealms.sparrow.ui.item.provider;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ImmediateItemProvider extends ItemProvider {

    /**
     * 当场算出本次要显示的物品.
     * <p>与 {@link ItemProvider#provide(RenderContext)} 受同一约束. 只读取上下文,
     * 不得改动 Window, Pane, Inventory, 也不得额外请求刷新或同步.
     *
     * @param context 当前渲染上下文
     * @return 本次要显示的物品, 空槽使用 {@link ItemStack#empty()}
     */
    @NotNull
    ItemStack provideImmediately(@NotNull RenderContext context);

    @Override
    @NotNull
    default CompletableFuture<? extends ItemStack> provide(@NotNull RenderContext context) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(this.provideImmediately(context), "rendered item"));
    }
}
