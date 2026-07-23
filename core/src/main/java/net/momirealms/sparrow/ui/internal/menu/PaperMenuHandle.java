package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common.ClientboundPingPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerClosePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetContentPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundOpenScreenPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundSetCursorItemPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.InventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.AbstractContainerMenuProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.ContainerSynchronizerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuSubclassFactory;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.RemoteSlotProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.paper.adventure.PaperAdventureProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 基于 Paper 容器协议实现的菜单句柄.
 *
 * <p>此实现只在玩家实体线程维护权威远端镜像, 并把需要跨越当前调用的数据包物品冻结为
 * 独立快照. Netty 入站消息由句柄自己的有界队列保序后交回实体线程消费.</p>
 */
@SuppressWarnings("UnstableApiUsage")
class PaperMenuHandle implements MenuHandle, MenuSubclassFactory.State {
    private static final int INCOMING_CAPACITY = 256; // 单个菜单会话最多暂存的入站消息数
    private static final Object EMPTY_ITEM = ItemStackProxy.INSTANCE.empty(); // NMS ItemStack.EMPTY

    private final PacketListener packets; // 安装入站捕获会话并发送出站协议包
    private final Player player;
    private final Object serverPlayer;
    private final Object menuType; // NMS MenuType<?>, 决定客户端打开的原版菜单类型
    private final int containerId; // 此次菜单会话独占的容器编号
    private final long generation; // 当前 Window 代际, 用于丢弃迟到的旧会话输入
    private final IncomingPacketQueue<MenuInput> incoming = new IncomingPacketQueue<>(INCOMING_CAPACITY); // Netty 到实体线程的有界 FIFO
    private final Object replacedMenu; // NMS AbstractContainerMenu, 打开前的活动菜单
    private final ProtocolInventoryView view; // 提供给 Bukkit 事件读取和临时写入的协议投影
    private final Object proxy; // NMS AbstractContainerMenu, 安装到玩家并禁用原版自动同步的生成菜单
    private final Object[] remoteSlots; // NMS RemoteSlot[], Paper 维护的客户端已知槽位哈希镜像
    private final Object remoteCursor; // NMS RemoteSlot, Paper 维护的客户端已知光标哈希镜像
    private final BitSet predictedSlots = new BitSet(); // 客户端预测声称发生变化的槽位
    private final BitSet forcedSlots = new BitSet(); // 即使内容相同也必须重发的槽位
    private final BitSet candidateSlots = new BitSet(); // 复用的本轮增量同步候选集合
    private final BitSet viewTouchedSlots = new BitSet(); // 复用的 Bukkit 事件视图写入集合
    private final BitSet changedSlots = new BitSet(); // 复用的本轮已发送或需恢复投影的槽位集合
    private final Object[] pendingRemoteItems; // NMS ItemStack[], 发送成功前暂存单槽包持有的物品快照

    private @Nullable PacketListener.Session session; // 当前入站捕获会话, 打开前和关闭后为空
    private Object cursor = EMPTY_ITEM; // NMS ItemStack, 实体线程已提交的菜单光标状态
    private boolean predictedCarried; // 客户端预测是否要求重新核对光标
    private boolean committed; // 初始打开批次是否已成功提交
    private boolean closed; // 菜单是否已关闭

    PaperMenuHandle(
            PacketListener packets,
            Player player,
            Object menuType,
            InventoryType inventoryType,
            org.bukkit.inventory.MenuType bukkitMenuType,
            int topSlots,
            long generation
    ) {
        this.packets = packets;
        this.player = player;
        this.serverPlayer = CraftEntityProxy.INSTANCE.entity(player);
        this.menuType = menuType;
        this.containerId = ServerPlayerProxy.INSTANCE.nextContainerCounter(this.serverPlayer);
        this.generation = generation;
        this.replacedMenu = ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer);
        this.view = new ProtocolInventoryView(player, topSlots, inventoryType, bukkitMenuType);
        this.proxy = MenuSubclassFactory.create(this.menuType, this.containerId, this);
        this.remoteSlots = new Object[topSlots + 36]; // NMS RemoteSlot[]
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            this.remoteSlots[slot] = this.createRemoteSlot();
        }
        this.pendingRemoteItems = new Object[this.remoteSlots.length]; // NMS ItemStack[]
        this.remoteCursor = this.createRemoteSlot();
    }

    @Override
    public int containerId() {
        return this.containerId;
    }

    @Override
    @NotNull
    public InventoryView view() {
        return this.view;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object carried() {
        return this.cursor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void carried(Object item) {
        this.cursor = item;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object emptyItem() {
        return EMPTY_ITEM;
    }

    @Override
    public int playerInventoryVersion() {
        Object inventory = ServerPlayerProxy.INSTANCE.inventory(this.serverPlayer); // NMS Inventory
        return InventoryProxy.INSTANCE.timesChanged(inventory);
    }

    @Override
    public int stateId() {
        return AbstractContainerMenuProxy.INSTANCE.stateId(this.proxy);
    }

    /**
     * 校验客户端操作基于当前 state id 后, 再吸收其非权威预测.
     */
    @Override
    public boolean accepts(@NotNull MenuInput.Common.Interaction interaction) {
        this.checkCommitted();
        if (interaction.containerId() != this.containerId) {
            return false;
        }
        if (interaction.stateId() != AbstractContainerMenuProxy.INSTANCE.stateId(this.proxy)) {
            return false;
        }
        if (interaction.prediction() instanceof ClientMenuPrediction prediction) {
            this.predictedCarried |= prediction.apply(this.remoteSlots, this.remoteCursor, this.predictedSlots);
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasInputOverflowed() {
        return this.incoming.hasOverflowed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NotNull
    public List<MenuInput> drainInputs(int limit) {
        return this.incoming.drain(this.generation, limit);
    }

    /**
     * 在安装入站捕获会话后替换服务端菜单, 再把打开与完整状态作为同一网络批次排队.
     *
     * <p>若写包失败, 必须恢复被替换的菜单并回滚捕获会话, 以免后续包落到失效 Window.</p>
     */
    @Override
    public void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull ItemStack cursor) {
        this.checkUsable();
        this.checkSlotCount(slots);

        // 先冻结完整内容, 使包、远端镜像与 Bukkit 事件视图共享同一份权威输入
        FullContents full = this.prepareFull(slots, cursor);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表

        // 先开始捕获入站容器包, 再把服务端活动菜单切换到代理.
        PacketListener.Session openedSession = this.packets.open(
                this.player,
                this.containerId,
                input -> this.incoming.offer(this.generation, input)
        );
        this.session = openedSession;
        ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);

        // 网络批次成功排入 event loop 后提交会话; 同步失败则完整恢复打开前状态
        try {
            this.packets.send(this.player, outgoing);
            openedSession.commit();
            this.commitFull(full);
            this.commitMenuDataPackets();
            this.view.initialize(slots, cursor, title);
            this.committed = true;
        } catch (RuntimeException | Error throwable) {
            openedSession.rollback();
            this.session = null;
            ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            throw throwable;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void synchronize(
            ItemStack @NotNull [] slots,
            @NotNull BitSet dirtySlots,
            @NotNull ItemStack cursor,
            boolean cursorDirty,
            boolean forceFull
    ) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        this.prepareMenuSynchronization(dirtySlots, forceFull);
        if (forceFull) {
            FullContents full = this.prepareFull(slots, cursor);
            ArrayList<Object> outgoing = new ArrayList<>(2); // NMS 客户端数据包列表
            outgoing.add(full.packet());
            this.appendMenuDataPackets(outgoing, true);
            this.packets.send(this.player, outgoing);
            this.commitFull(full);
            this.commitMenuDataPackets();
            this.view.initialize(slots, cursor, this.view.title());
            return;
        }
        this.synchronizeChanges(slots, dirtySlots, cursor, cursorDirty);
    }

    /**
     * 重发 OpenScreen 和完整内容, 因为客户端没有独立的标题更新包.
     */
    @Override
    public void updateTitle(
            @NotNull Component title,
            ItemStack @NotNull [] slots,
            @NotNull ItemStack cursor
    ) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        FullContents full = this.prepareFull(slots, cursor);
        ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表
        this.packets.send(this.player, outgoing);
        this.commitFull(full);
        this.commitMenuDataPackets();
        this.view.initialize(slots, cursor, title);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendPing(int id) {
        this.checkCommitted();
        this.packets.send(this.player, List.of(ClientboundPingPacketProxy.INSTANCE.newInstance(id)));
    }

    /**
     * 关闭会话并在仍由本代理持有菜单时恢复玩家库存菜单.
     *
     * <p>仅插件主动关闭才发送关闭包. 客户端已关闭或已被其他菜单替换时, 再写关闭包会干扰
     * 新会话, 因此只恢复服务端状态和库存快照.</p>
     */
    @Override
    public void close(@NotNull CloseMode mode) {
        if (this.closed) return;
        this.closed = true;
        // 无论后续关闭路径如何, 都先停止捕获本 Window 的入站包.
        this.incoming.close();
        PacketListener.Session previousSession = this.session;
        this.session = null;
        if (previousSession != null) {
            previousSession.close();
        }

        // 打开尚未提交时, 只需要恢复被替换的原菜单.
        if (!this.committed) {
            if (ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            }
            return;
        }
        // 新窗口已接管客户端时, 不能再发送旧窗口的关闭包.
        if (mode == CloseMode.REPLACED) {
            return;
        }
        if (ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer) != this.proxy) {
            return;
        }
        // 仅当前代理仍处于活动状态时才恢复库存菜单并发送最终快照.
        Object inventoryMenu = ServerPlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        ServerPlayerProxy.INSTANCE.containerMenu(this.serverPlayer, inventoryMenu);

        Throwable failure = null;
        if (mode == CloseMode.PLUGIN) {
            try {
                Object closePacket = ClientboundContainerClosePacketProxy.INSTANCE.newInstance(this.containerId);
                this.packets.send(this.player, List.of(closePacket));
            } catch (RuntimeException | Error throwable) {
                failure = throwable;
            }
        }
        try {
            AbstractContainerMenuProxy.INSTANCE.sendAllDataToRemote(inventoryMenu);
        } catch (RuntimeException | Error throwable) {
            if (failure == null) {
                failure = throwable;
            } else {
                failure.addSuppressed(throwable);
            }
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    /**
     * 在实体调度器停止后释放本地会话, 不再访问玩家或发送网络包.
     */
    @Override
    public void retire() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.incoming.close();
        PacketListener.Session previousSession = this.session;
        this.session = null;
        if (previousSession != null) {
            previousSession.close();
        }
    }

    /**
     * 构造一次打开或标题刷新所需的完整协议序列.
     */
    private List<Object> openPackets(Component title, FullContents full) {
        ArrayList<Object> outgoing = new ArrayList<>(3); // NMS 客户端数据包列表
        outgoing.add(ClientboundOpenScreenPacketProxy.INSTANCE.newInstance(
                this.containerId,
                this.menuType,
                PaperAdventureProxy.INSTANCE.asVanilla(title)
        ));
        outgoing.add(full.packet());
        this.appendMenuDataPackets(outgoing, true);
        return outgoing;
    }

    /**
     * 冻结一次完整状态并推进 NMS 菜单 state id.
     */
    private FullContents prepareFull(ItemStack[] slots, ItemStack cursor) {
        ArrayList<Object> items = new ArrayList<>(slots.length); // NMS ItemStack 包快照
        for (int index = 0; index < slots.length; index++) {
            items.add(ItemStackProxy.INSTANCE.copy(this.toClientItem(index, slots[index])));
        }
        Object menuCursor = ItemUtils.getItemStackNMSHandle(cursor); // 借用的 NMS ItemStack
        Object packetCursor = ItemStackProxy.INSTANCE.copy(menuCursor); // 包独占的 NMS ItemStack
        Object packet = ClientboundContainerSetContentPacketProxy.INSTANCE.newInstance(
                this.containerId,
                AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                items,
                packetCursor
        );
        return new FullContents(items, menuCursor, packet);
    }

    /**
     * 仅比较 dirty 槽位、客户端预测槽位和可选光标, 并在一个网络批次中提交差异.
     */
    private void synchronizeChanges(
            ItemStack[] slots,
            BitSet dirtySlots,
            ItemStack cursor,
            boolean cursorDirty
    ) {
        BitSet candidates = this.candidateSlots;
        candidates.clear();
        candidates.or(dirtySlots);
        candidates.or(this.predictedSlots);
        candidates.or(this.forcedSlots);
        this.view.drainTouchedSlots(this.viewTouchedSlots);
        candidates.or(this.viewTouchedSlots);
        boolean viewCursorTouched = this.view.takeCursorTouched();

        int candidateCount = candidates.cardinality();
        ArrayList<Object> outgoing = new ArrayList<>(candidateCount + 2); // NMS 客户端数据包列表
        BitSet changedSlots = this.changedSlots;
        changedSlots.clear();
        try {
            for (
                    int slot = candidates.nextSetBit(0);
                    slot >= 0 && slot < slots.length;
                    slot = candidates.nextSetBit(slot + 1)
            ) {
                Object item = this.toClientItem(slot, slots[slot]); // 借用的 NMS ItemStack
                if (!this.forcedSlots.get(slot) && RemoteSlotProxy.INSTANCE.matches(this.remoteSlots[slot], item)) {
                    continue;
                }
                // 当前目标版本的单槽包构造器会取得自己的物品副本, 这里不在比较前重复复制.
                Object packet = ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                        this.containerId,
                        AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                        slot,
                        item
                );
                outgoing.add(packet);
                changedSlots.set(slot);
                this.pendingRemoteItems[slot] = ClientboundContainerSetSlotPacketProxy.INSTANCE.item(packet);
            }

            boolean checkCursor = cursorDirty || this.predictedCarried || viewCursorTouched;
            boolean cursorChanged = false;
            Object sentCursor = this.cursor; // NMS ItemStack
            if (checkCursor) {
                sentCursor = ItemUtils.getItemStackNMSHandle(cursor);
                if (!RemoteSlotProxy.INSTANCE.matches(this.remoteCursor, sentCursor)) {
                    outgoing.add(ClientboundSetCursorItemPacketProxy.INSTANCE.newInstance(
                            ItemStackProxy.INSTANCE.copy(sentCursor)
                    ));
                    cursorChanged = true;
                }
            }

            this.appendMenuDataPackets(outgoing, false);

            if (!outgoing.isEmpty()) {
                this.packets.send(this.player, outgoing);
            }

            // 只有网络批次成功排入 event loop 后才提交远端镜像
            for (
                    int slot = changedSlots.nextSetBit(0);
                    slot >= 0;
                    slot = changedSlots.nextSetBit(slot + 1)
            ) {
                RemoteSlotProxy.INSTANCE.force(this.remoteSlots[slot], this.pendingRemoteItems[slot]);
            }
            if (checkCursor) {
                this.cursor = sentCursor;
            }
            if (cursorChanged) {
                RemoteSlotProxy.INSTANCE.force(this.remoteCursor, sentCursor);
            }
            changedSlots.or(this.viewTouchedSlots);
            this.view.apply(slots, changedSlots, cursor, cursorChanged || viewCursorTouched);
            this.predictedSlots.clear();
            this.predictedCarried = false;
            this.forcedSlots.andNot(changedSlots);
            this.commitMenuDataPackets();
        } finally {
            for (
                    int slot = changedSlots.nextSetBit(0);
                    slot >= 0;
                    slot = changedSlots.nextSetBit(slot + 1)
            ) {
                this.pendingRemoteItems[slot] = null;
            }
            candidates.clear();
            this.viewTouchedSlots.clear();
            changedSlots.clear();
        }
    }

    /**
     * 将一次完整发送提交为新的远端镜像.
     */
    private void commitFull(FullContents full) {
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            RemoteSlotProxy.INSTANCE.force(this.remoteSlots[slot], full.slots().get(slot));
        }
        RemoteSlotProxy.INSTANCE.force(this.remoteCursor, full.cursor());
        this.cursor = full.cursor();
        this.predictedSlots.clear();
        this.predictedCarried = false;
        this.forcedSlots.clear();
    }

    private Object createRemoteSlot() {
        Object synchronizer = ServerPlayerProxy.INSTANCE.containerSynchronizer(this.serverPlayer); // NMS ContainerSynchronizer
        Object slot = ContainerSynchronizerProxy.INSTANCE.createSlot(synchronizer); // NMS RemoteSlot
        RemoteSlotProxy.INSTANCE.force(slot, EMPTY_ITEM);
        return slot;
    }

    private void checkSlotCount(ItemStack[] slots) {
        if (slots.length != this.remoteSlots.length) {
            throw new IllegalArgumentException("menu requires " + this.remoteSlots.length + " slots, got " + slots.length);
        }
    }

    private void checkUsable() {
        if (this.closed || this.committed) {
            throw new IllegalStateException("menu is already open or closed");
        }
    }

    private void checkCommitted() {
        if (this.closed || !this.committed) {
            throw new IllegalStateException("menu is not open");
        }
    }

    /**
     * 把权威 Bukkit 物品转换为指定原始槽位的客户端显示物品.
     * 具体菜单可在不改变领域槽位状态的情况下提供协议占位物.
     *
     * @param rawSlot 原始窗口槽位
     * @param item 权威物品
     * @return 客户端显示物品
     */
    protected Object toClientItem(int rawSlot, ItemStack item) {
        return ItemUtils.getItemStackNMSHandle(item);
    }

    /**
     * 强制下一次增量同步重发指定槽位, 即使服务端远端镜像仍与权威物品相同.
     *
     * @param rawSlot 原始窗口槽位
     */
    protected final void forceRemoteSlot(int rawSlot) {
        this.forcedSlots.set(rawSlot);
    }

    /**
     * 在同步候选冻结前准备菜单专属状态.
     *
     * @param dirtySlots 本轮 dirty 槽位
     * @param forceFull 是否强制完整同步
     */
    protected void prepareMenuSynchronization(@NotNull BitSet dirtySlots, boolean forceFull) {
    }

    /**
     * 把菜单专属数据包追加到本轮统一网络批次.
     *
     * @param outgoing 本轮可变数据包列表
     * @param forceFull 是否强制完整同步
     */
    protected void appendMenuDataPackets(@NotNull List<Object> outgoing, boolean forceFull) {
    }

    /**
     * 在统一网络批次成功排入 Netty event loop 后提交菜单专属远端镜像.
     */
    protected void commitMenuDataPackets() {
    }

    /**
     * 一次完整同步所需的数据包快照和实体线程菜单状态.
     *
     * @param slots 冻结槽位
     * @param cursor 菜单在实体线程持有的光标状态.
     * @param packet 完整内容包
     */
    private record FullContents(
            List<Object> slots, // NMS ItemStack 包快照
            Object cursor, // NMS ItemStack 实体线程菜单状态
            Object packet // NMS ClientboundContainerSetContentPacket
    ) {
    }

}
