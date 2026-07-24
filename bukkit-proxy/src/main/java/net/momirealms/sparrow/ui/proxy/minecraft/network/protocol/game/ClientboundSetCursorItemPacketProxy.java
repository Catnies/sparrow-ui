package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket")
public interface ClientboundSetCursorItemPacketProxy extends PacketProxy {
    ClientboundSetCursorItemPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundSetCursorItemPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(@Type(clazz = ItemStackProxy.class) Object item);
}
