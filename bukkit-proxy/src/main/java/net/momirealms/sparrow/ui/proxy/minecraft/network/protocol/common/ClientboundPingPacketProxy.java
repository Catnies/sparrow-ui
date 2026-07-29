package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.common.ClientboundPingPacket")
public interface ClientboundPingPacketProxy extends PacketProxy {
    ClientboundPingPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundPingPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(int id);
}
