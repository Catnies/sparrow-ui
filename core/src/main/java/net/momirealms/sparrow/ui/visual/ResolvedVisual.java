package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

// 视觉层叠求值出来的那一份, 交到渲染层手上: 命中层的来源身份, 本轮 provider 和占位.
// 记录创建后不再改, 所以同一份结果可以同时挂在多个槽位上.
@ApiStatus.Internal
public record ResolvedVisual(
        @NotNull Object sourceKey,                  // 来源身份: 同一份配置始终给同一个对象, 渲染层据此决定异步结果能不能复用
        @NotNull ItemProvider provider,             // 这一层命中的提供器
        @Nullable ImmediateItemProvider placeholder // 首次成功结果前的占位, null 表示回退到调用方给的内容
) {
    public ResolvedVisual {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(provider, "provider");
    }

    // 来源身份就用 provider 自己, 也不带占位; 写死的映射走这条.
    @NotNull
    public static ResolvedVisual of(@NotNull ItemProvider provider) {
        return new ResolvedVisual(provider, provider, null);
    }
}
