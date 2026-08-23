package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.common.ClientboundPingPacket", activeIf = "min_version=1.20.2")
public interface ClientboundPingPacketProxy extends PacketProxy {
    ClientboundPingPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundPingPacketProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.20.2")
    Object newInstance(int id);
}
