package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.io.DataInput;
import java.io.DataOutput;

@ReflectionProxy(name = "net.minecraft.nbt.NbtIo")
public interface NbtIoProxy {
    NbtIoProxy INSTANCE = ASMProxyFactory.create(NbtIoProxy.class);

    @MethodInvoker(name = "write", isStatic = true, activeIf = "min_version=1.20.1")
    void write(@Type(name = "net.minecraft.nbt.CompoundTag") Object compoundTag, DataOutput output);

    @MethodInvoker(name = "read", isStatic = true, activeIf = "min_version=1.20.1")
    Object read(DataInput input, @Type(clazz = NbtAccounterProxy.class) Object accounter);
}
