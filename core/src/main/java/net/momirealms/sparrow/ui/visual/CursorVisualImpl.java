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
    private final AtomicBoolean pendingDirty = new AtomicBoolean(); // 标脏只置这个位, 由渲染那边取走并清零, 连着标几次合成一轮
    private volatile VisualLayer layer;                            // 光标视觉那一层配置, 改就整份换

    public CursorVisualImpl(@NotNull Bindings bindings, @NotNull VisualLayer layer) {
        super(bindings);
        this.layer = layer;
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.layer.visualizer();
    }

    // 先把层换掉再置失效位: 两次都是 volatile 写, 消费方看到失效位时一定看得到新层.
    @Override
    public void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    ) {
        if (this.layer.isSameVisualizerSamePlaceholder(visualizerProvider, placeholder)) return;
        this.layer = new VisualLayer(visualizerProvider, placeholder);
        this.dirty();
    }

    // 空光标按 null 交给映射, 与映射文档里"空光标为 null"那条对上
    @Nullable
    public ResolvedVisual visualize(@NotNull ItemStack actual) {
        return this.layer.visualize(actual.isEmpty() ? null : actual);
    }

    @Override
    public void dirty() {
        this.pendingDirty.set(true);
    }

    // 取走这个位并清零, 渲染那一轮只处理一次
    public boolean takeDirty() {
        return this.pendingDirty.getAndSet(false);
    }
}
