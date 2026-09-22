package net.momirealms.sparrow.ui;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.util.ReflectionUtils;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;

public final class PlayerConnectionTestSupport {
    private static boolean installed;

    private PlayerConnectionTestSupport() {
    }

    // MockBukkit 玩家没有 NMS 句柄, 测试中的连接状态由原有玩家替身提供.
    public static synchronized void install() {
        if (installed) return;
        try {
            Class<?> reflection = Class.forName("net.momirealms.sparrow.reflection.SReflection");
            Method setPredicate = reflection.getMethod("setActivePredicate", Predicate.class);
            Object previous = reflection.getMethod("getFilter").invoke(null);
            setPredicate.invoke(null, (Predicate<String>) ignored -> false);
            try {
                Class.forName(CraftEntityProxy.class.getName(), true, CraftEntityProxy.class.getClassLoader());
                Class.forName(ServerPlayerProxy.class.getName(), true, ServerPlayerProxy.class.getClassLoader());
            } finally {
                setPredicate.invoke(null, previous);
            }
            Object entities = Proxy.newProxyInstance(
                    CraftEntityProxy.class.getClassLoader(),
                    new Class<?>[]{CraftEntityProxy.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("entity")) return arguments[0];
                        throw new UnsupportedOperationException(method.getName());
                    }
            );
            Object players = Proxy.newProxyInstance(
                    ServerPlayerProxy.class.getClassLoader(),
                    new Class<?>[]{ServerPlayerProxy.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("hasDisconnected")) return !((Player) arguments[0]).isConnected();
                        throw new UnsupportedOperationException(method.getName());
                    }
            );
            ReflectionUtils.unreflectSetter(CraftEntityProxy.class.getField("INSTANCE")).invoke(entities);
            ReflectionUtils.unreflectSetter(ServerPlayerProxy.class.getField("INSTANCE")).invoke(players);
            installed = true;
        } catch (Throwable throwable) {
            throw new AssertionError("Unable to install the player connection proxies", throwable);
        }
    }
}
