package net.momirealms.sparrow.ui.proxy.minecraft.network.chat;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.chat.Component")
public interface ComponentProxy {
    ComponentProxy INSTANCE = ASMProxyFactory.create(ComponentProxy.class);

    @MethodInvoker(name = "empty", isStatic = true, activeIf = "min_version=1.20.1")
    Object empty();

    @MethodInvoker(name = "literal", isStatic = true, activeIf = "min_version=1.20.1")
    Object literal(String text);
}
