package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.handshake.ClientIntentionPacket")
public interface ClientIntentionPacketProxy {
    ClientIntentionPacketProxy INSTANCE = ASMProxyFactory.create(ClientIntentionPacketProxy.class);

    @MethodInvoker(name = "intention")
    Object intention(Object target);
}
