package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Window 光标的视觉覆盖配置.
 */
public interface CursorVisual extends Visual {

    /**
     * 返回当前光标视觉映射.
     *
     * @return 光标视觉映射, 未设置时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 设置光标视觉映射, 返回 {@code null} 表示显示菜单实际光标.
     * <p>映射收到菜单实际光标的本轮副本, 空光标以 {@code null} 表示.
     * 这份副本还用于内容变更比较和 Bukkit 事件视图, <strong>只读, 不得修改</strong>.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 设置不带占位的光标视觉映射.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射设置光标视觉.
     *
     * @param visualizer 新的光标物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }
}
