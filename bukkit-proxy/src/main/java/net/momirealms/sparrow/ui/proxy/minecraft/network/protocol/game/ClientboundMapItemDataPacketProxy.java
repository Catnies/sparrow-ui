package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapIdProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapPatchProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.Collection;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundMapItemDataPacket")
public interface ClientboundMapItemDataPacketProxy extends PacketProxy {
    ClientboundMapItemDataPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundMapItemDataPacketProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(clazz = MapIdProxy.class) Object mapId,
            byte scale,
            boolean locked,
            Collection<?> decorations,
            @Type(clazz = MapPatchProxy.class) Object colorPatch
    );
}
