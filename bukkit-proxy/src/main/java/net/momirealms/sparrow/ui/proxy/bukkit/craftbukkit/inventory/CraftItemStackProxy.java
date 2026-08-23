package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.inventory.ItemStack;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftItemStack")
public interface CraftItemStackProxy {
    CraftItemStackProxy INSTANCE = ASMProxyFactory.create(CraftItemStackProxy.class);

    @MethodInvoker(name = "unwrap", isStatic = true, activeIf = "min_version=1.20.1")
    Object unwrap(ItemStack item);

    @MethodInvoker(name = "asCraftMirror", isStatic = true, activeIf = "min_version=1.20.1")
    ItemStack asCraftMirror(@Type(clazz = ItemStackProxy.class) Object item);
}
