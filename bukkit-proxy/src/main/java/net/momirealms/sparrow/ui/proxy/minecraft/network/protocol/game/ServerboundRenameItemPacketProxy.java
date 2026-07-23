package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ServerboundRenameItemPacket")
public interface ServerboundRenameItemPacketProxy extends PacketProxy {
    ServerboundRenameItemPacketProxy INSTANCE = ASMProxyFactory.create(ServerboundRenameItemPacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ServerboundRenameItemPacket");

    @MethodInvoker(name = "getName")
    String name(Object target);
}
