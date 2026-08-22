package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftInventoryCrafting")
public interface CraftInventoryCraftingProxy {
    CraftInventoryCraftingProxy INSTANCE = ASMProxyFactory.create(CraftInventoryCraftingProxy.class);
    Class<?> CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftInventoryCrafting");

    @MethodInvoker(name = "getResultInventory")
    Object getResultInventory(Object target);

    @MethodInvoker(name = "getMatrixInventory")
    Object getMatrixInventory(Object target);
}
