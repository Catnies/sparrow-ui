package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.internal.AbstractVisual;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
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
    private volatile Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider;

    CursorVisualImpl(
            @NotNull SignalBindings signalBindings,
            @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
            @NotNull Consumer<Runnable> commandSubmitter
    ) {
        super(signalBindings);
        this.visualizerProvider = visualizerProvider;
        this.commandSubmitter = commandSubmitter;
    }

    @Override
    public void dirty() {
        this.pendingDirty.set(true);
    }

    @NotNull
    @Override
    public Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.visualizerProvider;
    }

    @Override
    public void visualizerProvider(@NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        Objects.requireNonNull(visualizerProvider, "visualizerProvider");
        this.commandSubmitter.accept(() -> {
            this.visualizerProvider = visualizerProvider;
            this.dirty();
        });
    }

    boolean takeDirty() {
        return this.pendingDirty.getAndSet(false);
    }
}
