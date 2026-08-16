package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Visual;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * 一个 Window 的光标视觉配置.
 */
public interface CursorVisual extends Visual {

    /**
     * 返回最近一次已经应用的光标视觉 Provider 映射.
     *
     * @return 光标视觉 Provider 映射
     */
    @NotNull
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 请求替换光标视觉 Provider 映射.
     *
     * @param visualizerProvider 新的光标视觉 Provider 映射
     */
    void visualizerProvider(@NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider);

    /**
     * 使用直接返回 ItemStack 的映射请求替换光标视觉映射.
     * <p>映射返回 {@code null} 表示显示菜单实际光标.
     *
     * @param visualizer 新的光标物品映射
     */
    default void visualizer(@NotNull Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        Objects.requireNonNull(visualizer, "visualizer");
        this.visualizerProvider(actual -> {
            ItemStack visual = visualizer.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        });
    }
}
