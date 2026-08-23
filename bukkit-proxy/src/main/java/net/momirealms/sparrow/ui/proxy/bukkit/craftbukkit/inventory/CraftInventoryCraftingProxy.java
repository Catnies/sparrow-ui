package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftInventoryCrafting")
public interface CraftInventoryCraftingProxy {
    CraftInventoryCraftingProxy INSTANCE = ASMProxyFactory.create(CraftInventoryCraftingProxy.class);
    Class<?> CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftInventoryCrafting");

    @MethodInvoker(name = "getResultInventory", activeIf = "min_version=1.20.1")
    Object getResultInventory(Object target);

    @MethodInvoker(name = "getMatrixInventory", activeIf = "min_version=1.20.1")
    Object getMatrixInventory(Object target);
}
