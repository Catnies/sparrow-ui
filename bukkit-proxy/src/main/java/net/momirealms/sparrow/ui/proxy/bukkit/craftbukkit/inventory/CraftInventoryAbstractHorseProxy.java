package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "org.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorse")
public interface CraftInventoryAbstractHorseProxy {
    CraftInventoryAbstractHorseProxy INSTANCE = ASMProxyFactory.create(CraftInventoryAbstractHorseProxy.class);
    Class<?> CLASS = SparrowClass.find("org.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorse");

    @FieldGetter(name = "equipment", activeIf = "min_version=26.2 && !has_patch=paper")
    Object equipment(Object target);
}
