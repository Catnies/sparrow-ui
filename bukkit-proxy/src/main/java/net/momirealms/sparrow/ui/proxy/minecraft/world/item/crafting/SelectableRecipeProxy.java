package net.momirealms.sparrow.ui.proxy.minecraft.world.item.crafting;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.Optional;

@ReflectionProxy(name = "net.minecraft.world.item.crafting.SelectableRecipe")
public interface SelectableRecipeProxy {
    SelectableRecipeProxy INSTANCE = ASMProxyFactory.create(SelectableRecipeProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.world.item.crafting.display.SlotDisplay") Object optionDisplay,
            Optional<?> recipe
    );
}
