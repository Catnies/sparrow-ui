package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;
import org.bukkit.inventory.ItemStack;

/**
 * Bukkit ItemStack 与 Minecraft ItemStack 之间的转换代理.
 */
@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftItemStack")
public interface CraftItemStackProxy {
    CraftItemStackProxy INSTANCE = ASMProxyFactory.create(CraftItemStackProxy.class);

    @MethodInvoker(name = "unwrap", isStatic = true)
    Object unwrap(ItemStack item);

    @MethodInvoker(name = "asCraftMirror", isStatic = true)
    ItemStack asCraftMirror(@Type(clazz = ItemStackProxy.class) Object item);
}
