package net.momirealms.sparrow.ui.proxy.minecraft.server.dedicated;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.dedicated.DedicatedServer", activeIf = "min_version=1.21.8")
public interface DedicatedServerProxy {
    DedicatedServerProxy INSTANCE = ASMProxyFactory.create(DedicatedServerProxy.class);

    @FieldGetter(name = "settings", activeIf = "min_version=1.21.8")
    Object settings(Object target);
}
