package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@ApiStatus.Internal
public record ResolvedVisual(
        @NotNull Object sourceKey,                  // 产出本结果的层配置对象, 同一层不变时保持稳定, 渲染层据此判断来源换没换
        @NotNull ItemProvider provider,
        @Nullable ImmediateItemProvider placeholder // 异步提供器首次成功结果前显示的占位, null 表示回退到调用方给的内容
) {
    public ResolvedVisual {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(provider, "provider");
    }

    // 以 provider 自身作为来源身份, 无占位.
    @NotNull
    public static ResolvedVisual of(@NotNull ItemProvider provider) {
        return new ResolvedVisual(provider, provider, null);
    }
}
