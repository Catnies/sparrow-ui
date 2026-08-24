package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.CraftRegistryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.RegistryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.registries.RegistriesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetDataPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.window.EnchantmentWindow;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/**
 * 将三个附魔选项和符文种子投影为原版的十个 container data slot.
 * <p>dirty 状态在整个网络批次成功进入发送路径后提交.
 */
@SuppressWarnings("UnstableApiUsage")
final class EnchantmentMenuHandleImpl extends ContainerMenuHandle implements EnchantmentMenuHandle {
    private static final Object ENCHANTMENT_REGISTRY = CraftRegistryProxy.INSTANCE.getMinecraftRegistry(RegistriesProxy.ENCHANTMENT); // NMS 附魔注册表
    private final DataSlots dataSlots = new DataSlots();

    EnchantmentMenuHandleImpl(@NotNull PacketListener packets, @NotNull Player player, long generation) {
        super(
                packets,
                player,
                MenuTypeProxy.ENCHANTMENT,
                InventoryType.ENCHANTING,
                MenuType.ENCHANTMENT,
                2,
                generation
        );
    }

    @Override
    public void setOption(int index, @Nullable EnchantmentWindow.EnchantOption option) {
        // 原版以 cost 0 和 clue -1 表示不可用的空选项
        if (option == null) {
            this.dataSlots.setOption(index, 0, -1, -1);
            return;
        }
        // clue 可省略, 此时客户端仍显示 cost, 但不会生成附魔 tooltip
        if (option.clue() == null) {
            this.dataSlots.setOption(index, option.cost(), -1, -1);
            return;
        }
        // 客户端协议需要当前 Minecraft 注册表中的整数 ID, Bukkit Enchantment 不能直接下发
        Object enchantment = CraftRegistryProxy.INSTANCE.bukkitToMinecraft(option.clue());
        int clueId = RegistryProxy.INSTANCE.getId(ENCHANTMENT_REGISTRY, enchantment);
        if (clueId < 0) {
            throw new IllegalArgumentException("enchantment clue is not registered: " + option.clue().getKey());
        }
        this.dataSlots.setOption(index, option.cost(), clueId, option.clueLevel());
    }

    @Override
    public void setEnchantmentSeed(int seed) {
        this.dataSlots.setEnchantmentSeed(seed);
    }

    // 上部槽位包会触发客户端本地重算, 随后恢复全部 data slot.
    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (dirtySlots.get(0) || dirtySlots.get(1)) {
            this.dataSlots.notifyUpdateEnchantmentOptions();
        }
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        // queuedSlots 记录本批范围, dirtySlots 在提交阶段再清除.
        this.dataSlots.queue(forceFull);
        for (
                int slot = this.dataSlots.nextQueuedSlot(0);
                slot >= 0;
                slot = this.dataSlots.nextQueuedSlot(slot + 1)
        ) {
            outgoing.add(ClientboundContainerSetDataPacketProxy.INSTANCE.newInstance(
                    this.containerId(),
                    slot,
                    this.dataSlots.value(slot)
            ));
        }
    }

    @Override
    protected void commitPackets() {
        this.dataSlots.commit();
    }

    // 三个选项和一个符文种子共占十个 data slot.
    static final class DataSlots {
        // 原版 data slot 布局
        static final int OPTION_COUNT = 3;          // cost 使用 data slot 0-2
        static final int ENCHANTMENT_SEED_SLOT = 3; // seed 使用 data slot 3
        static final int CLUE_START_SLOT = 4;       // clue ID 使用 data slot 4-6
        static final int CLUE_LEVEL_START_SLOT = 7; // clue 等级使用 data slot 7-9
        static final int DATA_SLOT_COUNT = 10;      // 原版附魔台 data slot 总数

        // 初始值对应三个禁用选项和 seed 0
        private final int[] values = {
                0, 0, 0,
                0,
                -1, -1, -1,
                -1, -1, -1
        };

        // 待发送与当前批次
        private final BitSet dirtySlots = new BitSet(DATA_SLOT_COUNT);  // 尚未成功发送的变更
        private final BitSet queuedSlots = new BitSet(DATA_SLOT_COUNT); // 当前批次记录的发送范围
        private boolean clientOptionsInvalid;                          // 客户端预测或输入槽更新可能覆盖了全部选项
        private boolean clientOptionsQueued;                           // 当前批次是否负责恢复全部客户端选项

        void setOption(int index, int cost, int clueId, int clueLevel) {
            if (index < 0 || index >= OPTION_COUNT) {
                throw new IndexOutOfBoundsException("enchantment option index out of bounds: " + index);
            }

            this.setValue(index, cost);
            this.setValue(CLUE_START_SLOT + index, clueId);
            this.setValue(CLUE_LEVEL_START_SLOT + index, clueLevel);
        }

        void setEnchantmentSeed(int seed) {
            this.setValue(ENCHANTMENT_SEED_SLOT, seed);
        }

        // 输入槽更新会覆盖客户端本地选项, 下一批恢复全部十项.
        void notifyUpdateEnchantmentOptions() {
            this.clientOptionsInvalid = true;
        }

        void queue(boolean forceFull) {
            this.queuedSlots.clear();
            this.clientOptionsQueued = forceFull || this.clientOptionsInvalid;
            if (this.clientOptionsQueued) {
                this.queuedSlots.set(0, DATA_SLOT_COUNT);
            } else {
                this.queuedSlots.or(this.dirtySlots);
            }
        }

        int nextQueuedSlot(int fromIndex) {
            return this.queuedSlots.nextSetBit(fromIndex);
        }

        int value(int slot) {
            if (slot < 0 || slot >= DATA_SLOT_COUNT)
                throw new IndexOutOfBoundsException("enchantment data slot out of bounds: " + slot);
            return this.values[slot];
        }

        // 本批成功进入发送路径后, 清除实际覆盖的 dirty 标记.
        void commit() {
            this.dirtySlots.andNot(this.queuedSlots);
            this.queuedSlots.clear();
            if (this.clientOptionsQueued) {
                this.clientOptionsInvalid = false;
                this.clientOptionsQueued = false;
            }
        }

        private void setValue(int slot, int value) {
            if (this.values[slot] != value) {
                this.values[slot] = value;
                this.dirtySlots.set(slot);
            }
        }
    }
}
