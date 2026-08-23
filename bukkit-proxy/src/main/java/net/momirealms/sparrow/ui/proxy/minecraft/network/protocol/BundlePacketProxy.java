package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.BundlePacket")
public interface BundlePacketProxy extends PacketProxy {
    BundlePacketProxy INSTANCE = ASMProxyFactory.create(BundlePacketProxy.class);

    @MethodInvoker(name = "subPackets", activeIf = "min_version=1.20.1")
    Iterable<?> subPackets(Object target);
}
