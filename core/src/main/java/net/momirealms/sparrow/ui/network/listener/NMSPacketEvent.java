package net.momirealms.sparrow.ui.network.listener;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Experimental
public final class NMSPacketEvent {
    private final Object packet;
    private @Nullable Object replacement;
    private boolean cancelled;

    NMSPacketEvent(@NotNull Object packet) {
        this.packet = packet;
    }

    @NotNull
    public Object packet() {
        return this.packet;
    }

    public void replacePacket(@NotNull Object replacement) {
        this.replacement = replacement;
    }

    public boolean usingReplacement() {
        return this.replacement != null;
    }

    @Nullable
    public Object replacement() {
        return this.replacement;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    public void cancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
