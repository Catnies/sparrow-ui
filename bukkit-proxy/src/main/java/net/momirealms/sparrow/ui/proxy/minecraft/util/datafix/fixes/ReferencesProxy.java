package net.momirealms.sparrow.ui.proxy.minecraft.util.datafix.fixes;

import com.mojang.datafixers.DSL;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.util.datafix.fixes.References")
public interface ReferencesProxy {
    ReferencesProxy INSTANCE = ASMProxyFactory.create(ReferencesProxy.class);

    @FieldGetter(name = "ITEM_STACK", isStatic = true)
    DSL.TypeReference getITEM_STACK();
}
