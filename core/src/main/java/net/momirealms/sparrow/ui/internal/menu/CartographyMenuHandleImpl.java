package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundMapItemDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapDecorationProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapDecorationTypesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapIdProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps.MapPatchProxy;
import net.momirealms.sparrow.ui.proxy.paper.adventure.PaperAdventureProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.window.CartographyWindow;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paper 制图台菜单句柄, 负责虚拟地图、地图数据包和客户端预览输入.
 */
@SuppressWarnings("UnstableApiUsage")
final class CartographyMenuHandleImpl extends ContainerMenuHandle implements CartographyMenuHandle {
    private static final int MAP_SIZE = CartographyWindow.MAP_SIZE;
    private static final int MAP_SLOT = 0;
    private static final int VIEW_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final AtomicInteger NEXT_MAP_ID = new AtomicInteger(-1);

    private final byte[] canvas = new byte[MAP_SIZE * MAP_SIZE];
    private int mapId = CartographyMenuHandleImpl.allocateMapId();
    private List<Object> decorations = List.of(); // NMS MapDecoration 的不可变快照
    private CartographyWindow.View view = CartographyWindow.View.NORMAL;
    private int dirtyMinX = MAP_SIZE;
    private int dirtyMinY = MAP_SIZE;
    private int dirtyMaxX = -1;
    private int dirtyMaxY = -1;
    private boolean iconsDirty = true;
    private boolean patchQueued;
    private boolean iconsQueued;

    CartographyMenuHandleImpl(PacketListener packets, Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.CARTOGRAPHY_TABLE,
                InventoryType.CARTOGRAPHY,
                org.bukkit.inventory.MenuType.CARTOGRAPHY_TABLE,
                3,
                generation
        );
    }

    @Override
    public void applyPatch(@NotNull CartographyWindow.MapPatch patch) {
        byte[] colors = patch.colors();
        for (int row = 0; row < patch.height(); row++) {
            System.arraycopy(
                    colors,
                    row * patch.width(),
                    this.canvas,
                    (patch.startY() + row) * MAP_SIZE + patch.startX(),
                    patch.width()
            );
        }
        this.markPatch(
                patch.startX(),
                patch.startY(),
                patch.startX() + patch.width() - 1,
                patch.startY() + patch.height() - 1
        );
    }

    @Override
    public void setIcons(@NotNull Set<CartographyWindow.MapIcon> icons) {
        ArrayList<Object> converted = new ArrayList<>(icons.size()); // NMS MapDecoration 列表
        for (CartographyWindow.MapIcon icon : icons) {
            converted.add(CartographyMenuHandleImpl.toDecoration(icon));
        }
        this.decorations = List.copyOf(converted);
        this.iconsDirty = true;
    }

    @Override
    public void resetMap() {
        this.mapId = CartographyMenuHandleImpl.allocateMapId();
        Arrays.fill(this.canvas, (byte) 0);
        this.decorations = List.of();
        this.markPatch(0, 0, MAP_SIZE - 1, MAP_SIZE - 1);
        this.iconsDirty = true;
        this.forceRemoteSlot(MAP_SLOT);
        this.forceRemoteSlot(RESULT_SLOT);
    }

    @Override
    public void setView(@NotNull CartographyWindow.View view) {
        if (this.view != view) {
            this.view = view;
            this.forceRemoteSlot(VIEW_SLOT);
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }

    @Override
    protected Object toClientItem(int rawSlot, ItemStack item) {
        if (rawSlot == MAP_SLOT) {
            Object clientItem = item.isEmpty()
                    ? ItemStackProxy.INSTANCE.newInstance(ItemsProxy.FILLED_MAP)
                    : ItemStackProxy.INSTANCE.copy(super.toClientItem(rawSlot, item)); // NMS ItemStack
            ItemStackProxy.INSTANCE.set(
                    clientItem,
                    DataComponentsProxy.MAP_ID,
                    MapIdProxy.INSTANCE.newInstance(this.mapId)
            );
            if (item.isEmpty()) {
                ItemUtils.hideTooltips(clientItem);
            }
            return clientItem;
        }
        if (rawSlot == VIEW_SLOT) {
            Object targetItem = this.viewItem(); // NMS Item
            Object clientItem = item.isEmpty()
                    ? ItemStackProxy.INSTANCE.newInstance(targetItem)
                    : ItemStackProxy.INSTANCE.transmuteCopy(super.toClientItem(rawSlot, item), targetItem);
            if (item.isEmpty()) {
                ItemUtils.hideTooltips(clientItem);
            }
            return clientItem;
        }
        return super.toClientItem(rawSlot, item);
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (dirtySlots.get(MAP_SLOT) || dirtySlots.get(VIEW_SLOT)) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        this.patchQueued = forceFull || this.hasDirtyPatch();
        this.iconsQueued = forceFull || this.iconsDirty;
        if (!this.patchQueued && !this.iconsQueued) {
            return;
        }

        Object patch = this.patchQueued ? this.createPatch(forceFull) : null; // NMS MapItemSavedData.MapPatch
        List<Object> icons = this.iconsQueued ? this.decorations : null; // NMS MapDecoration 列表
        outgoing.add(ClientboundMapItemDataPacketProxy.INSTANCE.newInstance(
                MapIdProxy.INSTANCE.newInstance(this.mapId),
                (byte) 0,
                false,
                icons,
                patch
        ));
    }

    @Override
    protected void commitPackets() {
        if (this.patchQueued) {
            this.clearDirtyPatch();
            this.patchQueued = false;
        }
        if (this.iconsQueued) {
            this.iconsDirty = false;
            this.iconsQueued = false;
        }
    }

    private Object createPatch(boolean forceFull) {
        if (forceFull) {
            return MapPatchProxy.INSTANCE.newInstance(
                    0,
                    0,
                    MAP_SIZE,
                    MAP_SIZE,
                    this.canvas.clone()
            );
        }

        int width = this.dirtyMaxX - this.dirtyMinX + 1;
        int height = this.dirtyMaxY - this.dirtyMinY + 1;
        byte[] colors = new byte[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(
                    this.canvas,
                    (this.dirtyMinY + row) * MAP_SIZE + this.dirtyMinX,
                    colors,
                    row * width,
                    width
            );
        }
        return MapPatchProxy.INSTANCE.newInstance(
                this.dirtyMinX,
                this.dirtyMinY,
                width,
                height,
                colors
        );
    }

    private Object viewItem() {
        return switch (this.view) {
            case NORMAL -> ItemsProxy.STONE;
            case SMALL -> ItemsProxy.PAPER;
            case DUPLICATE -> ItemsProxy.MAP;
            case LOCK -> ItemsProxy.GLASS_PANE;
        };
    }

    private boolean hasDirtyPatch() {
        return this.dirtyMaxX >= this.dirtyMinX && this.dirtyMaxY >= this.dirtyMinY;
    }

    private void markPatch(int minX, int minY, int maxX, int maxY) {
        this.dirtyMinX = Math.min(this.dirtyMinX, minX);
        this.dirtyMinY = Math.min(this.dirtyMinY, minY);
        this.dirtyMaxX = Math.max(this.dirtyMaxX, maxX);
        this.dirtyMaxY = Math.max(this.dirtyMaxY, maxY);
    }

    private void clearDirtyPatch() {
        this.dirtyMinX = MAP_SIZE;
        this.dirtyMinY = MAP_SIZE;
        this.dirtyMaxX = -1;
        this.dirtyMaxY = -1;
    }

    private static int allocateMapId() {
        return NEXT_MAP_ID.getAndUpdate(current -> current == Integer.MIN_VALUE ? -1 : current - 1);
    }

    private static Object toDecoration(CartographyWindow.MapIcon icon) {
        Optional<Object> name = Optional.ofNullable(icon.component()).map(PaperAdventureProxy.INSTANCE::asVanilla);
        return MapDecorationProxy.INSTANCE.newInstance(
                CartographyMenuHandleImpl.decorationType(icon.type()),
                (byte) (icon.x() - 128),
                (byte) (icon.y() - 128),
                (byte) icon.rot(),
                name
        );
    }

    private static Object decorationType(CartographyWindow.MapIcon.Type type) {
        return switch (type) {
            case WHITE_ARROW -> MapDecorationTypesProxy.PLAYER;
            case GREEN_ARROW -> MapDecorationTypesProxy.FRAME;
            case RED_ARROW -> MapDecorationTypesProxy.RED_MARKER;
            case BLUE_ARROW -> MapDecorationTypesProxy.BLUE_MARKER;
            case WHITE_CROSS -> MapDecorationTypesProxy.TARGET_X;
            case RED_POINTER -> MapDecorationTypesProxy.TARGET_POINT;
            case WHITE_CIRCLE -> MapDecorationTypesProxy.PLAYER_OFF_MAP;
            case SMALL_WHITE_CIRCLE -> MapDecorationTypesProxy.PLAYER_OFF_LIMITS;
            case MANSION -> MapDecorationTypesProxy.WOODLAND_MANSION;
            case TEMPLE -> MapDecorationTypesProxy.JUNGLE_TEMPLE;
            case WHITE_BANNER -> MapDecorationTypesProxy.WHITE_BANNER;
            case ORANGE_BANNER -> MapDecorationTypesProxy.ORANGE_BANNER;
            case MAGENTA_BANNER -> MapDecorationTypesProxy.MAGENTA_BANNER;
            case LIGHT_BLUE_BANNER -> MapDecorationTypesProxy.LIGHT_BLUE_BANNER;
            case YELLOW_BANNER -> MapDecorationTypesProxy.YELLOW_BANNER;
            case LIME_BANNER -> MapDecorationTypesProxy.LIME_BANNER;
            case PINK_BANNER -> MapDecorationTypesProxy.PINK_BANNER;
            case GRAY_BANNER -> MapDecorationTypesProxy.GRAY_BANNER;
            case LIGHT_GRAY_BANNER -> MapDecorationTypesProxy.LIGHT_GRAY_BANNER;
            case CYAN_BANNER -> MapDecorationTypesProxy.CYAN_BANNER;
            case PURPLE_BANNER -> MapDecorationTypesProxy.PURPLE_BANNER;
            case BLUE_BANNER -> MapDecorationTypesProxy.BLUE_BANNER;
            case BROWN_BANNER -> MapDecorationTypesProxy.BROWN_BANNER;
            case GREEN_BANNER -> MapDecorationTypesProxy.GREEN_BANNER;
            case RED_BANNER -> MapDecorationTypesProxy.RED_BANNER;
            case BLACK_BANNER -> MapDecorationTypesProxy.BLACK_BANNER;
            case RED_CROSS -> MapDecorationTypesProxy.RED_X;
        };
    }

}
