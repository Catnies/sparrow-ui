package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket")
public interface ClientboundContainerSetContentPacketProxy extends PacketProxy {
    ClientboundContainerSetContentPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundContainerSetContentPacketProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.5")
    Object newInstance(
            int containerId,
            int stateId,
            List<?> items,
            @Type(clazz = ItemStackProxy.class) Object carried
    );

    @ConstructorInvoker(activeIf = "min_version=1.21.4 && max_version=1.21.4")
    Object newInstance$0(
            int containerId,
            int stateId,
            @Type(name = "net.minecraft.core.NonNullList") Object items,
            @Type(clazz = ItemStackProxy.class) Object carried
    );

    @MethodInvoker(name = "getItems", activeIf = "min_version=1.21.4 && max_version=1.21.4")
    List<Object> getItems(Object target);

    @MethodInvoker(name = "getCarriedItem", activeIf = "min_version=1.21.4 && max_version=1.21.4")
    Object getCarriedItem(Object target);
}
