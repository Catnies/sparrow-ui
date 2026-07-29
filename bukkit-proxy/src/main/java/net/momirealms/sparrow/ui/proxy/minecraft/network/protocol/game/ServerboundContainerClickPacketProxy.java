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

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "stateId")
    int stateId(Object target);

    @MethodInvoker(name = "slotNum")
    short slotNum(Object target);

    @MethodInvoker(name = "buttonNum")
    byte buttonNum(Object target);

    @MethodInvoker(name = "containerInput", activeIf = "min_version=26.1")
    Enum<?> containerInput(Object target);

    @MethodInvoker(name = "clickType", activeIf = "!min_version=26.1")
    Enum<?> clickType(Object target);

    @MethodInvoker(name = "changedSlots")
    Int2ObjectMap<Object> changedSlots(Object target);

    @MethodInvoker(name = "carriedItem")
    Object carriedItem(Object target);
}
