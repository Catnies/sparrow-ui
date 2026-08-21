package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@ApiStatus.Internal
public final class CursorVisualImpl extends AbstractVisual implements CursorVisual {
    private final AtomicBoolean pendingDirty = new AtomicBoolean(); // 置脏请求合并, 渲染线程用 takeDirty() 取走
    private volatile VisualLayer layer;

    public CursorVisualImpl(@NotNull Bindings bindings, @NotNull VisualLayer layer) {
        super(bindings);
        this.layer = layer;
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.layer.visualizer();
    }

    // 先换层再置失效位, 两次写都是 volatile 语义, 消费方看到失效位就一定看得到新层.
    @Override
    public void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    ) {
        if (this.layer.isSameVisualizerSamePlaceholder(visualizerProvider, placeholder)) return;
        this.layer = new VisualLayer(visualizerProvider, placeholder);
        this.dirty();
    }

    // 求值光标视觉映射, 空光标按 null 输入, 放行时返回 null 表示按菜单实际光标显示.
    @Nullable
    public ResolvedVisual visualize(@NotNull ItemStack actual) {
        return this.layer.visualize(actual.isEmpty() ? null : actual);
    }

    @Override
    public void dirty() {
        this.pendingDirty.set(true);
    }

    public boolean takeDirty() {
        return this.pendingDirty.getAndSet(false);
    }
}
