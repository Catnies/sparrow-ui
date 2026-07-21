package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import org.bukkit.entity.Entity;

/**
 * 从 CraftBukkit 实体取得底层 Minecraft 实体的代理.
 */
@ReflectionProxy(name = "org.bukkit.craftbukkit.entity.CraftEntity")
public interface CraftEntityProxy {
    CraftEntityProxy INSTANCE = ASMProxyFactory.create(CraftEntityProxy.class);

    @FieldGetter(name = "entity")
    Object entity(Entity target);
}
