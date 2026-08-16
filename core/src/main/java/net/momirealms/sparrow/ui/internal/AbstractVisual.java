package net.momirealms.sparrow.ui.internal;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.Visual;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 固定 Visual 的 Signal 绑定所有权, 具体视觉范围只需定义如何标脏.
 */
@ApiStatus.Internal
public abstract class AbstractVisual implements Visual {
    private final SignalBindings signalBindings;

    protected AbstractVisual(@NotNull SignalBindings signalBindings) {
        this.signalBindings = signalBindings;
    }

    @NotNull
    @Override
    public final Subscription bind(@NotNull Signal<?> signal) {
        return this.signalBindings.add(signal.onDirty(this::dirty));
    }
}
