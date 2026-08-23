package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket")
public interface ServerboundPlaceRecipePacketProxy extends PacketProxy {
    ServerboundPlaceRecipePacketProxy INSTANCE = ASMProxyFactory.create(ServerboundPlaceRecipePacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket");

    @MethodInvoker(name = "containerId", activeIf = "min_version=1.21.2")
    int containerId(Object target);

    @MethodInvoker(name = "recipe", activeIf = "min_version=1.21.2")
    Object recipe(Object target);

    @MethodInvoker(name = "useMaxItems", activeIf = "min_version=1.21.2")
    boolean useMaxItems(Object target);
}
