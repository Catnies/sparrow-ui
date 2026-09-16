package net.minecraft.network;

import net.minecraft.network.protocol.PacketType;

public interface ProtocolInfo {

    interface Details {
        void listPackets(PacketVisitor output);
        interface PacketVisitor {
            void accept(PacketType<?> type, int networkId);
        }
    }

    interface DetailsProvider {
        Details details();
    }
}
