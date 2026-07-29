package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.NbtAccounter")
public interface NbtAccounterProxy {
    NbtAccounterProxy INSTANCE = ASMProxyFactory.create(NbtAccounterProxy.class);

    @MethodInvoker(name = "unlimitedHeap", isStatic = true)
    Object unlimitedHeap();
}
