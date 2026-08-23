package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.Connection")
public interface ConnectionProxy {
    ConnectionProxy INSTANCE = ASMProxyFactory.create(ConnectionProxy.class);

    @FieldGetter(name = "channel", activeIf = "min_version=1.20.1")
    Object channel(Object target);
}
