package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.SignalBindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

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

    @NotNull
    final SignalBindings signalBindings() {
        return this.signalBindings;
    }
}
