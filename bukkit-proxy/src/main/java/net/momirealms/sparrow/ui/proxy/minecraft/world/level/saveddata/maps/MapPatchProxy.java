package net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.ConstructorInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch")
public interface MapPatchProxy {
    MapPatchProxy INSTANCE = ASMProxyFactory.create(MapPatchProxy.class);

    @ConstructorInvoker
    Object newInstance(int startX, int startY, int width, int height, byte[] colors);
}
