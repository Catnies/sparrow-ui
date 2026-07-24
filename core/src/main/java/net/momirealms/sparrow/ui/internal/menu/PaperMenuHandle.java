package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.event.CraftEventFactoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common.ClientboundPingPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetContentPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundOpenScreenPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundSetCursorItemPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.InventoryProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.player.PlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.AbstractContainerMenuProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.ContainerSynchronizerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuSubclassFactory;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.RemoteSlotProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.paper.adventure.PaperAdventureProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 Paper 容器协议实现的菜单句柄.
 *
 * <p>此实现只在玩家实体线程维护权威远端镜像, 并把需要跨越当前调用的数据包物品冻结为
 * 独立快照. Netty 入站消息由句柄自己的有界队列保序后交回实体线程消费.</p>
 */
@SuppressWarnings("UnstableApiUsage")
class PaperMenuHandle implements MenuHandle, MenuSubclassFactory.State {
    private static final int INCOMING_CAPACITY = 256; // 单个菜单会话最多暂存的入站消息数

    protected final PacketListener packets; // 安装入站捕获会话并发送出站协议包
    protected final Player player;
    protected final Object serverPlayer;
    protected final Object menuType; // NMS MenuType<?>, 决定客户端打开的原版菜单类型
    protected final int containerId; // 此次菜单会话独占的容器编号
    protected final long generation; // 当前 Window 代际, 用于丢弃迟到的旧会话输入
    protected final IncomingPacketQueue<MenuInput> incoming = new IncomingPacketQueue<>(INCOMING_CAPACITY); // Netty 到实体线程的有界 FIFO
    protected Object replacedMenu; // NMS AbstractContainerMenu, 光标转移来源与打开失败时的恢复目标
    protected final ProtocolInventoryView view; // 提供给 Bukkit 事件读取和临时写入的协议投影
    protected final Object proxy; // NMS AbstractContainerMenu, 安装到玩家并禁用原版自动同步的生成菜单
    protected final Object[] remoteSlots; // NMS RemoteSlot[], Paper 维护的客户端已知槽位哈希镜像
    protected final Object remoteCursor; // NMS RemoteSlot, Paper 维护的客户端已知光标哈希镜像
    protected final BitSet predictedSlots = new BitSet(); // 客户端预测声称发生变化的槽位
    protected final BitSet forcedSlots = new BitSet(); // 即使内容相同也必须重发的槽位
    protected final BitSet candidateSlots = new BitSet(); // 复用的本轮增量同步候选集合
    protected final BitSet viewTouchedSlots = new BitSet(); // 复用的 Bukkit 事件视图写入集合
    protected final BitSet changedSlots = new BitSet(); // 复用的本轮已发送或需恢复投影的槽位集合
    protected final Object[] pendingRemoteItems; // NMS ItemStack[], 发送成功前暂存单槽包持有的物品快照

    protected @Nullable PacketListener.Session session; // 当前入站捕获会话, 打开前和关闭后为空
    protected Object actualCarried = ItemStackProxy.EMPTY; // NMS ItemStack, 代理菜单唯一持有的真实光标
    protected boolean predictedCarried; // 客户端预测是否要求重新核对光标
    protected boolean prepared; // 是否已经捕获待转移的真实光标
    protected boolean cursorClaimed; // 来源菜单的真实光标是否已经被清空
    protected boolean committed; // 初始打开批次是否已成功提交
    protected boolean closed; // 菜单是否已关闭

    PaperMenuHandle(
            PacketListener packets,
            Player player,
            Object menuType,
            InventoryType inventoryType,
            MenuType bukkitMenuType,
            int upperSize,
            long generation
    ) {
        this(packets, player, menuType, inventoryType, bukkitMenuType, upperSize, upperSize, generation);
    }

    PaperMenuHandle(
            PacketListener packets,
            Player player,
            Object menuType,
            InventoryType inventoryType,
            MenuType bukkitMenuType,
            int upperSize,
            int lowerStart,
            long generation
    ) {
        this.packets = packets;
        this.player = player;
        this.serverPlayer = CraftEntityProxy.INSTANCE.entity(player);
        this.menuType = menuType;
        this.containerId = ServerPlayerProxy.INSTANCE.nextContainerCounter(this.serverPlayer);
        this.generation = generation;
        this.replacedMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);
        this.view = new ProtocolInventoryView(player, upperSize, lowerStart, inventoryType, bukkitMenuType);
        this.proxy = MenuSubclassFactory.create(this.menuType, this.containerId, this);
        this.remoteSlots = new Object[this.view.countSlots()]; // NMS RemoteSlot[]
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            this.remoteSlots[slot] = this.createRemoteSlot();
        }
        this.remoteCursor = this.createRemoteSlot();
        this.pendingRemoteItems = new Object[this.remoteSlots.length]; // NMS ItemStack[]
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
        return this.actualCarried;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void carried(Object item) {
        this.actualCarried = item;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object emptyItem() {
        return ItemStackProxy.EMPTY;
    }

    /**
     * 先完成非 Sparrow 菜单的原生关闭，再捕获来源菜单中等待转移的真实光标.
     */
    @Override
    public void prepareOpen(boolean replacingWindow) {
        this.checkUsable();
        if (this.prepared) {
            throw new IllegalStateException("menu opening is already prepared");
        }

        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        Object currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer); // NMS AbstractContainerMenu
        boolean replacingProxy = replacingWindow && currentMenu.getClass() == this.proxy.getClass();
        if (!replacingProxy && currentMenu != inventoryMenu) {
            ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, InventoryCloseEvent.Reason.OPEN_NEW);
            currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);
        }

        this.replacedMenu = currentMenu;
        this.actualCarried = AbstractContainerMenuProxy.INSTANCE.getCarried(currentMenu);
        this.prepared = true;
    }

    @Override
    @NotNull
    public ItemStack cursor() {
        return ItemUtils.copyOrEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(this.actualCarried));
    }

    @Override
    public int playerInventoryVersion() {
        Object inventory = PlayerProxy.INSTANCE.inventory(this.serverPlayer); // NMS Inventory
        return InventoryProxy.INSTANCE.timesChanged(inventory);
    }

    @Override
    public int stateId() {
        return AbstractContainerMenuProxy.INSTANCE.getStateId(this.proxy);
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
        if (interaction.stateId() != AbstractContainerMenuProxy.INSTANCE.getStateId(this.proxy)) {
            return false;
        }
        if (interaction.prediction() instanceof ClientMenuPrediction prediction) {
            this.predictedCarried |= prediction.apply(this.remoteSlots, this.remoteCursor, this.predictedSlots);
        }
        this.handleAcceptedInteraction();
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
    public void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor) {
        this.checkUsable();
        this.checkPrepared();
        this.checkSlotCount(slots);

        // 先冻结完整内容, 使包、远端镜像与 Bukkit 事件视图共享同一份权威输入
        FullContents full = this.prepareFull(slots, cursor);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表

        // 先开始捕获入站容器包, 再把服务端活动菜单切换到代理.
        PacketListener.Session openedSession = this.packets.open(
                this.player,
                this.containerId,
                input -> this.incoming.offer(this.generation, input),
                this.discardedOutgoingPacketTypes()
        );
        this.session = openedSession;
        AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, ItemStackProxy.EMPTY);
        this.cursorClaimed = true;
        PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);

        // 网络批次成功排入 event loop 后提交会话; 同步失败则完整恢复打开前状态
        try {
            this.packets.send(this.player, outgoing);
            openedSession.commit();
            this.commitFull(full);
            this.commitMenuDataPackets();
            this.view.initialize(slots, cursor.actual(), title);
            this.committed = true;
            this.prepared = false;
            this.cursorClaimed = false;
        } catch (RuntimeException | Error throwable) {
            openedSession.rollback();
            this.session = null;
            PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            this.restorePreparedCursor();
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
            @NotNull CursorSnapshot cursor,
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
            this.view.initialize(slots, cursor.actual(), this.view.title());
            return;
        }
        this.synchronizeChanges(slots, dirtySlots, cursor, cursorDirty);
    }

    /**
     * 重发 OpenScreen 和完整内容, 因为客户端没有独立的标题更新包.
     */
    @Override
    public void updateTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        FullContents full = this.prepareFull(slots, cursor);
        PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表
        this.packets.send(this.player, outgoing);
        this.commitFull(full);
        this.commitMenuDataPackets();
        this.view.initialize(slots, cursor.actual(), title);
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
     * 按关闭原因和当前菜单所有权接入 Paper 的容器关闭生命周期.
     *
     * <p>代理已不再是活动菜单时，Paper 或替换窗口已经接管关闭流程。客户端关闭与断线不再
     * 发送关闭包，只发布 Bukkit 事件并执行 doCloseContainer；其余原因走服务端主动关闭。</p>
     */
    @Override
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
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
            if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            }
            this.restorePreparedCursor();
            return;
        }
        if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) != this.proxy) {
            return;
        }

        Throwable failure = null;
        try {
            if (reason == InventoryCloseEvent.Reason.PLAYER || reason == InventoryCloseEvent.Reason.DISCONNECT) {
                CraftEventFactoryProxy.INSTANCE.handleInventoryCloseEvent(this.serverPlayer, reason);
            } else {
                ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, reason);
            }
        } catch (RuntimeException | Error throwable) {
            failure = throwable;
        }
        if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
            try {
                ServerPlayerProxy.INSTANCE.doCloseContainer(this.serverPlayer);
            } catch (RuntimeException | Error throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
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
    private FullContents prepareFull(ItemStack[] slots, CursorSnapshot cursor) {
        ArrayList<Object> items = new ArrayList<>(slots.length); // NMS ItemStack 包快照
        for (int index = 0; index < slots.length; index++) {
            items.add(ItemStackProxy.INSTANCE.copy(this.toClientItem(index, slots[index])));
        }
        Object visualCursor = ItemUtils.getItemStackNMSHandle(cursor.visual()); // 借用的 NMS ItemStack
        Object packet = ClientboundContainerSetContentPacketProxy.INSTANCE.newInstance(
                this.containerId,
                AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                items,
                ItemStackProxy.INSTANCE.copy(visualCursor)
        );
        return new FullContents(items, visualCursor, packet);
    }

    /**
     * 仅比较 dirty 槽位、客户端预测槽位和可选光标, 并在一个网络批次中提交差异.
     */
    private void synchronizeChanges(
            ItemStack[] slots,
            BitSet dirtySlots,
            CursorSnapshot cursor,
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
            Object sentVisualCursor = ItemStackProxy.EMPTY; // NMS ItemStack
            if (checkCursor) {
                sentVisualCursor = ItemUtils.getItemStackNMSHandle(cursor.visual());
                if (!RemoteSlotProxy.INSTANCE.matches(this.remoteCursor, sentVisualCursor)) {
                    outgoing.add(ClientboundSetCursorItemPacketProxy.INSTANCE.newInstance(
                            ItemStackProxy.INSTANCE.copy(sentVisualCursor)
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
            if (cursorChanged) {
                RemoteSlotProxy.INSTANCE.force(this.remoteCursor, sentVisualCursor);
            }
            changedSlots.or(this.viewTouchedSlots);
            this.view.apply(slots, changedSlots, cursor.actual(), cursorChanged || viewCursorTouched);
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
        RemoteSlotProxy.INSTANCE.force(this.remoteCursor, full.visualCursor());
        this.predictedSlots.clear();
        this.predictedCarried = false;
        this.forcedSlots.clear();
    }

    private Object createRemoteSlot() {
        Object synchronizer = ServerPlayerProxy.INSTANCE.containerSynchronizer(this.serverPlayer); // NMS ContainerSynchronizer
        Object slot = ContainerSynchronizerProxy.INSTANCE.createSlot(synchronizer); // NMS RemoteSlot
        RemoteSlotProxy.INSTANCE.force(slot, ItemStackProxy.EMPTY);
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

    private void checkPrepared() {
        if (!this.prepared) {
            throw new IllegalStateException("menu opening has not been prepared");
        }
    }

    private void checkCommitted() {
        if (this.closed || !this.committed) {
            throw new IllegalStateException("menu is not open");
        }
    }

    private void restorePreparedCursor() {
        if (!this.prepared) {
            return;
        }
        if (this.cursorClaimed) {
            AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, this.actualCarried);
        }
        this.actualCarried = ItemStackProxy.EMPTY;
        this.prepared = false;
        this.cursorClaimed = false;
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
     * 返回已提交菜单关闭时是否应恢复被当前会话覆盖的出站状态.
     *
     * @param packetType 被覆盖的包类型
     * @return 当前菜单负责恢复时为 true
     */
    final boolean shouldRestoreOutgoing(@NotNull Class<?> packetType) {
        if (!this.committed || this.closed) {
            return false;
        }
        PacketListener.Session currentSession = this.session;
        return currentSession == null || !currentSession.replacementDiscardsOutgoing(packetType);
    }

    /**
     * 返回活动期间需要屏蔽的原版出站包类型.
     *
     * @return 不可修改的包类型集合
     */
    @NotNull
    protected Set<Class<?>> discardedOutgoingPacketTypes() {
        return Set.of();
    }

    /**
     * 在公共容器交互通过 state id 校验并吸收客户端预测后更新菜单专属状态.
     */
    protected void handleAcceptedInteraction() {
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
     * 一次完整同步所需的数据包槽位与可视光标快照.
     *
     * @param slots 冻结槽位
     * @param visualCursor 仅发送给客户端的可视光标
     * @param packet 完整内容包
     */
    private record FullContents(
            List<Object> slots, // NMS ItemStack 包快照
            Object visualCursor, // NMS ItemStack 可视光标
            Object packet // NMS ClientboundContainerSetContentPacket
    ) {
    }

}
