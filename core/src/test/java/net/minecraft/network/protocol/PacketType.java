package net.minecraft.network.protocol;

public record PacketType<T>(String name) {

    public Object id() {
        return this.name;
    }
}
