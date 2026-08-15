package net.momirealms.sparrow.ui.proxy.minecraft.world.level.material;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.material.MapColor")
public interface MapColorProxy {
    MapColorProxy INSTANCE = ASMProxyFactory.create(MapColorProxy.class);

    @MethodInvoker(name = "getColorFromPackedId", isStatic = true)
    int getColorFromPackedId(int packedId);
}
