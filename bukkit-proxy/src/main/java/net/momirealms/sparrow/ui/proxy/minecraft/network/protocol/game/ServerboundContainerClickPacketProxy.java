package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

/**
 * 读取客户端容器点击及其预测状态的数据包代理.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundContainerClickPacket")
public interface ServerboundContainerClickPacketProxy extends PacketProxy {
    ServerboundContainerClickPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundContainerClickPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "stateId")
    int stateId(Object target);

    @MethodInvoker(name = "slotNum")
    short slot(Object target);

    @MethodInvoker(name = "buttonNum")
    byte button(Object target);

    @MethodInvoker(name = "containerInput")
    Enum<?> containerInput(Object target);

    @MethodInvoker(name = "changedSlots")
    Map<Integer, Object> changedSlots(Object target);

    @MethodInvoker(name = "carriedItem")
    Object carried(Object target);
}
