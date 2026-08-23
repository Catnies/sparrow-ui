package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe$SingleInputEntry", activeIf = "min_version=1.21.2")
public interface SelectableRecipeSingleInputEntryProxy {
    SelectableRecipeSingleInputEntryProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeSingleInputEntryProxy.class);

    @ConstructorInvoker(activeIf = "min_version=1.21.2")
    Object newInstance(
            @Type(name = "net.minecraft.world.item.crafting.Ingredient") Object input,
            @Type(name = "net.minecraft.world.item.crafting.SelectableRecipe") Object recipe
    );
}
