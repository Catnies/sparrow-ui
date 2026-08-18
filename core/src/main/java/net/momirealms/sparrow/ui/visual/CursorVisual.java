package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface CursorVisual extends Visual {

    /**
     * 返回最近一次已经应用的光标视觉映射.
     *
     * @return 光标视觉映射, 没有设置过时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 请求替换光标视觉映射.
     * <p>映射本身在渲染线程求值, 只负责挑出这次要用哪个提供器, 重活放进返回的提供器里.
     * 返回 {@code null} 表示显示菜单实际光标. 提供器给出结果之前显示 {@code placeholder};
     * 没有给占位就显示菜单实际光标. 提供器当场算得出结果时首帧就是真值, 用不到占位.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 请求替换光标视觉映射, 提供器给出结果前显示菜单实际光标.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射请求替换光标视觉映射.
     * <p>映射返回 {@code null} 表示显示菜单实际光标.
     *
     * @param visualizer 新的光标物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }
}
