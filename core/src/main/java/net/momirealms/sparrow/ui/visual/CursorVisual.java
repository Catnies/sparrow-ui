package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface CursorVisual extends Visual {

    /**
     * 当前的光标视觉映射.
     *
     * @return 光标视觉映射; 没设时为 null
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 换掉光标视觉映射, 映射返回 {@code null} 时显示菜单实际光标.
     * <p>映射收到的是菜单实际光标本轮的副本, 空光标给 {@code null}. 这份副本还要用于内容变更比较和
     * Bukkit 事件视图, <strong>只读, 不得修改</strong>.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 同 {@link #setVisualizerProvider(Function, ImmediateItemProvider)}, 不带占位.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 用直接返回 ItemStack 的映射当光标视觉.
     *
     * @param visualizer 新的光标物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }
}
