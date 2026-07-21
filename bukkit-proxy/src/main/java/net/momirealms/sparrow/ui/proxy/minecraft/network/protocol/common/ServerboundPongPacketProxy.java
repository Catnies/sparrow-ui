package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * 读取客户端 Pong 标识的数据包代理.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.common.ServerboundPongPacket")
public interface ServerboundPongPacketProxy extends PacketProxy {
    ServerboundPongPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundPongPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.common.ServerboundPongPacket");

    @MethodInvoker(name = "getId")
    int id(Object target);
}
