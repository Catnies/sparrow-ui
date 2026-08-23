package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {
        "org.bukkit.craftbukkit.inventory.CraftInventorySaddledMount",
        "org.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorse"
})
public interface CraftInventorySaddledMountProxy {
    CraftInventorySaddledMountProxy INSTANCE = ASMProxyFactory.create(CraftInventorySaddledMountProxy.class);
    Class<?> CLASS = SparrowClass.find(
            "org.bukkit.craftbukkit.inventory.CraftInventorySaddledMount",
            "org.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorse"
    );

    @MethodInvoker(name = "getSaddleInventory", activeIf = "min_version=1.21.5")
    Object getSaddleInventory(Object target);

    @MethodInvoker(name = "getArmorInventory", activeIf = "min_version=1.21")
    Object getArmorInventory(Object target);

    @MethodInvoker(name = "getMainInventory", activeIf = "min_version=1.21")
    Object getMainInventory(Object target);
}
