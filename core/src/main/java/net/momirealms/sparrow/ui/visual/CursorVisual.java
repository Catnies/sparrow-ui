package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

public interface CursorVisual extends Visual {

    /**
     * 返回最近一次已经应用的光标视觉 Provider 映射.
     *
     * @return 光标视觉 Provider 映射
     */
    @NotNull
    Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider();

    /**
     * 请求替换光标视觉 Provider 映射.
     *
     * @param visualizerProvider 新的光标视觉 Provider 映射
     */
    void visualizerProvider(@NotNull Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider);
    /**
     * 返回当前光标异步视觉映射.
     *
     * @return 光标异步视觉映射; 没有设置过或当前是同步映射时为 {@code null}
     */
    @Nullable
    Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync();

    /**
     * 请求替换光标异步视觉映射.
     * <p>映射本身在渲染线程求值, 只负责挑出这次要用哪个提供器, 重活放进返回的提供器里.
     * 返回 {@code null} 表示显示菜单实际光标. 异步结果未完成前显示 {@code placeholder};
     * 没有给占位就显示菜单实际光标.
     *
     * @param visualizerAsync 新的光标异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    void visualizerAsync(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @Nullable ImmediateItemProvider placeholder
    );

    /**
     * 请求替换光标异步视觉映射, 未完成时显示菜单实际光标.
     *
     * @param visualizerAsync 新的光标异步视觉映射, {@code null} 表示移除这一层
     */
    default void visualizerAsync(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync) {
        this.visualizerAsync(visualizerAsync, (ImmediateItemProvider) null);
    }

    /**
     * 请求替换光标异步视觉映射, 未完成时显示固定占位物品.
     *
     * @param visualizerAsync 新的光标异步视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位物品
     */
    default void visualizerAsync(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @NotNull ItemStack placeholder
    ) {
        this.visualizerAsync(visualizerAsync, ItemProvider.constant(placeholder));
    }
    /**
     * 使用直接返回 ItemStack 的映射请求替换光标视觉映射.
     * <p>映射返回 {@code null} 表示显示菜单实际光标.
     *
     * @param visualizer 新的光标物品映射
     */
    default void visualizerItem(@NotNull Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        Objects.requireNonNull(visualizer, "visualizer");
        this.visualizerProvider(actual -> {
            ItemStack visual = visualizer.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        });
    }
}
