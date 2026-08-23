package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket", activeIf = "min_version=1.20.2")
public interface ServerboundContainerSlotStateChangedPacketProxy extends PacketProxy {
    ServerboundContainerSlotStateChangedPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerSlotStateChangedPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket");

    @MethodInvoker(name = "slotId", activeIf = "min_version=1.20.2")
    int slotId(Object target);

    @MethodInvoker(name = "containerId", activeIf = "min_version=1.20.2")
    int containerId(Object target);

    @MethodInvoker(name = "newState", activeIf = "min_version=1.20.2")
    boolean newState(Object target);
}
