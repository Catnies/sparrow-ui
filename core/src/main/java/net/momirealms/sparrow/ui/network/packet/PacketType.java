package net.momirealms.sparrow.ui.network.packet;

import org.jetbrains.annotations.NotNull;

public record PacketType(@NotNull String name, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
}
