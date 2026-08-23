package net.momirealms.sparrow.ui.proxy.minecraft.world.item.component;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.component.BundleContents", activeIf = "min_version=1.20.5")
public interface BundleContentsProxy {
    BundleContentsProxy INSTANCE = ASMProxyFactory.create(BundleContentsProxy.class);

    @MethodInvoker(name = "size", activeIf = "min_version=1.20.5")
    int size(Object target);

    @MethodInvoker(name = "isEmpty", activeIf = "min_version=1.20.5")
    boolean isEmpty(Object target);

    @MethodInvoker(name = "getSelectedItem", activeIf = "min_version=1.21.2 && max_version=1.21.11")
    int selectedItem(Object target);

    @MethodInvoker(name = "getSelectedItemIndex", activeIf = "min_version=26.1")
    int selectedItemIndex(Object target);
}
