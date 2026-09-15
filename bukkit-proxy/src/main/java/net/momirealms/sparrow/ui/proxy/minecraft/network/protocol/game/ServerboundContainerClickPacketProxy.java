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

    @MethodInvoker(name = {"containerId", "getContainerId"}, activeIf = "min_version=1.21.2")
    int containerId(Object target);

    @MethodInvoker(name = {"stateId", "getStateId"}, activeIf = "min_version=1.21.2")
    int stateId(Object target);

    @MethodInvoker(name = "slotNum", activeIf = "min_version=1.21.5")
    short slotNum(Object target);

    @MethodInvoker(name = "buttonNum", activeIf = "min_version=1.21.5")
    byte buttonNum(Object target);

    @MethodInvoker(name = "getSlotNum", activeIf = "min_version=1.21.2 && max_version=1.21.4")
    int getSlotNum(Object target);

    @MethodInvoker(name = "getButtonNum", activeIf = "min_version=1.21.2 && max_version=1.21.4")
    int getButtonNum(Object target);

    @MethodInvoker(name = "containerInput", activeIf = "min_version=26.1")
    Enum<?> containerInput(Object target);

    @MethodInvoker(name = {"clickType", "getClickType"}, activeIf = "min_version=1.21.2 && max_version=1.21.11")
    Enum<?> clickType(Object target);

    @MethodInvoker(name = {"changedSlots", "getChangedSlots"}, activeIf = "min_version=1.21.2")
    Int2ObjectMap<Object> changedSlots(Object target);

    @MethodInvoker(name = {"carriedItem", "getCarriedItem"}, activeIf = "min_version=1.21.2")
    Object carriedItem(Object target);
}
