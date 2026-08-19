package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

@ApiStatus.Internal
public record VisualLayer(
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer, // null 表示这一层没有配置, 求值时直接放行
        @Nullable ImmediateItemProvider placeholder // null 表示没有占位
) {
    public static final VisualLayer NONE = new VisualLayer(null, null);

    // 求值这一层, 没有配置或映射放行时返回 null, 命中时产出带占位的结果.
    @Nullable
    public ResolvedVisual visualize(@Nullable ItemStack actual) {
        if (this.visualizer == null) {
            return null;
        }
        ItemProvider mapped = this.visualizer.apply(actual);
        return mapped == null ? null : new ResolvedVisual(this, mapped, this.placeholder);
    }

    // 把直接产出 ItemStack 的映射包成视觉映射, 入参为 null 时原样返回.
    @Nullable
    public static Function<@Nullable ItemStack, @Nullable ItemProvider> itemVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer
    ) {
        if (visualizer == null) return null;
        return actual -> {
            ItemStack visual = visualizer.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        };
    }
}
