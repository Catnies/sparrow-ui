package net.momirealms.sparrow.ui.proxy.paper.adventure;

import net.kyori.adventure.text.Component;
import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.MethodInvoker;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

/**
 * Paper Adventure 与原版聊天组件之间的转换代理.
 */
@ReflectionProxy(name = "io.papermc.paper.adventure.PaperAdventure", activeIf = "has_patch=paper")
public interface PaperAdventureProxy {
    PaperAdventureProxy INSTANCE = ASMProxyFactory.create(PaperAdventureProxy.class);

    @MethodInvoker(name = "asVanilla", isStatic = true)
    Object asVanilla(Component component);
}
