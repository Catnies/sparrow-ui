package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.ui.proxy.minecraft.world.ContainerProxy;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.inventory.Inventory;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftInventory")
public interface CraftInventoryProxy {
    CraftInventoryProxy INSTANCE = ASMProxyFactory.create(CraftInventoryProxy.class);
    Class<?> CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftInventory");

    @ConstructorInvoker(activeIf = "min_version=1.20.1")
    Inventory newInstance(@Type(clazz = ContainerProxy.class) Object container);

    @MethodInvoker(name = "getInventory", activeIf = "min_version=1.20.1")
    Object getInventory(Object target);
}
