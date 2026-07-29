package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.NbtOps")
public interface NbtOpsProxy {
    NbtOpsProxy INSTANCE = ASMProxyFactory.create(NbtOpsProxy.class);

    @FieldGetter(name = "INSTANCE", isStatic = true)
    DynamicOps<Object> getINSTANCE();
}
