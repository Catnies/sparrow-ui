package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.visual.AbstractVisual;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.visual.ResolvedVisual;
import net.momirealms.sparrow.ui.visual.CursorVisual;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 保存一个 Window 的光标映射, Signal 绑定与跨线程失效位.
 */
final class CursorVisualImpl extends AbstractVisual implements CursorVisual {
    private final Consumer<Runnable> commandSubmitter;
    private final AtomicBoolean pendingDirty = new AtomicBoolean();
    private volatile VisualLayer layer;

    CursorVisualImpl(
            @NotNull SignalBindings signalBindings,
            @NotNull VisualLayer layer,
            @NotNull Consumer<Runnable> commandSubmitter
    ) {
        super(signalBindings);
        this.layer = layer;
        this.commandSubmitter = commandSubmitter;
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.layer.visualizer();
    }

    @Override
    public void setVisualizerProvider(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @Nullable ImmediateItemProvider placeholder
    ) {
        VisualLayer newLayer = new VisualLayer(visualizerProvider, placeholder);
        this.commandSubmitter.accept(() -> {
            this.layer = newLayer;
            this.dirty();
        });
    }

    /**
     * 求值光标视觉映射. 喂给映射的输入在此处复制; 空光标以 {@code null} 输入.
     *
     * @param actual 菜单实际光标
     * @return 求值结果; 没有配置或放行时为 {@code null}, 表示按菜单实际光标显示
     */
    @Nullable
    ResolvedVisual visualize(@NotNull ItemStack actual) {
        return this.layer.visualize(actual.isEmpty() ? null : actual);
    }

    @Override
    public void dirty() {
        this.pendingDirty.set(true);
    }

    boolean takeDirty() {
        return this.pendingDirty.getAndSet(false);
    }
}
