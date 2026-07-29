package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket")
public interface ServerboundContainerSlotStateChangedPacketProxy extends PacketProxy {
    ServerboundContainerSlotStateChangedPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerSlotStateChangedPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket");

    @MethodInvoker(name = "slotId")
    int slotId(Object target);

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "newState")
    boolean newState(Object target);
}
