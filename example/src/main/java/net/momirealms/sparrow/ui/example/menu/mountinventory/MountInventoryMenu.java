package net.momirealms.sparrow.ui.example.menu.mountinventory;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * 一次坐骑背包会话: 把坐骑自己的容器接成引用 Inventory, 内容仍然住在坐骑身上.
 * <p>槽位沿用 Bukkit 的坐骑布局, 0 是鞍位, 1 是护甲位, 再往后是储物格.
 * 鞍位只收鞍, 护甲位只收能装在身体槽的物品, 储物格不限.
 */
public final class MountInventoryMenu {
    private static final int COLUMNS = 9; // 上半部分每行的槽位数

    private final ReferencingInventory storage; // 坐骑背包的映射, 读写都落到坐骑身上
    private final NormalWindow window;

    /**
     * 为指定玩家打开一只坐骑的映射背包.
     *
     * @param viewer 要查看菜单的玩家
     * @param mount 被查看的坐骑
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer, @NotNull AbstractHorse mount) {
        return new MountInventoryMenu(viewer, mount).window.open();
    }

    private MountInventoryMenu(@NotNull Player viewer, @NotNull AbstractHorse mount) {
        this.storage = ReferencingInventory.fromContents(mount.getInventory());
        // 装备位按原版的口径收物
        this.storage.setPlacementRule(0, context -> context.item().getType() == Material.SADDLE);
        this.storage.setPlacementRule(1, context -> isBodyArmor(context.item()));

        // 上半部分一格接一格地铺开, 坐骑格数不够整行时由背景补齐
        NormalPane pane = Pane.empty(COLUMNS, rows());
        for (int slot = 0; slot < this.storage.size(); slot++) {
            pane.setElement(slot, Element.inventory(this.storage, slot));
        }
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        filler.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        pane.setBackgroundItem(filler);

        this.window = NormalWindow.builder()
                .setTitle(title(mount))
                .setUpperPane(pane)
                .build(viewer);
    }

    // 铺满整行要几行
    private int rows() {
        return (this.storage.size() + COLUMNS - 1) / COLUMNS;
    }

    /**
     * 玩家给坐骑起过名字就用那个名字, 否则显示生物类型, 由客户端按玩家语言翻译.
     *
     * @param mount 被查看的坐骑
     * @return 窗口标题
     */
    @NotNull
    private static Component title(@NotNull AbstractHorse mount) {
        Component customName = mount.customName();
        return customName != null ? customName : Component.translatable(mount.getType().translationKey());
    }

    // 马铠和羊驼地毯都声明了身体槽, 一件物品能装进哪一格由它自己的组件说了算
    private static boolean isBodyArmor(@NotNull ItemStack item) {
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.BODY;
    }
}
