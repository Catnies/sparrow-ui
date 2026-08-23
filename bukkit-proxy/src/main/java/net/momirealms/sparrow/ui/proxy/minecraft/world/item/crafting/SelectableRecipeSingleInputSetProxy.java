package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.List;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputSet", activeIf = "min_version=1.21.2")
public interface SelectableRecipeSingleInputSetProxy {
    SelectableRecipeSingleInputSetProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeSingleInputSetProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.2")
    Object newInstance(List<?> entries);
}
