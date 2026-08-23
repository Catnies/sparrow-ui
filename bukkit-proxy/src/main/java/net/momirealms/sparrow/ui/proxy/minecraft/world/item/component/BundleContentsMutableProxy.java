package net.momirealms.sparrow.ui.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.BundleContents$Mutable", activeIf = "min_version=1.20.5")
public interface BundleContentsMutableProxy {
    BundleContentsMutableProxy INSTANCE = ASMProxyFactory.create(BundleContentsMutableProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.20.5")
    Object newInstance(@Type(name = "net.minecraft.world.item.component.BundleContents") Object contents);

    @MethodInvoker(name = "tryInsert", activeIf = "min_version=1.20.5")
    int tryInsert(Object target, @Type(clazz = ItemStackProxy.class) Object item);

    @MethodInvoker(name = "toggleSelectedItem", activeIf = "min_version=1.21.2")
    void toggleSelectedItem(Object target, int selectedIndex);

    @MethodInvoker(name = "removeOne", activeIf = "min_version=1.20.5")
    Object removeOne(Object target);

    @MethodInvoker(name = "toImmutable", activeIf = "min_version=1.20.5")
    Object toImmutable(Object target);
}
