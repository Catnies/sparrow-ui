package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket")
public interface ServerboundContainerSlotStateChangedPacketProxy extends PacketProxy {
    ServerboundContainerSlotStateChangedPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerSlotStateChangedPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket");

    @MethodInvoker(name = "slotId")
    int slotId(Object target);

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "newState")
    boolean newState(Object target);
}
