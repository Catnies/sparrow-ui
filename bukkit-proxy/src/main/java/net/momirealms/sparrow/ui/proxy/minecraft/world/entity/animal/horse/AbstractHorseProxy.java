package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.animal.horse;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {
        "net.minecraft.world.entity.animal.equine.AbstractHorse",
        "net.minecraft.world.entity.animal.horse.AbstractHorse"
})
public interface AbstractHorseProxy {
    AbstractHorseProxy INSTANCE = ASMProxyFactory.create(AbstractHorseProxy.class);

    @FieldGetter(name = "inventory", activeIf = "min_version=1.21.4")
    Object inventory(Object target);

    @MethodInvoker(name = "getBodyArmorAccess", activeIf = "min_version=1.21.4 && max_version=1.21.4")
    Object getBodyArmorAccess(Object target);
}
