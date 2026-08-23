package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket")
public interface ClientboundContainerSetDataPacketProxy extends PacketProxy {
    ClientboundContainerSetDataPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerSetDataPacketProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Object newInstance(int containerId, int propertyId, int value);
}
