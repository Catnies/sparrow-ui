package net.momirealms.sparrow.ui.proxy.minecraft.network.protocol;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.protocol.PacketType", activeIf = "min_version=1.21.8")
public interface PacketTypeProxy {
    PacketTypeProxy INSTANCE = ASMProxyFactory.create(PacketTypeProxy.class);

    @MethodInvoker(name = "id", activeIf = "min_version=1.21.8")
    Object id(Object target);
}
