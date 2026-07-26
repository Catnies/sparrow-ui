package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.craftbukkit.CraftRegistry")
public interface CraftRegistryProxy {
    CraftRegistryProxy INSTANCE = ASMProxyFactory.create(CraftRegistryProxy.class);

    @MethodInvoker(name = "getMinecraftRegistry", isStatic = true)
    Object getMinecraftRegistry();
}
