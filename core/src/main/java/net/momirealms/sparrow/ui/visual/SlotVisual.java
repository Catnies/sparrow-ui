package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 按槽位组织的视觉宿主, 提供全局层与逐槽层两层视觉映射.
 *
 * <p>两层从具体到一般求值: 逐槽层先来, 它放行才轮到全局层. 映射返回 {@code null} 表示本层放行.
 * 两层都放行时这个宿主不给出结果, 由调用方决定接下来看哪里.
 *
 * <p>映射本身在渲染线程求值, 只负责挑出这一槽要用哪个提供器, 重活放进返回的提供器里.
 * 提供器给出结果之前显示配置的占位; 没有给占位就显示调用方给出的内容.
 * 提供器当场算得出结果时首帧就是真值, 用不到占位.
 *
 * <p>映射的输入是这个显示位当前的同步可读内容, 没有内容时为 {@code null}.
 * 输入的具体含义与槽位坐标系都由宿主定义.
 */
public interface SlotVisual extends Visual {

    /**
     * 返回当前全局视觉映射.
     *
     * @return 全局视觉映射; 没有设置过时为 {@code null}
     */
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

    /**
     * 替换全局视觉映射, 提供器给出结果前显示调用方给出的内容.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换全局视觉映射并标脏全部槽位.
     * <p>映射返回 {@code null} 表示本层放行.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 返回一个槽位的显式视觉映射.
     *
     * @param slot 宿主槽位
     * @return 逐槽视觉映射; 没有设置过时为 {@code null}
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

    /**
     * 替换一个槽位的视觉映射, 提供器给出结果前显示调用方给出的内容.
     *
     * @param slot 宿主槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(slot, visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换一个槽位的视觉映射并只标脏该槽位.
     * <p>映射返回 {@code null} 表示本层放行; 返回空 ItemStack 表示覆盖为空视觉.
     *
     * @param slot 宿主槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号超出宿主范围时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(slot, VisualLayer.itemVisualizer(visualizer));
    }
}
