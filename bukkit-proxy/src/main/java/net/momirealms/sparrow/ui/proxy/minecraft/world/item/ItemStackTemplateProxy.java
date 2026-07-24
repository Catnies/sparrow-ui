package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.ItemStackTemplate", activeIf = "min_version=26.1")
public interface ItemStackTemplateProxy {
    ItemStackTemplateProxy INSTANCE = ASMProxyFactory.create(ItemStackTemplateProxy.class);

    @MethodInvoker(name = "fromNonEmptyStack", isStatic = true)
    Object fromNonEmptyStack(@Type(name = "net.minecraft.world.item.ItemStack") Object stack);
}
