package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.nyana.reflection.clazz.NyanaClass;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundBundlePacket")
public interface ClientboundBundlePacketProxy extends PacketProxy {
    ClientboundBundlePacketProxy INSTANCE = ASMProxyFactory.create(ClientboundBundlePacketProxy.class);
    Class<?> CLASS = NyanaClass.find("net.minecraft.network.protocol.game.ClientboundBundlePacket");

    @ConstructorInvoker
    Object newInstance(Iterable<?> packets);
}
