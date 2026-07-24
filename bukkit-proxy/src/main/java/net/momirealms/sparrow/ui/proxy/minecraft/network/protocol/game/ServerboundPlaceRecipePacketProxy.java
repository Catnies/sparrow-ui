package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket")
public interface ServerboundPlaceRecipePacketProxy extends PacketProxy {
    ServerboundPlaceRecipePacketProxy INSTANCE = ASMProxyFactory.create(ServerboundPlaceRecipePacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket");

    @MethodInvoker(name = "containerId")
    int containerId(Object target);

    @MethodInvoker(name = "recipe")
    Object recipe(Object target);

    @MethodInvoker(name = "useMaxItems")
    boolean useMaxItems(Object target);
}
