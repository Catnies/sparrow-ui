package net.momirealms.sparrow.ui.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.BundleContents$Mutable")
public interface BundleContentsMutableProxy {
    BundleContentsMutableProxy INSTANCE = ASMProxyFactory.create(BundleContentsMutableProxy.class);

    @ConstructorInvoker
    Object newInstance(@Type(name = "net.minecraft.world.item.component.BundleContents") Object contents);

    @MethodInvoker(name = "tryInsert")
    int tryInsert(Object target, @Type(clazz = ItemStackProxy.class) Object item);

    @MethodInvoker(name = "toggleSelectedItem")
    void toggleSelectedItem(Object target, int selectedIndex);

    @MethodInvoker(name = "removeOne")
    Object removeOne(Object target);

    @MethodInvoker(name = "toImmutable")
    Object toImmutable(Object target);
}
