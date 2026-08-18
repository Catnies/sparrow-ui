package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 一层视觉配置, 一个视觉映射, 加上它算出的提供器首次成功前显示的占位.
 * <p>本记录一经设置就不再变化, 因此渲染层直接拿它自己当来源身份.
 *
 * @param visualizer 视觉映射, {@code null} 表示这一层没有配置, 求值时直接放行到下一层
 * @param placeholder 映射产出的提供器首次成功结果前显示的占位, {@code null} 表示没有
 */
@ApiStatus.Internal
public record VisualLayer(
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer,
        @Nullable ImmediateItemProvider placeholder
) {
    public static final VisualLayer NONE = new VisualLayer(null, null);

    /**
     * 求值这一层.
     *
     * @param actual 该位置当前真实内容, 空为 {@code null}
     * @return 求值结果; 这一层没有配置或映射放行时为 {@code null}
     */
    @Nullable
    public ResolvedVisual visualize(@Nullable ItemStack actual) {
        if (this.visualizer == null) {
            return null;
        }
        ItemProvider mapped = this.visualizer.apply(actual);
        return mapped == null ? null : new ResolvedVisual(this, mapped, this.placeholder);
    }

    /**
     * 把直接产出 ItemStack 的映射包成视觉映射.
     *
     * @param visualizer 物品映射, {@code null} 表示这一层没有配置
     * @return 对应的视觉映射, {@code null} 时原样返回
     */
    @Nullable
    public static Function<@Nullable ItemStack, @Nullable ItemProvider> itemVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer
    ) {
        if (visualizer == null) {
            return null;
        }
        return actual -> {
            ItemStack visual = visualizer.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        };
    }
}
