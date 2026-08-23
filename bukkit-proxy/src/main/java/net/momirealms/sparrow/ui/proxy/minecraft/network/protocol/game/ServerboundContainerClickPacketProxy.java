package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerClickPacket")
public interface ServerboundContainerClickPacketProxy extends PacketProxy {
    ServerboundContainerClickPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerClickPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");

    @MethodInvoker(name = "containerId", activeIf = "min_version=1.21.2")
    int containerId(Object target);

    @MethodInvoker(name = "stateId", activeIf = "min_version=1.21.2")
    int stateId(Object target);

    @MethodInvoker(name = "slotNum", activeIf = "min_version=1.21.2")
    short slotNum(Object target);

    @MethodInvoker(name = "buttonNum", activeIf = "min_version=1.21.2")
    byte buttonNum(Object target);

    @MethodInvoker(name = "containerInput", activeIf = "min_version=26.1")
    Enum<?> containerInput(Object target);

    @MethodInvoker(name = "clickType", activeIf = "min_version=1.21.2 && max_version=1.21.11")
    Enum<?> clickType(Object target);

    @MethodInvoker(name = "changedSlots", activeIf = "min_version=1.21.2")
    Int2ObjectMap<Object> changedSlots(Object target);

    @MethodInvoker(name = "carriedItem", activeIf = "min_version=1.21.2")
    Object carriedItem(Object target);
}
