package net.momirealms.sparrow.ui.proxy.minecraft.server.dedicated;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.dedicated.DedicatedServerProperties", activeIf = "min_version=1.21.8")
public interface DedicatedServerPropertiesProxy {
    DedicatedServerPropertiesProxy INSTANCE = ASMProxyFactory.create(DedicatedServerPropertiesProxy.class);

    @FieldGetter(name = "networkCompressionThreshold", activeIf = "min_version=1.21.8")
    int networkCompressionThreshold(Object target);
}
