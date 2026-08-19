package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface CursorVisual extends Visual {

    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 替换光标视觉映射.
     * <p>映射本身在渲染线程求值, 只负责挑出这次要用哪个 ItemProvider , 重活放进返回的 ItemProvider 里.
     * 返回 {@code null} 表示显示菜单实际光标. ItemProvider 给出结果之前显示 {@code placeholder};
     * 没有给占位就显示菜单实际光标. ItemProvider 当场算得出结果时首帧就是真值, 用不到占位.
     * <p><strong>映射收到的是本轮光标快照的实例, 不是副本, 一律不得修改</strong>, 改它会污染下一轮"光标内容变没变"的比对.
     *
     * @param visualizerProvider 新的光标视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }
}
