package net.momirealms.sparrow.ui.proxy.minecraft.network.chat;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.chat.Component")
public interface ComponentProxy {
    ComponentProxy INSTANCE = ASMProxyFactory.create(ComponentProxy.class);

    @MethodInvoker(name = "empty", isStatic = true)
    Object empty();
}
