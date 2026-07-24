package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeHolder")
public interface RecipeHolderProxy {
    RecipeHolderProxy INSTANCE = ASMProxyFactory.create(RecipeHolderProxy.class);

    @MethodInvoker(name = "id")
    Object id(Object target);
}
