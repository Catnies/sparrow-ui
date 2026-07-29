package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket")
public interface ClientboundPlaceGhostRecipePacketProxy extends PacketProxy {
    ClientboundPlaceGhostRecipePacketProxy INSTANCE = ASMProxyFactory.create(ClientboundPlaceGhostRecipePacketProxy.class);

    @ConstructorInvoker
    Object newInstance(
            int containerId,
            @Type(name = "net.minecraft.world.item.crafting.display.RecipeDisplay") Object recipeDisplay
    );
}
