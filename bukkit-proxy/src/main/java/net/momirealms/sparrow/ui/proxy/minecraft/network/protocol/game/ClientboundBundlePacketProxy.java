package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game;

import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.game.ClientboundBundlePacket")
public interface ClientboundBundlePacketProxy extends PacketProxy {
    ClientboundBundlePacketProxy INSTANCE = ASMProxyFactory.create(ClientboundBundlePacketProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.protocol.game.ClientboundBundlePacket");

    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Object newInstance(Iterable<?> packets);
}
