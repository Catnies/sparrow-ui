package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import org.bukkit.Keyed;

@ReflectionProxy(name = "org.bukkit.craftbukkit.CraftRegistry")
public interface CraftRegistryProxy {
    CraftRegistryProxy INSTANCE = ASMProxyFactory.create(CraftRegistryProxy.class);

    @MethodInvoker(name = "getMinecraftRegistry", isStatic = true, activeIf = "min_version=1.20.1")
    Object getMinecraftRegistry();

    @MethodInvoker(name = "getMinecraftRegistry", isStatic = true, activeIf = "min_version=1.20.1")
    Object getMinecraftRegistry(@Type(name = "net.minecraft.resources.ResourceKey") Object key);

    @MethodInvoker(name = "bukkitToMinecraft", isStatic = true, activeIf = "min_version=1.20.4")
    Object bukkitToMinecraft(Keyed bukkit);

    @MethodInvoker(name = "bukkitToMinecraftHolder", isStatic = true, activeIf = "min_version=1.21.7")
    Object bukkitToMinecraftHolder(Keyed bukkit);
}
