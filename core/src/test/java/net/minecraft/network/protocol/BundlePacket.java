package net.minecraft.network.protocol;

public abstract class BundlePacket implements Packet<Object> {
    private final Iterable<?> packets;

    protected BundlePacket(Iterable<?> packets) {
        this.packets = packets;
    }

    public Iterable<?> subPackets() {
        return this.packets;
    }
}
