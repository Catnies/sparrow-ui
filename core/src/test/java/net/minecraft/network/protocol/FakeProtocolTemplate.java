package net.minecraft.network.protocol;

import net.minecraft.network.ProtocolInfo;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;

import java.util.HashMap;
import java.util.Map;

public final class FakeProtocolTemplate implements ProtocolInfo.DetailsProvider, ProtocolInfo.Details {
    private static final Map<String, PacketType<?>> TYPES = new HashMap<>();

    private final PacketType<?>[] types;

    private FakeProtocolTemplate(PacketType<?>[] types) {
        this.types = types;
    }

    public static FakeProtocolTemplate of(PacketFlow flow, String... names) {
        PacketType<?>[] types = new PacketType<?>[names.length];
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            types[index] = TYPES.computeIfAbsent(flow + "/" + name, ignored -> new PacketType<>(name));
        }
        return new FakeProtocolTemplate(types);
    }

    @Override
    public ProtocolInfo.Details details() {
        return this;
    }

    @Override
    public void listPackets(ProtocolInfo.Details.PacketVisitor output) {
        for (int index = 0; index < this.types.length; index++) {
            output.accept(this.types[index], index);
        }
    }
}
