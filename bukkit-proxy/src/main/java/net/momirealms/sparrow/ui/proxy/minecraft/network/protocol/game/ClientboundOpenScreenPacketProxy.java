package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundOpenScreenPacket")
public interface ClientboundOpenScreenPacketProxy extends PacketProxy {
    ClientboundOpenScreenPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundOpenScreenPacketProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Object newInstance(
            int containerId,
            @Type(clazz = MenuTypeProxy.class) Object menuType,
            @Type(clazz = ComponentProxy.class) Object title
    );
}
