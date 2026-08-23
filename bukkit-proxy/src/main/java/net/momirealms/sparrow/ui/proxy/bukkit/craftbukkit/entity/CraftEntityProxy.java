package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.entity.Entity;

@ReflectionProxy(name = "org.bukkit.craftbukkit.entity.CraftEntity")
public interface CraftEntityProxy {
    CraftEntityProxy INSTANCE = ASMProxyFactory.create(CraftEntityProxy.class);

    @FieldGetter(name = "entity", activeIf = "min_version=1.20.1")
    Object entity(Entity target);
}
