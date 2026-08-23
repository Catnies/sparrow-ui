package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting.display;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.display.RecipeDisplayId", activeIf = "min_version=1.21.2")
public interface RecipeDisplayIdProxy {
    RecipeDisplayIdProxy INSTANCE = ASMProxyFactory.create(RecipeDisplayIdProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.2")
    Object newInstance(int index);

    @MethodInvoker(name = "index", activeIf = "min_version=1.21.2")
    int index(Object target);
}
