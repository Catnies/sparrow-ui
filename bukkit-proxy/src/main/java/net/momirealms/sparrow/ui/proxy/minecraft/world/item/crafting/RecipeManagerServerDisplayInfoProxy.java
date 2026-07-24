package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeManager$ServerDisplayInfo")
public interface RecipeManagerServerDisplayInfoProxy {
    RecipeManagerServerDisplayInfoProxy INSTANCE = ASMProxyFactory.create(RecipeManagerServerDisplayInfoProxy.class);

    @MethodInvoker(name = "display")
    Object display(Object target);

    @MethodInvoker(name = "parent")
    Object parent(Object target);
}
