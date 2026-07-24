package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket")
public interface ServerboundContainerButtonClickPacketProxy extends PacketProxy {
    ServerboundContainerButtonClickPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerButtonClickPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket");

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "buttonId")
    int buttonId(Object target);
}
