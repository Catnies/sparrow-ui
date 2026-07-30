package net.momirealms.sparrow.ui.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.BundleContents")
public interface BundleContentsProxy {
    BundleContentsProxy INSTANCE = ASMProxyFactory.create(BundleContentsProxy.class);

    @MethodInvoker(name = "size")
    int size(Object target);

    @MethodInvoker(name = "isEmpty")
    boolean isEmpty(Object target);

    @MethodInvoker(name = "getSelectedItem", activeIf = "!min_version=26.1")
    int selectedItem(Object target);

    @MethodInvoker(name = "getSelectedItemIndex", activeIf = "min_version=26.1")
    int selectedItemIndex(Object target);
}
