package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

@ApiStatus.Internal
public record VisualLayer(
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer, // null 表示这一层没有配置, 求值时直接放行
        @Nullable ImmediateItemProvider placeholder                                 // null 表示没有占位
) {
    public static final VisualLayer NONE = new VisualLayer(null, null);

    public VisualLayer {
        if (visualizer == null) {
            placeholder = null;
        }
    }

    // 求值这一层, 没有配置或映射放行时返回 null, 命中时产出带占位的结果.
    @Nullable
    public ResolvedVisual visualize(@Nullable ItemStack actual) {
        if (this.visualizer == null) {
            return null;
        }
        ItemProvider mapped = this.visualizer.apply(actual);
        return mapped == null ? null : new ResolvedVisual(this, mapped, this.placeholder);
    }

    // 判断是否和当前配置一致, 用来跳过不改变任何东西的重设.
    public boolean isSameVisualizerSamePlaceholder(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer,
            @Nullable ImmediateItemProvider placeholder
    ) {
        if (!sameVisualizer(this.visualizer, visualizer)) return false;
        return visualizer == null || this.placeholder == placeholder;
    }

    // 同一个映射算同一份配置: 直接 Item 映射每次都要新包一层适配, 比到适配里面才认得出来.
    private static boolean sameVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> left,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> right
    ) {
        if (left == right) return true;
        return left instanceof ItemVisualizer(var leftDelegate)
                && right instanceof ItemVisualizer(var rightDelegate)
                && leftDelegate == rightDelegate;
    }

    // 把直接产出 ItemStack 的映射包成视觉映射, 入参为 null 时原样返回.
    @Nullable
    public static Function<@Nullable ItemStack, @Nullable ItemProvider> itemVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer
    ) {
        return visualizer == null ? null : new ItemVisualizer(visualizer);
    }

    // 直接 Item 映射的适配. 写成具名类型而不是 lambda, 重设同一个映射才认得出还是同一份配置.
    private record ItemVisualizer(
            @NotNull Function<@Nullable ItemStack, @Nullable ItemStack> delegate
    ) implements Function<@Nullable ItemStack, @Nullable ItemProvider> {

        @Nullable
        @Override
        public ItemProvider apply(@Nullable ItemStack actual) {
            ItemStack visual = this.delegate.apply(actual);
            return visual == null ? null : ItemProvider.constant(visual);
        }
    }
}
