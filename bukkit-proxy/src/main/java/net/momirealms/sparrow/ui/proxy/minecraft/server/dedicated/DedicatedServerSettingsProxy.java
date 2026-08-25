package net.momirealms.sparrow.ui.proxy.minecraft.server.dedicated;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.dedicated.DedicatedServerSettings", activeIf = "min_version=1.21.8")
public interface DedicatedServerSettingsProxy {
    DedicatedServerSettingsProxy INSTANCE = ASMProxyFactory.create(DedicatedServerSettingsProxy.class);

    @FieldGetter(name = "properties", activeIf = "min_version=1.21.8")
    Object properties(Object target);
}
