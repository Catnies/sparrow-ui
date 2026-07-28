package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;
import org.bukkit.Keyed;

@ReflectionProxy(name = "org.bukkit.craftbukkit.CraftRegistry")
public interface CraftRegistryProxy {
    CraftRegistryProxy INSTANCE = ASMProxyFactory.create(CraftRegistryProxy.class);

    @MethodInvoker(name = "getMinecraftRegistry", isStatic = true)
    Object getMinecraftRegistry();

    @MethodInvoker(name = "getMinecraftRegistry", isStatic = true)
    Object getMinecraftRegistry(@Type(name = "net.minecraft.resources.ResourceKey") Object key);

    @MethodInvoker(name = "bukkitToMinecraft", isStatic = true)
    Object bukkitToMinecraft(Keyed bukkit);
}
