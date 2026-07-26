package net.momirealms.sparrow.ui.proxy.minecraft.nbt;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.io.DataInput;
import java.io.DataOutput;

@ReflectionProxy(name = "net.minecraft.nbt.NbtIo")
public interface NbtIoProxy {
    NbtIoProxy INSTANCE = ASMProxyFactory.create(NbtIoProxy.class);

    @MethodInvoker(name = "write", isStatic = true)
    void write(@Type(name = "net.minecraft.nbt.CompoundTag") Object compoundTag, DataOutput output);

    @MethodInvoker(name = "read", isStatic = true)
    Object read(DataInput input, @Type(clazz = NbtAccounterProxy.class) Object accounter);
}
