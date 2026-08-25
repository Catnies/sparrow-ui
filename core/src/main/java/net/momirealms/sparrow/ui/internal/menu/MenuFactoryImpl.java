package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * Paper 容器协议适配器.
 * <p><strong>菜单创建和生成的 MenuHandle 必须在玩家实体线程调用.</strong>
 * {@link MenuPacketGateway} 将实际网络写入切换到 Netty event loop.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MenuFactoryImpl implements MenuFactory, AutoCloseable {
    private final MenuPacketGateway packets;

    /**
     * 创建共享一个 {@link MenuPacketGateway} 的菜单工厂.
     *
     * @param plugin 注册网络监听器的插件
     */
    public MenuFactoryImpl(@NotNull Plugin plugin) {
        this.packets = new MenuPacketGateway(plugin, SparrowUI.getInstance().networkManager());
    }

    @NotNull
    @Override
    public MenuHandle normal(@NotNull Player viewer, int rows, long generation) {
        return new ContainerMenuHandle(
                this.packets,
                viewer,
                MenuFactoryImpl.normalMenuType(rows),
                InventoryType.CHEST,
                MenuFactoryImpl.normalBukkitMenuType(rows),
                rows * 9,
                generation
        );
    }

    @NotNull
    @Override
    public MenuHandle hopper(@NotNull Player viewer, long generation) {
        return new ContainerMenuHandle(
                this.packets,
                viewer,
                MenuTypeProxy.HOPPER,
                InventoryType.HOPPER,
                MenuType.HOPPER,
                5,
                generation
        );
    }

    @NotNull
    @Override
    public AnvilMenuHandle anvil(@NotNull Player viewer, long generation) {
        return new AnvilMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public MenuHandle dispenser(@NotNull Player viewer, long generation) {
        return new ContainerMenuHandle(
                this.packets,
                viewer,
                MenuTypeProxy.GENERIC_3x3,
                InventoryType.DISPENSER,
                MenuType.GENERIC_3X3,
                9,
                generation
        );
    }

    @Override
    @NotNull
    public MenuHandle dropper(@NotNull Player viewer, long generation) {
        return new ContainerMenuHandle(
                this.packets,
                viewer,
                MenuTypeProxy.GENERIC_3x3,
                InventoryType.DROPPER,
                MenuType.GENERIC_3X3,
                9,
                generation
        );
    }

    @Override
    @NotNull
    public MenuHandle grindstone(@NotNull Player viewer, long generation) {
        return new GrindstoneMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public MenuHandle smithing(@NotNull Player viewer, long generation) {
        return new SmithingMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public BrewingMenuHandle brewing(@NotNull Player viewer, long generation) {
        return new BrewingMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public CartographyMenuHandle cartography(@NotNull Player viewer, long generation) {
        return new CartographyMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public CrafterMenuHandle crafter(@NotNull Player viewer, long generation) {
        return new CrafterMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public RecipeBookMenuHandle crafting(@NotNull Player viewer, long generation) {
        return new CraftingMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public FurnaceMenuHandle furnace(@NotNull Player viewer, long generation) {
        return new FurnaceMenuHandleImpl(
                this.packets,
                viewer,
                MenuTypeProxy.FURNACE,
                InventoryType.FURNACE,
                MenuType.FURNACE,
                generation
        );
    }

    @Override
    @NotNull
    public FurnaceMenuHandle smoker(@NotNull Player viewer, long generation) {
        return new FurnaceMenuHandleImpl(
                this.packets,
                viewer,
                MenuTypeProxy.SMOKER,
                InventoryType.SMOKER,
                MenuType.SMOKER,
                generation
        );
    }

    @Override
    @NotNull
    public FurnaceMenuHandle blastFurnace(@NotNull Player viewer, long generation) {
        return new FurnaceMenuHandleImpl(
                this.packets,
                viewer,
                MenuTypeProxy.BLAST_FURNACE,
                InventoryType.BLAST_FURNACE,
                MenuType.BLAST_FURNACE,
                generation
        );
    }

    @Override
    @NotNull
    public EnchantmentMenuHandle enchantment(@NotNull Player viewer, long generation) {
        return new EnchantmentMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public StonecutterMenuHandle stonecutter(@NotNull Player viewer, long generation) {
        return new StonecutterMenuHandleImpl(this.packets, viewer, generation);
    }

    @Override
    @NotNull
    public MerchantMenuHandle merchant(
            @NotNull Player viewer,
            long generation,
            @NotNull MerchantWindow window,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        return new MerchantMenuHandleImpl(
                this.packets,
                viewer,
                generation,
                window,
                reporter
        );
    }

    /**
     * 关闭菜单协议会话与事件入口.
     */
    @Override
    public void close() {
        this.packets.close();
    }

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
