package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

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
    Object getItem(Object target);
}
