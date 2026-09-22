package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PlayerUtils {
    private PlayerUtils() {
    }

    /**
     * 读取 NMS 玩家的断开标记, 返回其是否仍保持连接.
     *
     * @param player Bukkit 玩家
     * @return 玩家尚未标记为断开时返回 true
     */
    public static boolean isConnected(@NotNull Player player) {
        return !ServerPlayerProxy.INSTANCE.hasDisconnected(CraftEntityProxy.INSTANCE.entity(player));
    }
}
