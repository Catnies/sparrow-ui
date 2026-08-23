package net.momirealms.sparrow.ui.proxy.minecraft.network;

import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.network.HashedStack", activeIf = "min_version=1.21.5")
public interface HashedStackProxy {
}
