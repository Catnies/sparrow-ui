package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.common.ServerboundPongPacket")
public interface ServerboundPongPacketProxy extends PacketProxy {
    ServerboundPongPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundPongPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.common.ServerboundPongPacket");

    @MethodInvoker(name = "getId")
    int id(Object target);
}
