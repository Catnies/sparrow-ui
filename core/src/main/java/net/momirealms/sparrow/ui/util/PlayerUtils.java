package net.momirealms.sparrow.ui.util;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.util.PredictionProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * 返回玩家当前的网络连接, channel 可能在 Bukkit 退出事件之前关闭.
     *
     * @param player Bukkit 玩家
     * @return 玩家连接的 Netty channel; 没有网络连接的假人可能返回 null
     */
    @Nullable
    public static Channel getChannel(@NotNull Player player) {
        Object serverPlayer = CraftEntityProxy.INSTANCE.entity(player);
        Object packetListener = ServerPlayerProxy.INSTANCE.connection(serverPlayer);
        Object connection = ServerCommonPacketListenerImplProxy.INSTANCE.connection(packetListener);
        return (Channel) ConnectionProxy.INSTANCE.channel(connection);
    }

    /**
     * 将物品的独立副本交给 NMS 玩家向前丢出, 空物品不产生掉落.
     * <p>用于事务提交后的掉落, 取消由提交前的点击事件处理, 此处不触发 PlayerDropItemEvent.
     *
     * @param player 执行掉落的玩家
     * @param item 已从事务来源中扣除的物品
     */
    public static void dropItem(@NotNull Player player, @NotNull ItemStack item) {
        Object handle = ItemUtils.getItemStackHandle(item);
        if (ItemStackProxy.INSTANCE.isEmpty(handle)) return;
        // 实体持有独立物品; Spigot 普通物品在转换时已经产生副本.
        if (VersionHelper.hasPaperPatch || CraftItemStackProxy.CLASS.isInstance(item)) {
            handle = ItemStackProxy.INSTANCE.copy(handle);
        }
        Object entity = CraftEntityProxy.INSTANCE.entity(player);
        if (VersionHelper.hasPaperPatch) {
            if (VersionHelper.isOrAbove26_3) {
                ServerPlayerProxy.INSTANCE.drop$1(entity, handle, true, PredictionProxy.INSTANCE.getPredicted(), false, null);
            } else {
                ServerPlayerProxy.INSTANCE.drop(entity, handle, false, true, false, null);
            }
        } else {
            ServerPlayerProxy.INSTANCE.drop$0(entity, handle, false, true, false);
        }
    }
}
