package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerClosePacket")
public interface ServerboundContainerClosePacketProxy extends PacketProxy {
    ServerboundContainerClosePacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerClosePacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundContainerClosePacket");

    @MethodInvoker(name = "getContainerId")
    int containerId(Object target);
}
