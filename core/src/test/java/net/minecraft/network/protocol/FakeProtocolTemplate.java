package net.minecraft.network.protocol;

import net.minecraft.network.ProtocolInfo;

public final class FakeProtocolTemplate implements ProtocolInfo.DetailsProvider, ProtocolInfo.Details {

    private final String[] names;
    private FakeProtocolTemplate(String[] names) {
        this.names = names;
    }

    public static FakeProtocolTemplate of(String... names) {
        return new FakeProtocolTemplate(names);
    }

    @Override
    public ProtocolInfo.Details details() {
        return this;
    }

    @Override
    public void listPackets(ProtocolInfo.Details.PacketVisitor output) {
        for (int index = 0; index < this.names.length; index++) {
            output.accept(new PacketType<>(this.names[index]), index);
        }
    }
}
