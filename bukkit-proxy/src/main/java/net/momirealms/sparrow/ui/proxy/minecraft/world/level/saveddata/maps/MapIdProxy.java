package net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.saveddata.maps.MapId")
public interface MapIdProxy {
    MapIdProxy INSTANCE = ASMProxyFactory.create(MapIdProxy.class);

    @ConstructorInvoker
    Object newInstance(int id);
}
