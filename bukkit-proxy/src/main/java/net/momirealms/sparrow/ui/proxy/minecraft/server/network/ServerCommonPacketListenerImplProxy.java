package net.momirealms.sparrow.ui.proxy.minecraft.server.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.network.ServerCommonPacketListenerImpl")
public interface ServerCommonPacketListenerImplProxy {
    ServerCommonPacketListenerImplProxy INSTANCE = ASMProxyFactory.create(ServerCommonPacketListenerImplProxy.class);

    @FieldGetter(name = "connection")
    Object connection(Object target);
}
