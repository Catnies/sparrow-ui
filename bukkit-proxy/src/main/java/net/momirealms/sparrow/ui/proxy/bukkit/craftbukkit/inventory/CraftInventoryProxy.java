package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;
import org.bukkit.inventory.Inventory;

/**
 * 为 Minecraft Container 创建 Bukkit Inventory 视图的代理.
 */
@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftInventory")
public interface CraftInventoryProxy {
    CraftInventoryProxy INSTANCE = ASMProxyFactory.create(CraftInventoryProxy.class);

    @ConstructorInvoker
    Inventory newInstance(@Type(clazz = ContainerProxy.class) Object container);
}
