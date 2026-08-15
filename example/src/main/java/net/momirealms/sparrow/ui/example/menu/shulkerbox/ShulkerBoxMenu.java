package net.momirealms.sparrow.ui.example.menu.shulkerbox;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * 一次潜影盒菜单会话：持有玩家的 NMS 背包、右键槽位、潜影盒句柄、27 格内容和 Window。
 * <p>玩家直接点击上栏时先核对物品身份；拖拽、快捷转移等真正产生内容变化的操作，
 * 还会在事务 Pre 阶段核对一次。事务提交后立即把完整内容写回原潜影盒。
 */
public final class ShulkerBoxMenu {
    private static final Component INVALIDATED_MESSAGE = Component
            .text("潜影盒已被移动或替换，菜单已关闭。", NamedTextColor.RED)
            .decoration(TextDecoration.ITALIC, false);

    private final Player viewer;
    private final Inventory playerInventory;
    private final int sourceSlot;
    private final ItemStack shulker;
    private final VirtualInventory contents;
    private final NormalWindow window;
    private boolean invalidated;

    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer, int sourceSlot) {
        return new ShulkerBoxMenu(viewer, sourceSlot).window.open();
    }

    private ShulkerBoxMenu(@NotNull Player viewer, int sourceSlot) {
        this.viewer = viewer;
        this.playerInventory = ((CraftPlayer) viewer).getHandle().getInventory();
        this.sourceSlot = sourceSlot;
        this.shulker = this.playerInventory.getItem(sourceSlot);
        this.contents = new VirtualInventory(readContents(this.shulker));

        // 创建 Pane
        NormalPane pane = Pane.empty(9, 3);
        for (int slot = 0; slot < this.contents.size(); slot++) {
            pane.setElement(slot, Element.inventory(this.contents, slot));
        }

        // 创建 Window
        this.window = NormalWindow.builder()
                .setUpperPane(pane)
                .setTitle(PaperAdventure.asAdventure(this.shulker.getHoverName()))
                .build(viewer);

        // 冻结所持潜影盒
        if (sourceSlot == Inventory.SLOT_OFFHAND) {
            this.window.offhandFrozen(true);
        } else {
            this.window.frozenAt(this.window.windowSlotAtHotbar(sourceSlot), true);
        }

        // 在发起事务时应该检查源是否还在.
        this.contents.subscribePreUpdate(event -> {
            if (!this.validateSource()) {
                event.setCancelled(true);
            }
        });

        // 完成事务时应该把内容写回原潜影盒
        this.contents.subscribePostUpdate(event -> {
            if (this.validateSource()) {
                this.writeContents(this.contents.snapshot());
            }
        });
    }

    /**
     * 校验原潜影盒是否还在.
     */
    private boolean validateSource() {
        if (this.invalidated) return false;
        if (this.playerInventory.getItem(this.sourceSlot) == this.shulker) return true;
        // 如果校验失败, 那么发送失败消息然后关闭 Window.
        this.invalidated = true;
        this.contents.frozen(true);
        this.viewer.sendMessage(INVALIDATED_MESSAGE);
        this.window.close();
        return false;
    }

    /**
     * 从物品携带的方块状态快照读取潜影盒内容。
     */
    @NotNull
    private static org.bukkit.inventory.ItemStack[] readContents(@NotNull ItemStack shulker) {
        NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
        shulker.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
        org.bukkit.inventory.ItemStack[] contents = new org.bukkit.inventory.ItemStack[items.size()];
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack item = items.get(slot);
            contents[slot] = item.isEmpty() ? null : CraftItemStack.asCraftMirror(item);
        }
        return contents;
    }

    /**
     * 直接替换原 NMS 物品的容器组件，不创建新的潜影盒句柄。
     */
    private void writeContents(@Nullable org.bukkit.inventory.ItemStack @NotNull [] contents) {
        NonNullList<ItemStack> items = NonNullList.withSize(contents.length, ItemStack.EMPTY);
        for (int slot = 0; slot < contents.length; slot++) {
            org.bukkit.inventory.ItemStack item = contents[slot];
            if (item != null) {
                items.set(slot, CraftItemStack.unwrap(item));
            }
        }
        this.shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        this.playerInventory.setChanged();
    }
}
