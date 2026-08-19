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
     * 设置光标视觉映射, 返回 {@code null} 表示显示菜单实际光标.
     * <p><strong>映射收到的是本轮光标快照的实例, 不是副本, 一律不得修改</strong>.
     * 这份实例同时是下一轮"光标内容变没变"的比对基准, 改过之后每一轮都会判定为变过, 异步映射的结果因此被反复作废;
     * 它还会作为菜单光标进入 Bukkit 事件的 InventoryView, 污染点击语义读到的光标.
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
