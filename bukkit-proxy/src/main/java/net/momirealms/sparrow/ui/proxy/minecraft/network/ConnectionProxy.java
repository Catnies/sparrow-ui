package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.network.chat.ComponentProxy;

@ReflectionProxy(name = "net.minecraft.network.Connection")
public interface ConnectionProxy {
    ConnectionProxy INSTANCE = ASMProxyFactory.create(ConnectionProxy.class);
    Class<?> CLASS = SparrowClass.find("net.minecraft.network.Connection");

    @FieldGetter(name = "channel", activeIf = "min_version=1.20.1")
    Object channel(Object target);

    @MethodInvoker(name = "disconnect")
    void disconnect(Object target, @Type(clazz = ComponentProxy.class) Object reason);
}
