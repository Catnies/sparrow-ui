package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerClosePacket")
public interface ServerboundContainerClosePacketProxy extends PacketProxy {
    ServerboundContainerClosePacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerClosePacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundContainerClosePacket");

    @MethodInvoker(name = "getContainerId", activeIf = "min_version=1.20.1")
    int containerId(Object target);
}
