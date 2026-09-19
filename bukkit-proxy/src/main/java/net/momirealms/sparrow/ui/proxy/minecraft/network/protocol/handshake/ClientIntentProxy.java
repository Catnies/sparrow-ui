package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.handshake;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.handshake.ClientIntent")
public interface ClientIntentProxy {
    ClientIntentProxy INSTANCE = ASMProxyFactory.create(ClientIntentProxy.class);

    @MethodInvoker(name = "id")
    int id(Object target);
}
