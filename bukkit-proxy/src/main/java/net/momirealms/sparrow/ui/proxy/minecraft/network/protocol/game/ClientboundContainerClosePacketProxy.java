package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerClosePacket")
public interface ClientboundContainerClosePacketProxy extends PacketProxy {
    ClientboundContainerClosePacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerClosePacketProxy.class);

    @ConstructorInvoker
    Object newInstance(int containerId);
}
