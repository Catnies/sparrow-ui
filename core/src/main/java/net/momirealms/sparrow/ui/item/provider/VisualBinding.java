package net.momirealms.sparrow.ui.item.provider;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 一次视觉映射求值的结果, 交给渲染层直接装配成渲染意图.
 *
 * @param sourceKey 产出本结果的那一层配置对象; 同一层配置不变时它稳定不变, 渲染层据此判断来源换没换
 * @param provider 本次要显示的提供器
 * @param placeholder 异步提供器首次成功结果前显示的占位, {@code null} 表示回退到调用方给的内容
 */
@ApiStatus.Internal
public record VisualBinding(
        @NotNull Object sourceKey,
        @NotNull ItemProvider provider,
        @Nullable ImmediateItemProvider placeholder
) {
    public VisualBinding {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(provider, "provider");
    }

    /**
     * 以提供器自身作为来源身份: 提供器实例本就稳定的层直接用它当身份.
     *
     * @param provider 本次要显示的提供器
     * @return 视觉映射结果
     */
    @NotNull
    public static VisualBinding of(@NotNull ItemProvider provider) {
        return new VisualBinding(provider, provider, null);
    }
}
