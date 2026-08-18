package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public interface InventoryVisual extends Visual {

    /**
     * 返回当前全局视觉映射.
     *
     * @return 全局视觉映射; 没有设置过时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizerProvider();

    /**
     * 替换全局视觉映射并标脏全部 Inventory 槽位.
     * <p>映射本身在渲染线程求值, 只负责挑出这一槽要用哪个提供器, 重活放进返回的提供器里.
     * 返回 {@code null} 表示本层放行. 提供器给出结果之前显示 {@code placeholder};
     * 没有给占位就显示该槽真实内容. 提供器当场算得出结果时首帧就是真值, 用不到占位.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示真实内容
     */
    void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换全局视觉映射, 提供器给出结果前显示该槽真实内容.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示移除这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换全局视觉映射并标脏全部 Inventory 槽位.
     * <p>映射返回 {@code null} 表示本层放行.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示移除这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 返回一个 Inventory 槽位的显式视觉映射.
     *
     * @param slot Inventory 槽位
     * @return 逐槽视觉映射; 没有设置过时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> getVisualizerProvider(int slot);

    /**
     * 替换一个 Inventory 槽位的视觉映射并只标脏该槽位.
     * <p>约定与 {@link #setVisualizerProvider(Function, ImmediateItemProvider)} 相同.
     *
     * @param slot Inventory 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示真实内容
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    void setVisualizerProvider(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换一个 Inventory 槽位的视觉映射, 提供器给出结果前显示该槽真实内容.
     *
     * @param slot Inventory 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void setVisualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.setVisualizerProvider(slot, visualizerProvider, null);
    }

    /**
     * 使用直接返回 ItemStack 的映射替换一个 Inventory 槽位的视觉映射并只标脏该槽位.
     * <p>映射返回 {@code null} 表示本层放行; 返回空 ItemStack 表示覆盖为空视觉.
     *
     * @param slot Inventory 槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void setVisualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.setVisualizerProvider(slot, VisualLayer.itemVisualizer(visualizer));
    }

    /**
     * 返回当前空槽背景.
     *
     * @return 空槽背景; 没有设置过时为 {@code null}
     */
    @Nullable
    ItemProvider background();

    /**
     * 替换空槽背景并标脏全部 Inventory 槽位.
     *
     * @param background 空槽背景, {@code null} 表示清除
     */
    void background(@Nullable ItemProvider background);

    /**
     * 使用 ItemStack 替换空槽背景并标脏全部 Inventory 槽位.
     *
     * @param background 空槽背景
     */
    default void backgroundItem(@NotNull ItemStack background) {
        this.background(ItemProvider.constant(background));
    }
}
