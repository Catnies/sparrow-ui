package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket")
public interface ClientboundContainerSetDataPacketProxy extends PacketProxy {
    ClientboundContainerSetDataPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerSetDataPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(int containerId, int propertyId, int value);
}
