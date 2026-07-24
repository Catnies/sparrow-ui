package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Paper 容器协议适配器.
 *
 * <p>调用方必须保证菜单创建方法与生成的 {@link MenuHandle} 方法运行在玩家实体线程.
 * 此类只负责构造协议菜单, 实际网络写入由
 * {@link PacketListener} 切换到 Netty event loop.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class MenuFactoryImpl implements MenuFactory, AutoCloseable {
    private final PacketListener packets;

    /**
     * 创建共享一个 {@link PacketListener} 的菜单工厂.
     *
     * @param plugin 注册网络监听器的插件
     */
    public MenuFactoryImpl(@NotNull Plugin plugin) {
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
                MenuFactoryImpl.normalMenuType(rows),
                InventoryType.CHEST,
                MenuFactoryImpl.normalBukkitMenuType(rows),
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
                MenuTypeProxy.HOPPER,
                InventoryType.HOPPER,
                MenuType.HOPPER,
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
        return new AnvilMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MenuHandle dispenser(@NotNull Player viewer, long generation) {
        return new PaperMenuHandle(
                this.packets,
                viewer,
                MenuTypeProxy.GENERIC_3x3,
                InventoryType.DISPENSER,
                MenuType.GENERIC_3X3,
                9,
                generation
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MenuHandle dropper(@NotNull Player viewer, long generation) {
        return new PaperMenuHandle(
                this.packets,
                viewer,
                MenuTypeProxy.GENERIC_3x3,
                InventoryType.DROPPER,
                MenuType.GENERIC_3X3,
                9,
                generation
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MenuHandle grindstone(@NotNull Player viewer, long generation) {
        return new GrindstoneMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public MenuHandle smithing(@NotNull Player viewer, long generation) {
        return new SmithingMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public BrewingMenuHandle brewing(@NotNull Player viewer, long generation) {
        return new BrewingMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public CartographyMenuHandle cartography(@NotNull Player viewer, long generation) {
        return new CartographyMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public CrafterMenuHandle crafter(@NotNull Player viewer, long generation) {
        return new CrafterMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public StonecutterMenuHandle stonecutter(@NotNull Player viewer, long generation) {
        return new StonecutterMenuHandleImpl(this.packets, viewer, generation);
    }

    /**
     * 卸载所有已注入的玩家网络 handler.
     */
    @Override
    public void close() {
        this.packets.close();
    }

    // 返回供 OpenScreen 和菜单代理使用的 NMS MenuType 句柄.
    private static Object normalMenuType(int rows) {
        return switch (rows) {
            case 1 -> MenuTypeProxy.GENERIC_9x1;
            case 2 -> MenuTypeProxy.GENERIC_9x2;
            case 3 -> MenuTypeProxy.GENERIC_9x3;
            case 4 -> MenuTypeProxy.GENERIC_9x4;
            case 5 -> MenuTypeProxy.GENERIC_9x5;
            case 6 -> MenuTypeProxy.GENERIC_9x6;
            default -> throw new IllegalArgumentException("normal inventory must contain between one and six rows");
        };
    }

    private static MenuType normalBukkitMenuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9X1;
            case 2 -> MenuType.GENERIC_9X2;
            case 3 -> MenuType.GENERIC_9X3;
            case 4 -> MenuType.GENERIC_9X4;
            case 5 -> MenuType.GENERIC_9X5;
            case 6 -> MenuType.GENERIC_9X6;
            default -> throw new IllegalArgumentException("normal inventory must contain between one and six rows");
        };
    }
}
