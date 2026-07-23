package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.Items")
public interface ItemsProxy {
    ItemsProxy INSTANCE = ASMProxyFactory.create(ItemsProxy.class);

    @FieldGetter(name = "BARRIER", isStatic = true)
    Object barrier();
}
