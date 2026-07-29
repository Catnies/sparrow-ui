package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeHolder")
public interface RecipeHolderProxy {
    RecipeHolderProxy INSTANCE = ASMProxyFactory.create(RecipeHolderProxy.class);

    @MethodInvoker(name = "id")
    Object id(Object target);
}
