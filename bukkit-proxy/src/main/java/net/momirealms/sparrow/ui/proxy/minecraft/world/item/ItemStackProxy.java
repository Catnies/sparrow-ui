package net.momirealms.sparrow.ui.proxy.minecraft.world.item;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * SparrowUI 同步路径所需的 Minecraft ItemStack 最小代理.
 */
@ReflectionProxy(name = "net.minecraft.world.item.ItemStack")
public interface ItemStackProxy {
    ItemStackProxy INSTANCE = ASMProxyFactory.create(ItemStackProxy.class);

    @FieldGetter(name = "EMPTY", isStatic = true)
    Object empty();

    @MethodInvoker(name = "copy")
    Object copy(Object target);
}
