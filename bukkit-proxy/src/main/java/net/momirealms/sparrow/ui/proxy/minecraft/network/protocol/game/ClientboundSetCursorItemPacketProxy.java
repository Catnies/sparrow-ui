package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket", activeIf = "min_version=1.21.2")
public interface ClientboundSetCursorItemPacketProxy extends PacketProxy {
    ClientboundSetCursorItemPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundSetCursorItemPacketProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.2")
    Object newInstance(@Type(clazz = ItemStackProxy.class) Object item);
}
