package net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;
import net.nyana.reflection.proxy.annotation.Type;

import java.util.Optional;

@ReflectionProxy(name = "net.minecraft.world.level.saveddata.maps.MapDecoration")
public interface MapDecorationProxy {
    MapDecorationProxy INSTANCE = ASMProxyFactory.create(MapDecorationProxy.class);

    @ConstructorInvoker
    Object newInstance(
            @Type(name = "net.minecraft.core.Holder") Object type,
            byte x,
            byte y,
            byte rotation,
            Optional<?> name
    );
}
