package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Visual;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 一个 Inventory 的视觉配置.
 */
public interface InventoryVisual extends Visual {

    /**
     * 返回当前全局视觉 Provider 映射.
     *
     * @return 全局视觉 Provider 映射; 没有设置过时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider();

    /**
     * 替换全局视觉 Provider 映射并标脏全部 Inventory 槽位.
     *
     * @param visualizerProvider 新的全局视觉 Provider 映射, {@code null} 表示移除这一层
     */
    void visualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider);

    /**
     * 使用直接返回 ItemStack 的映射替换全局视觉映射并标脏全部 Inventory 槽位.
     * <p>映射返回 {@code null} 表示本层放行.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示移除这一层
     */
    default void visualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visualizerProvider(providerVisualizer(visualizer));
    }

    /**
     * 返回一个 Inventory 槽位的显式视觉 Provider 映射.
     *
     * @param slot Inventory 槽位
     * @return 逐槽视觉 Provider 映射; 没有设置过时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider(int slot);

    /**
     * 替换一个 Inventory 槽位的视觉 Provider 映射并只标脏该槽位.
     *
     * @param slot Inventory 槽位
     * @param visualizerProvider 新的逐槽视觉 Provider 映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    void visualizerProvider(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider);

    /**
     * 使用直接返回 ItemStack 的映射替换一个 Inventory 槽位的视觉映射并只标脏该槽位.
     * <p>映射返回 {@code null} 表示本层放行; 返回空 ItemStack 表示覆盖为空视觉.
     *
     * @param slot Inventory 槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void visualizerItem(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visualizerProvider(slot, providerVisualizer(visualizer));
    }

    /**

    /**
     * 返回当前全局异步视觉映射.
     *
     * @return 全局异步视觉映射; 没有设置过或该层是同步映射时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync();

    /**
     * 替换全局异步视觉映射并标脏全部 Inventory 槽位.
     * <p>映射本身在渲染线程求值, 只负责挑出这一槽要用哪个提供器, 重活放进返回的提供器里.
     * 返回 {@code null} 表示本层放行. 异步结果未完成前显示 {@code placeholder};
     * 没有给占位就显示该槽真实内容. 槽位内容变化会作废尚未完成的计算与已完成的结果.
     * <p>同一层的同步映射会被这次设置取代.
     *
     * @param visualizerAsync 新的全局异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示真实内容
     */
    void visualizerAsync(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换全局异步视觉映射, 未完成时显示该槽真实内容.
     *
     * @param visualizerAsync 新的全局异步视觉映射, {@code null} 表示移除这一层
     */
    default void visualizerAsync(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync) {
        this.visualizerAsync(visualizerAsync, (ImmediateItemProvider) null);
    }

    /**
     * 替换全局异步视觉映射, 未完成时显示固定占位物品.
     *
     * @param visualizerAsync 新的全局异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位物品
     */
    default void visualizerAsync(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @NotNull ItemStack placeholder
    ) {
        this.visualizerAsync(visualizerAsync, ItemProvider.constant(placeholder));
    }

    /**
     * 返回一个 Inventory 槽位的异步视觉映射.
     *
     * @param slot Inventory 槽位
     * @return 逐槽异步视觉映射; 没有设置过或该层是同步映射时为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync(int slot);

    /**
     * 替换一个 Inventory 槽位的异步视觉映射并只标脏该槽位.
     * <p>约定与 {@link #visualizerAsync(Function, ImmediateItemProvider)} 相同.
     *
     * @param slot Inventory 槽位
     * @param visualizerAsync 新的逐槽异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示真实内容
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    void visualizerAsync(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 替换一个 Inventory 槽位的异步视觉映射, 未完成时显示该槽真实内容.
     *
     * @param slot Inventory 槽位
     * @param visualizerAsync 新的逐槽异步视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void visualizerAsync(int slot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync) {
        this.visualizerAsync(slot, visualizerAsync, (ImmediateItemProvider) null);
    }

    /**
     * 替换一个 Inventory 槽位的异步视觉映射, 未完成时显示固定占位物品.
     *
     * @param slot Inventory 槽位
     * @param visualizerAsync 新的逐槽异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位物品
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    default void visualizerAsync(
            int slot,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @NotNull ItemStack placeholder
    ) {
        this.visualizerAsync(slot, visualizerAsync, ItemProvider.constant(placeholder));
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

    @Nullable
    private static Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> providerVisualizer(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        if (visualizer == null) {
            return null;
        }
        return actual -> {
            ItemStack visual = visualizer.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        };
    }
}
