package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket")
public interface ClientboundUpdateRecipesPacketProxy extends PacketProxy {
    ClientboundUpdateRecipesPacketProxy INSTANCE = ASMProxyFactory.create(ClientboundUpdateRecipesPacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket");

    @ConstructorInvoker
    Object newInstance(
            Map<?, ?> itemSets,
            @Type(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet")
            Object stonecutterRecipes
    );
}
