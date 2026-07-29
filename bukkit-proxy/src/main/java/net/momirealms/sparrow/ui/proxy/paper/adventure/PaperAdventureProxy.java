package net.momirealms.sparrow.ui.proxy.paper.adventure;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "io.papermc.paper.adventure.PaperAdventure", activeIf = "has_patch=paper")
public interface PaperAdventureProxy {
    PaperAdventureProxy INSTANCE = ASMProxyFactory.create(PaperAdventureProxy.class);

    @MethodInvoker(name = "asVanilla", isStatic = true)
    Object asVanilla(Component component);
}