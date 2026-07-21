package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

/**
 * 创建和读取单槽位容器更新数据包的代理.
 */
@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket")
public interface ClientboundContainerSetSlotPacketProxy extends PacketProxy {
    ClientboundContainerSetSlotPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerSetSlotPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(
            int containerId,
            int stateId,
            int slot,
            @Type(clazz = ItemStackProxy.class) Object item
    );

    @MethodInvoker(name = "getItem")
    Object item(Object target);
}
