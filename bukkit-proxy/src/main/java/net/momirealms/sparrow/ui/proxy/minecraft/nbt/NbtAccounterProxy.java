package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.NbtAccounter")
public interface NbtAccounterProxy {
    NbtAccounterProxy INSTANCE = ASMProxyFactory.create(NbtAccounterProxy.class);

    @MethodInvoker(name = "unlimitedHeap", isStatic = true)
    Object unlimitedHeap();
}
