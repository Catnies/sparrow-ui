package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * Minecraft 网络连接中 Netty Channel 的访问代理.
 */
@ReflectionProxy(name = "net.minecraft.network.Connection")
public interface ConnectionProxy {
    ConnectionProxy INSTANCE = ASMProxyFactory.create(ConnectionProxy.class);

    @FieldGetter(name = "channel")
    Object channel(Object target);
}
