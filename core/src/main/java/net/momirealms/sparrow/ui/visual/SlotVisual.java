package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface SlotVisual extends Visual {

    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider();

    /**
     * 替换全局视觉映射并标脏全部槽位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
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

    /**
     * 返回一个槽位的显式视觉映射.
     *
     * @param slot 宿主槽位
     * @return 逐槽视觉映射, 没有设置过时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int slot);

    /**
     * 替换一个槽位的视觉映射并只标脏该槽位.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示调用方给出的内容
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    void setVisualizerProvider(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(slot, visualizerProvider, null);
    }

    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(slot, VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 求值一个槽位的两层视觉映射.
     * <p>{@code actual} 由调用方提供, 渲染层用它避免重复读取, 映射按约定只读.
     */
    @Nullable
    @ApiStatus.Internal
    ResolvedVisual visualize(int slot, @Nullable ItemStack actual);

    /**
     * 挂一条这个槽位的视觉失效订阅.
     * <p>订阅表只弱持有回执, 调用方丢掉回执即等于退订.
     */
    @NotNull
    @ApiStatus.Internal
    Subscription attach(int slot, @NotNull Runnable invalidator);
}
