package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import com.mojang.serialization.DynamicOps;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.NbtOps")
public interface NbtOpsProxy {
    NbtOpsProxy INSTANCE = ASMProxyFactory.create(NbtOpsProxy.class);

    @FieldGetter(name = "INSTANCE", isStatic = true)
    DynamicOps<Object> getINSTANCE();
}
