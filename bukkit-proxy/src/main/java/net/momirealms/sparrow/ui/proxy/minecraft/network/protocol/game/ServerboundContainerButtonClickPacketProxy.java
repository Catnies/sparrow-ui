package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket")
public interface ServerboundContainerButtonClickPacketProxy extends PacketProxy {
    ServerboundContainerButtonClickPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerButtonClickPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket");

    @MethodInvoker(name = "containerId", activeIf = "min_version=1.20.5")
    int containerId(Object target);

    @MethodInvoker(name = "buttonId", activeIf = "min_version=1.20.5")
    int buttonId(Object target);
}
