package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.RecipeManager$ServerDisplayInfo", activeIf = "min_version=1.21.2")
public interface RecipeManagerServerDisplayInfoProxy {
    RecipeManagerServerDisplayInfoProxy INSTANCE = ASMProxyFactory.create(RecipeManagerServerDisplayInfoProxy.class);

    @MethodInvoker(name = "display", activeIf = "min_version=1.21.2")
    Object display(Object target);

    @MethodInvoker(name = "parent", activeIf = "min_version=1.21.2")
    Object parent(Object target);
}
