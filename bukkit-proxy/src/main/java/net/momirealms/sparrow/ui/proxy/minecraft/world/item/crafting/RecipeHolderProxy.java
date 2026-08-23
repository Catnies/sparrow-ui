package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeHolder", activeIf = "min_version=1.20.2")
public interface RecipeHolderProxy {
    RecipeHolderProxy INSTANCE = ASMProxyFactory.create(RecipeHolderProxy.class);

    @MethodInvoker(name = "id", activeIf = "min_version=1.20.2")
    Object id(Object target);
}
