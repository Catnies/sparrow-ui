package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket")
public interface ClientboundUpdateRecipesPacketProxy extends PacketProxy {
    ClientboundUpdateRecipesPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundUpdateRecipesPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket");

    @ConstructorInvoker
    Object newInstance(
            Map<?, ?> itemSets,
            @Type(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet")
            Object stonecutterRecipes
    );
}
