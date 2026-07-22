package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.minecraft.world.inventory.MenuType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Paper/NMS 容器适配器.
 *
 * <p>调用方必须保证菜单创建方法与生成的 {@link MenuHandle} 方法运行在玩家实体线程.
 * 此类只负责构造协议菜单, 实际网络写入由
 * {@link PacketListener} 切换到 Netty event loop.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class PaperMenuFactory implements MenuFactory, AutoCloseable {
    private final PacketListener packets;

    /**
     * 创建共享一个 {@link PacketListener} 的菜单工厂.
     *
     * @param plugin 注册网络监听器的插件
     */
    public PaperMenuFactory(@NotNull Plugin plugin) {
        this.packets = new PacketListener(plugin, SparrowUI.getInstance()::handleException);
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public MenuHandle normal(@NotNull Player viewer, int rows, long generation) {
        return new PaperMenuHandle(
                this.packets,
                viewer,
                PaperMenuFactory.normalMenuType(rows),
                InventoryType.CHEST,
                PaperMenuFactory.normalBukkitMenuType(rows),
                rows * 9,
                generation
        );
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public MenuHandle hopper(@NotNull Player viewer, long generation) {
        return new PaperMenuHandle(
                this.packets,
                viewer,
                MenuType.HOPPER,
                InventoryType.HOPPER,
                org.bukkit.inventory.MenuType.HOPPER,
                5,
                generation
        );
    }

    /**
     * {@inheritDoc}
     */
    @NotNull
    @Override
    public AnvilMenuHandle anvil(@NotNull Player viewer, long generation) {
        return new PaperAnvilMenuHandle(this.packets, viewer, generation);
    }

    /**
     * 卸载所有已注入的玩家网络 handler.
     */
    @Override
    public void close() {
        this.packets.close();
    }

    private static MenuType<?> normalMenuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("normal inventory must contain between one and six rows");
        };
    }

    private static org.bukkit.inventory.MenuType normalBukkitMenuType(int rows) {
        return switch (rows) {
            case 1 -> org.bukkit.inventory.MenuType.GENERIC_9X1;
            case 2 -> org.bukkit.inventory.MenuType.GENERIC_9X2;
            case 3 -> org.bukkit.inventory.MenuType.GENERIC_9X3;
            case 4 -> org.bukkit.inventory.MenuType.GENERIC_9X4;
            case 5 -> org.bukkit.inventory.MenuType.GENERIC_9X5;
            case 6 -> org.bukkit.inventory.MenuType.GENERIC_9X6;
            default -> throw new IllegalArgumentException("normal inventory must contain between one and six rows");
        };
    }
}
