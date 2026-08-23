package net.momirealms.sparrow.ui.proxy.minecraft.core;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.core.TypedInstance", activeIf = "min_version=26.1")
public interface TypedInstanceProxy {
    TypedInstanceProxy INSTANCE = ASMProxyFactory.create(TypedInstanceProxy.class);

    @MethodInvoker(name = "is", activeIf = "min_version=26.1")
    boolean is(Object target, @Type(name = "net.minecraft.tags.TagKey") Object tag);
}
