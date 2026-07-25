package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.npc;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {
        "net.minecraft.world.entity.npc.villager.VillagerData",
        "net.minecraft.world.entity.npc.VillagerData"
})
public interface VillagerDataProxy {
    VillagerDataProxy INSTANCE = ASMProxyFactory.create(VillagerDataProxy.class);

    @MethodInvoker(name = "getMaxXpPerLevel", isStatic = true)
    int getMaxXpPerLevel(int level);
}
