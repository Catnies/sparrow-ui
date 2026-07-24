package net.momirealms.sparrow.ui.proxy.minecraft.server;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.MinecraftServer")
public interface MinecraftServerProxy {
    MinecraftServerProxy INSTANCE = ASMProxyFactory.create(MinecraftServerProxy.class);

    @MethodInvoker(name = "getServer", isStatic = true)
    Object getServer();

    @MethodInvoker(name = "getRecipeManager")
    Object getRecipeManager(Object target);
}
