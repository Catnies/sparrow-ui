package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public enum PacketFlow {
    SERVERBOUND,
    CLIENTBOUND;
    
    @NotNull
    public PacketFlow opposite() {
        return this == SERVERBOUND ? CLIENTBOUND : SERVERBOUND;
    }
}
