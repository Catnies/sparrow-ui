package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.BundlePacket")
public interface BundlePacketProxy extends PacketProxy {
    BundlePacketProxy INSTANCE = ASMProxyFactory.create(BundlePacketProxy.class);

    @MethodInvoker(name = "subPackets")
    Iterable<?> subPackets(Object target);
}
