package net.momirealms.sparrow.ui.proxy.minecraft.world.entity.npc;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = {
        "net.minecraft.world.entity.npc.villager.VillagerData",
        "net.minecraft.world.entity.npc.VillagerData"
})
public interface VillagerDataProxy {
    VillagerDataProxy INSTANCE = ASMProxyFactory.create(VillagerDataProxy.class);

    @MethodInvoker(name = "getMaxXpPerLevel", isStatic = true, activeIf = "min_version=1.20.1")
    int getMaxXpPerLevel(int level);
}
