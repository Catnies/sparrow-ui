package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.entity.Entity;

@ReflectionProxy(name = "org.bukkit.craftbukkit.entity.CraftEntity")
public interface CraftEntityProxy {
    CraftEntityProxy INSTANCE = ASMProxyFactory.create(CraftEntityProxy.class);

    @FieldGetter(name = "entity")
    Object entity(Entity target);
}
