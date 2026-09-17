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
        @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer,
        @Nullable ImmediateItemProvider placeholder
) {
    public static final VisualLayer NONE = new VisualLayer(null, null);

    public VisualLayer {
        if (visualizer == null) {
            placeholder = null;
        }
    }

    // null 表示这一层放行, 交给下一层.
    @Nullable
    public ResolvedVisual visualize(@Nullable ItemStack actual) {
        if (this.visualizer == null) {
            return null;
        }
        ItemProvider mapped = this.visualizer.apply(actual);
        return mapped == null ? null : new ResolvedVisual(this, mapped, this.placeholder);
    }

    // 配置没变就跳过重设, 免得白标脏一轮; 映射和占位都跟现在一样才算没变.
    public boolean isSameVisualizerSamePlaceholder(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizer,
            @Nullable ImmediateItemProvider placeholder
    ) {
        if (!sameVisualizer(this.visualizer, visualizer)) return false;
        return visualizer == null || this.placeholder == placeholder;
    }

    // ItemStack 映射每次设置都会新建一层适配器, 所以比身份要看里面的 delegate, 不然同一份映射会被当成两次不同的配置
    private static boolean sameVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> left,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> right
    ) {
        if (left == right) return true;
        return left instanceof ItemVisualizer(var leftDelegate)
                && right instanceof ItemVisualizer(var rightDelegate)
                && leftDelegate == rightDelegate;
    }

    // 把直接产出 ItemStack 的映射包成视觉映射; 传 null 就还 null, 表示不参与这一层.
    @Nullable
    public static Function<@Nullable ItemStack, @Nullable ItemProvider> itemVisualizer(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer
    ) {
        return visualizer == null ? null : new ItemVisualizer(visualizer);
    }

    // 用具名记录而不是匿名 lambda, 身份比较时才能摸到 delegate
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
