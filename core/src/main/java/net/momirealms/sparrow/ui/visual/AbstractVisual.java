package net.momirealms.sparrow.ui.visual;

import net.momirealms.sparrow.ui.Bindings;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public abstract class AbstractVisual implements Visual {
    private final Bindings bindings;

    protected AbstractVisual(@NotNull Bindings bindings) {
        this.bindings = bindings;
    }

    @NotNull
    @Override
    public final Subscription bind(@NotNull Signal<?> signal) {
        return this.bindings.bind(() -> signal.onDirty(this::dirty));
    }
}
