package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket")
public interface ClientboundContainerSetContentPacketProxy extends PacketProxy {
    ClientboundContainerSetContentPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerSetContentPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(
            int containerId,
            int stateId,
            List<?> items,
            @Type(clazz = ItemStackProxy.class) Object carried
    );
}
