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
 * 把附魔展示状态转换为原版附魔台菜单协议.
 * <p>选项和 seed 存放在十个 container data slot 中. 变更只有在整个发包批次成功进入发送路径后
 * 才会清除 dirty 标记, 构包或发送失败时仍可在下一轮重试.
 */
@SuppressWarnings("UnstableApiUsage")
final class EnchantmentMenuHandleImpl extends ContainerMenuHandle implements EnchantmentMenuHandle {
    private static final Object ENCHANTMENT_REGISTRY = CraftRegistryProxy.INSTANCE.getMinecraftRegistry(RegistriesProxy.ENCHANTMENT); // Minecraft 附魔注册表
    private final DataSlots dataSlots = new DataSlots(); // 待同步的十个 container data slot

    /**
     * 创建附魔台菜单 handle, 并缓存当前服务器的附魔注册表.
     *
     * @param packets 菜单发包入口
     * @param player 查看菜单的玩家
     * @param generation 当前打开世代
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>禁用项映射为 {@code 0/-1/-1}, 无 clue 的启用项映射为 {@code cost/-1/-1}.
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEnchantmentSeed(int seed) {
        this.dataSlots.setEnchantmentSeed(seed);
    }

    /**
     * 上部槽位包会触发客户端 EnchantmentMenu 重新计算并覆盖选项, 因此随后需要恢复全部 data slot.
     *
     * @param dirtySlots 本轮 dirty 槽位
     * @param forceFull 是否强制完整同步
     */
    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        if (dirtySlots.get(0) || dirtySlots.get(1)) {
            this.dataSlots.notifyUpdateEnchantmentOptions();
        }
    }

    /**
     * 把本轮需要发送的 data slot 包追加到同一个菜单同步批次.
     *
     * @param outgoing 待发送的协议包列表
     * @param forceFull 是否忽略 dirty 状态并发送全部十项
     */
    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        // queue 只记录本批发送范围, dirty 状态要等数据包进入发送路径后才清除
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

    /**
     * 在整个菜单包批次成功交给发送路径后确认 data slot 状态.
     */
    @Override
    protected void commitPackets() {
        this.dataSlots.commit();
    }

    /**
     * 三个附魔选项和符文种子对应的十个 container data slot 一致状态.
     */
    static final class DataSlots {
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
        private final BitSet dirtySlots = new BitSet(DATA_SLOT_COUNT);  // 尚未成功发送的变更
        private final BitSet queuedSlots = new BitSet(DATA_SLOT_COUNT); // 当前批次记录的发送范围
        private boolean clientOptionsInvalid;                          // 客户端预测或输入槽更新可能覆盖了全部选项
        private boolean clientOptionsQueued;                           // 当前批次是否负责恢复全部客户端选项

        /**
         * 更新一个选项对应的 cost, clue ID 和 clue level.
         *
         * @param index 选项索引
         * @param cost 客户端显示的经验等级
         * @param clueId Minecraft 附魔注册表 ID, -1 表示无 clue
         * @param clueLevel tooltip 显示的附魔等级, -1 表示无 clue
         * @throws IndexOutOfBoundsException 当选项索引不在 [0, 3) 时
         */
        void setOption(int index, int cost, int clueId, int clueLevel) {
            if (index < 0 || index >= OPTION_COUNT) {
                throw new IndexOutOfBoundsException("enchantment option index out of bounds: " + index);
            }

            this.setValue(index, cost);
            this.setValue(CLUE_START_SLOT + index, clueId);
            this.setValue(CLUE_LEVEL_START_SLOT + index, clueLevel);
        }

        /**
         * 更新客户端符文文字使用的随机种子.
         *
         * @param seed 附魔种子
         */
        void setEnchantmentSeed(int seed) {
            this.setValue(ENCHANTMENT_SEED_SLOT, seed);
        }

        /**
         * 标记客户端本地附魔选项可能已被输入槽预测覆盖.
         */
        void notifyUpdateEnchantmentOptions() {
            this.clientOptionsInvalid = true;
        }

        /**
         * 记录下一批要发送的 data slot. 强制同步会包含全部十项.
         *
         * @param forceFull 是否发送全部 data slot
         */
        void queue(boolean forceFull) {
            this.queuedSlots.clear();
            this.clientOptionsQueued = forceFull || this.clientOptionsInvalid;
            if (this.clientOptionsQueued) {
                this.queuedSlots.set(0, DATA_SLOT_COUNT);
            } else {
                this.queuedSlots.or(this.dirtySlots);
            }
        }

        /**
         * 查找当前批次中不小于起始位置的下一个 data slot.
         *
         * @param fromIndex 搜索起始索引
         * @return 下一个 data slot, 不存在时返回 -1
         */
        int nextQueuedSlot(int fromIndex) {
            return this.queuedSlots.nextSetBit(fromIndex);
        }

        /**
         * 返回指定 data slot 的当前值.
         *
         * @param slot data slot 索引
         * @return 当前协议值
         * @throws IndexOutOfBoundsException 当索引不在 [0, 10) 时
         */
        int value(int slot) {
            if (slot < 0 || slot >= DATA_SLOT_COUNT)
                throw new IndexOutOfBoundsException("enchantment data slot out of bounds: " + slot);
            return this.values[slot];
        }

        /**
         * 确认当前批次已进入发送路径, 只清除该批次实际覆盖的 dirty 标记.
         */
        void commit() {
            this.dirtySlots.andNot(this.queuedSlots);
            this.queuedSlots.clear();
            if (this.clientOptionsQueued) {
                this.clientOptionsInvalid = false;
                this.clientOptionsQueued = false;
            }
        }

        /**
         * 在协议值实际变化时记录对应 data slot 为 dirty.
         *
         * @param slot data slot 索引
         * @param value 新协议值
         */
        private void setValue(int slot, int value) {
            if (this.values[slot] != value) {
                this.values[slot] = value;
                this.dirtySlots.set(slot);
            }
        }
    }
}
