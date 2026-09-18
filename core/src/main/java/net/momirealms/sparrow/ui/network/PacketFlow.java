package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.NotNull;

public enum PacketFlow {
    SERVERBOUND,
    CLIENTBOUND;
    
    @NotNull
    public PacketFlow opposite() {
        return this == SERVERBOUND ? CLIENTBOUND : SERVERBOUND;
    }
}
