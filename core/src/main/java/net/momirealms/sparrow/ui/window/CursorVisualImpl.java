package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.internal.AbstractVisual;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.VisualBinding;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 保存一个 Window 的光标映射, Signal 绑定与跨线程失效位.
 */
final class CursorVisualImpl extends AbstractVisual implements CursorVisual {
    private final Consumer<Runnable> commandSubmitter;
    private final AtomicBoolean pendingDirty = new AtomicBoolean();
    private volatile Layer layer;

    CursorVisualImpl(
            @NotNull SignalBindings signalBindings,
            @NotNull Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider,
            @NotNull Consumer<Runnable> commandSubmitter
    ) {
        super(signalBindings);
        this.layer = new Layer(visualizerProvider, null, null);
        this.commandSubmitter = commandSubmitter;
    }

    @NotNull
    @Override
    public Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider() {
        Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> sync = this.layer.sync;
        return sync == null ? ignoredCursor -> null : sync;
    }

    @Override
    public void visualizerProvider(@NotNull Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> visualizerProvider) {
        Objects.requireNonNull(visualizerProvider, "visualizerProvider");
        Layer newLayer = new Layer(visualizerProvider, null, null);
        this.commandSubmitter.accept(() -> {
            this.layer = newLayer;
            this.dirty();
        });
    }

    @Nullable
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync() {
        return this.layer.async;
    }

    @Override
    public void visualizerAsync(
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerAsync,
            @Nullable ImmediateItemProvider placeholder
    ) {
        Layer newLayer = new Layer(null, visualizerAsync, placeholder);
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
    VisualBinding visualize(@NotNull ItemStack actual) {
        return this.layer.visualize(actual.isEmpty() ? null : actual.clone());
    }

    @Override
    public void dirty() {
        this.pendingDirty.set(true);
    }

    boolean takeDirty() {
        return this.pendingDirty.getAndSet(false);
    }

    /**
     * 光标的一层视觉配置. 同步与异步互斥, 后设置的那种取代前一种.
     * <p>本记录一经设置就不再变化, 因此渲染层可以拿它自己当来源身份.
     */
    private record Layer(
            @Nullable Function<@Nullable ItemStack, @Nullable ImmediateItemProvider> sync,
            @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> async,
            @Nullable ImmediateItemProvider asyncPlaceholder
    ) {
        @Nullable
        private VisualBinding visualize(@Nullable ItemStack actual) {
            if (this.async != null) {
                ItemProvider mapped = this.async.apply(actual);
                return mapped == null ? null : new VisualBinding(this, mapped, this.asyncPlaceholder);
            }
            if (this.sync != null) {
                ImmediateItemProvider mapped = this.sync.apply(actual);
                return mapped == null ? null : VisualBinding.of(mapped);
            }
            return null;
        }
    }
}
