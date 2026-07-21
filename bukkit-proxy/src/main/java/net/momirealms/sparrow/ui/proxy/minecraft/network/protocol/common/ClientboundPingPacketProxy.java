package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 创建客户端 Ping 数据包的代理.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.common.ClientboundPingPacket")
public interface ClientboundPingPacketProxy extends PacketProxy {
    ClientboundPingPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundPingPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(int id);
}
