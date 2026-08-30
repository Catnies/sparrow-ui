package net.momirealms.sparrow.ui.proxy.minecraft.server;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.server.MinecraftServer")
public interface MinecraftServerProxy {
    MinecraftServerProxy INSTANCE = ASMProxyFactory.create(MinecraftServerProxy.class);

    @MethodInvoker(name = "getServer", isStatic = true, activeIf = "min_version=1.20.1")
    Object getServer();

    @MethodInvoker(name = "getRecipeManager", activeIf = "min_version=1.20.1")
    Object getRecipeManager(Object target);

    @FieldGetter(name = "connection", activeIf = "min_version=1.20.1")
    Object getConnection(Object target);
}
