package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 一次视觉映射求值的结果.
 *
 * @param sourceKey 产出本结果的那一层配置对象, 同一层配置不变时它稳定不变, 渲染层据此判断来源换没换
 * @param provider 本次要显示的提供器
 * @param placeholder 异步提供器首次成功结果前显示的占位, {@code null} 表示回退到调用方给的内容
 */
@ApiStatus.Internal
public record ResolvedVisual(
        @NotNull Object sourceKey,
        @NotNull ItemProvider provider,
        @Nullable ImmediateItemProvider placeholder
) {
    public ResolvedVisual {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(provider, "provider");
    }

    /**
     * 以 ItemProvider 作为来源身份.
     *
     * @param provider 本次要显示的提供器
     * @return 视觉映射结果
     */
    @NotNull
    public static ResolvedVisual of(@NotNull ItemProvider provider) {
        return new ResolvedVisual(provider, provider, null);
    }
}
