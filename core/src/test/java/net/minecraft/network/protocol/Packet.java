package net.minecraft.network.protocol;

public interface Packet<T> {
    PacketType<? extends Packet<T>> type();
}
