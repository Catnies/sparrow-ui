package net.momirealms.sparrow.ui.window.handle;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.network.filter.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.network.filter.ClientboundStateProjection;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.event.CraftEventFactoryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.common.ClientboundPingPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetContentPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundContainerSetSlotPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundOpenScreenPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundSetCursorItemPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

@SuppressWarnings("UnstableApiUsage")
class ContainerMenuHandle implements MenuHandle, MenuSubclassFactory.State {
    private static final int INCOMING_CAPACITY = 256;
    private static final int OFF_HAND_SLOT = 45;

    // 会话身份与协议入口
    private final MenuPacketGateway packets;
    private final Player player;
    private final Object serverPlayer;
    private final Object menuType; // NMS MenuType<?>
    private final int containerId;
    private final long generation; // Window 代际, 用于丢弃旧会话的迟到输入
    private final IncomingPacketQueue<MenuInput> incoming = new IncomingPacketQueue<>(INCOMING_CAPACITY);
    private final ProtocolInventoryView view;
    private final Object proxy; // 禁用原版自动同步的 NMS AbstractContainerMenu 代理

    // 客户端已知状态
    private final Object[] remoteSlots; // NMS RemoteSlot[]
    private final Object remoteCursor;  // NMS RemoteSlot
    private final Object remoteOffHand; // NMS RemoteSlot

    // 增量同步工作区
    private final BitSet predictedSlots = new BitSet();
    private final BitSet forcedSlots = new BitSet();
    private final BitSet candidateSlots = new BitSet();
    private final BitSet viewTouchedSlots = new BitSet();
    private final BitSet changedSlots = new BitSet();
    private final Object[] pendingRemoteItems; // 单槽包进入发送路径前暂存的 NMS ItemStack[]
    private final ItemStack[] alignedSlots;    // 已与客户端对齐的渲染引用, 相同引用可跳过比较

    // 菜单交接与生命周期
    private Object replacedMenu; // 光标来源与打开失败时的 NMS 菜单恢复目标
    private @Nullable MenuPacketGateway.Session session;
    private Object actualCarried = ItemStackProxy.EMPTY; // 代理菜单实际持有的 NMS ItemStack
    private boolean predictedCarried;
    private boolean externalCarried;
    private boolean offHandDirty;
    private boolean cursorClaimed;
    private Lifecycle lifecycle = Lifecycle.CREATED;

    ContainerMenuHandle(
            MenuPacketGateway packets,
            Player player,
            Object menuType,
            InventoryType inventoryType,
            MenuType bukkitMenuType,
            int upperSize,
            long generation
    ) {
        this(packets, player, menuType, inventoryType, bukkitMenuType, upperSize, upperSize, generation);
    }

    ContainerMenuHandle(
            MenuPacketGateway packets,
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
        // 每个协议槽位, 光标和副手各自维护一份客户端已知状态.
        this.remoteSlots = new Object[this.view.countSlots()]; // NMS RemoteSlot[]
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            this.remoteSlots[slot] = this.createRemoteSlot();
        }
        this.remoteCursor = this.createRemoteSlot();
        this.remoteOffHand = this.createRemoteSlot();
        this.pendingRemoteItems = new Object[this.remoteSlots.length]; // NMS ItemStack[]
        this.alignedSlots = new ItemStack[this.remoteSlots.length];
    }

    // 关闭不参与直接交接的原版菜单, 再接管来源菜单光标.
    @Override
    public void prepareOpen(boolean replacingWindow) {
        if (this.lifecycle != Lifecycle.CREATED) {
            throw new IllegalStateException("menu opening cannot be prepared in state " + this.lifecycle);
        }

        // 同类代理可直接交接, 其他菜单先走完原版关闭流程.
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer);   // NMS AbstractContainerMenu
        Object currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);     // NMS AbstractContainerMenu
        boolean replacingProxy = replacingWindow && currentMenu.getClass() == this.proxy.getClass();
        if (!replacingProxy && currentMenu != inventoryMenu) {
            ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, InventoryCloseEvent.Reason.OPEN_NEW);
            currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);
        }

        // 保留来源菜单, 打开失败时光标仍有明确归处.
        this.replacedMenu = currentMenu;
        this.actualCarried = AbstractContainerMenuProxy.INSTANCE.getCarried(currentMenu);
        this.lifecycle = Lifecycle.PREPARED;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现先装好入站捕获会话, 把服务端活动菜单换成代理菜单, 再把打开包和完整状态
     * 作为一个网络批次发出. 发不出去就整体回滚: 恢复原菜单, 撤销捕获会话, 归还光标.
     */
    @Override
    public void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor) {
        if (this.lifecycle != Lifecycle.PREPARED) {
            throw new IllegalStateException("menu cannot be opened in state " + this.lifecycle);
        }
        this.checkSlotCount(slots);

        // 从同一份服务端输入准备数据包, 客户端已知状态和 Bukkit 事件副本.
        FullContents full = this.prepareFullContents(slots, cursor);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表

        // 先捕获入站容器包, 再把服务端活动菜单切换到代理.
        MenuPacketGateway.Session openedSession = this.packets.open(
                this.player,
                this.containerId,
                input -> this.incoming.offer(this.generation, input),
                this.clientboundPacketFilter()
        );
        this.session = openedSession;
        AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, ItemStackProxy.EMPTY);
        // 来源为玩家背包菜单时恢复其远端更新.
        AbstractContainerMenuProxy.INSTANCE.resumeRemoteUpdates(this.replacedMenu);
        this.cursorClaimed = true;
        PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);

        // 网络批次排入 event loop 后提交会话, 失败则恢复打开前状态.
        try {
            this.packets.send(this.player, outgoing);
            openedSession.commit();
            this.commitFullContents(slots, full);
            this.view.initialize(slots, cursor.actual(), title);
            this.lifecycle = Lifecycle.COMMITTED;
            this.cursorClaimed = false;
        } catch (RuntimeException | Error throwable) {
            openedSession.rollback();
            this.session = null;
            PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            this.lifecycle = Lifecycle.CREATED;
            this.restorePreparedCursor();
            throw throwable;
        }
    }

    // 子类自行投影的客户端状态需要过滤原版同类数据包.
    @Nullable
    protected ClientboundPacketFilter clientboundPacketFilter() {
        return null;
    }

    // 候选槽位汇总前, 先让子类补充菜单专属失效状态.
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
    }

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
        this.prepareSynchronize(dirtySlots, forceFull);
        if (forceFull) {
            // 全量批次进入发送路径后, 整体替换客户端已知状态.
            FullContents full = this.prepareFullContents(slots, cursor);
            ArrayList<Object> outgoing = new ArrayList<>(3); // NMS 客户端数据包列表
            outgoing.add(full.packet());
            outgoing.add(full.offHandPacket());
            this.submitPackets(outgoing, true);
            this.packets.send(this.player, outgoing);
            this.commitFullContents(slots, full);
            this.view.initialize(slots, cursor.actual(), this.view.title());
            return;
        }
        this.synchronizeChanges(slots, dirtySlots, cursor, cursorDirty);
    }

    /**
     * 将菜单专属数据包加入本轮批次, 并记录待提交状态.
     * <p>批次进入 Netty event loop 后调用 {@link #commitPackets()}.
     * {@code forceFull} 时必须提供客户端重建专属状态所需的完整数据.
     *
     * @param outgoing 本轮可变数据包列表
     * @param forceFull 是否强制完整同步
     */
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
    }

    /**
     * 提交上一轮 {@link #submitPackets(List, boolean)} 记录的状态.
     * <p>调用只表示批次已进入网络发送路径, 不表示客户端已收到或处理.
     */
    protected void commitPackets() {
    }

    /**
     * 将服务端渲染结果投影为客户端 NMS ItemStack.
     * <p>子类可返回协议占位物, Window 槽位内容保持不变.
     * <p><strong>同一渲染引用的投影必须稳定.</strong> 菜单状态影响投影时, 状态变化必须调用
     * {@link #forceRemoteSlot(int)} 重发对应槽位.
     *
     * @param rawSlot 协议槽位(raw slot)
     * @param item 服务端槽位渲染结果
     * @return 客户端显示物品
     */
    protected Object toClientItem(int rawSlot, ItemStack item) {
        return ItemUtils.getItemStackHandle(item);
    }

    // 写入下一次事件要读取的副本前, 菜单必须仍处于打开状态.
    @Override
    public void resetBukkitEventView(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        this.view.resetForEvent(slots, renderedSlots, cursor);
    }

    // 事件中关闭 Window 后, 事件写入仍要取回, 这里不检查菜单状态.
    @Override
    @Nullable
    public ItemStack takeBukkitEventCursor() {
        return this.view.takeEventCursor();
    }

    // 与光标相同, 事件槽位写入不受事件内关闭 Window 影响.
    @Override
    public void drainBukkitEventSlots(@NotNull BitSet destination) {
        this.view.drainEventTouchedSlots(destination);
    }

    // 客户端没有独立标题更新包, 重发 OpenScreen 与完整内容.
    @Override
    public void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        FullContents full = this.prepareFullContents(slots, cursor);
        PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表
        this.packets.send(this.player, outgoing);
        this.commitFullContents(slots, full);
        this.view.initialize(slots, cursor.actual(), title);
    }

    @Override
    public void sendPing(int id) {
        this.checkCommitted();
        this.packets.send(this.player, List.of(ClientboundPingPacketProxy.INSTANCE.newInstance(id)));
    }

    /**
     * 按关闭原因和当前活动菜单的所有权接入 Paper 容器关闭流程.
     * <p>会话释放后, 尚未被新菜单接管的客户端投影恢复为原版状态. 断线关闭不再发包.
     *
     * @param reason 关闭原因
     */
    @Override
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
        if (this.lifecycle == Lifecycle.CLOSED) {
            return;
        }
        // 先停止入站捕获, 后续关闭步骤即使失败也不会再接收消息.
        Lifecycle previous = this.lifecycle;
        ClientboundStateProjection releasedProjection = null;
        if (previous == Lifecycle.COMMITTED && reason != InventoryCloseEvent.Reason.DISCONNECT) {
            MenuPacketGateway.Session currentSession = this.session;
            if (currentSession != null) {
                releasedProjection = currentSession.releasedClientboundStateProjection();
            }
        }
        this.lifecycle = Lifecycle.CLOSED;
        Throwable failure = ThrowableUtils.captureUnchecked(null, this::closeSession);
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        // 打开尚未提交时恢复原菜单和已接管光标.
        if (previous != Lifecycle.COMMITTED) {
            if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            }
            this.restorePreparedCursor();
        }
        // 当前仍由代理菜单持有会话时执行 Paper 关闭流程.
        else if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
            try {
                // 玩家关闭和断线先派发 Bukkit 事件, 主动关闭则走服务端入口.
                if (reason == InventoryCloseEvent.Reason.PLAYER || reason == InventoryCloseEvent.Reason.DISCONNECT) {
                    CraftEventFactoryProxy.INSTANCE.handleInventoryCloseEvent(this.serverPlayer, reason);
                } else {
                    ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, reason);
                }
            } catch (RuntimeException | Error throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
            // 事件处理器未替换菜单时完成底层容器关闭.
            if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                try {
                    ServerPlayerProxy.INSTANCE.doCloseContainer(this.serverPlayer);
                } catch (RuntimeException | Error throwable) {
                    failure = ThrowableUtils.combine(failure, throwable);
                }
            }
        }
        ThrowableUtils.throwIfUnchecked(failure);
        // 回到玩家背包菜单后重发完整状态, 清掉 Window 投影.
        if (reason != InventoryCloseEvent.Reason.DISCONNECT && PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == inventoryMenu) {
            try {
                AbstractContainerMenuProxy.INSTANCE.sendAllDataToRemote(inventoryMenu);
            } catch (RuntimeException | Error throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
        }
        // 新会话未接管的客户端投影在旧容器关闭后恢复.
        if (releasedProjection != null) {
            this.packets.send(this.player, List.of(releasedProjection.createNativeRestorePacket()));
        }
    }

    // 玩家线程已不可用, 这里只关闭独立的入站捕获资源.
    @Override
    public void retire() {
        if (this.lifecycle != Lifecycle.CLOSED) {
            this.lifecycle = Lifecycle.CLOSED;
            this.closeSession();
        }
    }

    @Override
    public boolean accepts(@NotNull MenuInput.Common.Interaction interaction) {
        this.checkCommitted();
        // 原版允许过时 state id 继续进入预测复核, 此处只按容器编号隔离会话.
        if (interaction.containerId() != this.containerId) return false;
        // F 键会本地预测副手交换, 下一轮无条件核对副手.
        if (interaction instanceof MenuInput.Common.Click click && click.clickType() == ClickType.SWAP_OFFHAND) {
            this.offHandDirty = true;
        }
        // 将客户端声称的槽位与光标变化并入复核集合.
        if (interaction.prediction() instanceof ClientMenuPrediction prediction) {
            this.predictedCarried |= prediction.apply(this.remoteSlots, this.remoteCursor, this.predictedSlots);
        }
        // 子类在预测吸收完成后更新菜单专属状态.
        this.handleAcceptedInteraction();
        return true;
    }

    // 交互归属确认并吸收预测后调用.
    protected void handleAcceptedInteraction() {
    }

    @Override
    @NotNull
    public List<MenuInput> drainInputs(int limit) {
        return this.incoming.drain(this.generation, limit);
    }

    @Override
    public boolean hasInputOverflowed() {
        return this.incoming.hasOverflowed();
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

    @Override
    public int stateId() {
        return AbstractContainerMenuProxy.INSTANCE.getStateId(this.proxy);
    }

    @Override
    @NotNull
    public ItemStack cursor() {
        return ItemUtils.copyOrEmpty(CraftItemStackProxy.INSTANCE.asCraftMirror(this.actualCarried));
    }

    @Override
    public void cursor(@NotNull ItemStack cursor) {
        // unwrap 后立即复制, 菜单不持有调用方实例.
        this.actualCarried = cursor.isEmpty()
                ? ItemStackProxy.EMPTY
                : ItemStackProxy.INSTANCE.copy(ItemUtils.getItemStackHandle(cursor));
    }

    @Override
    public Object carried() {
        return this.actualCarried;
    }

    @Override
    @NotNull
    public Object unsafeCursor() {
        return this.carried();
    }

    // 外部 NMS/CraftBukkit 写入会自行发送实际光标, 标记后由本轮同步恢复视觉光标.
    @Override
    public void carried(Object item) {
        this.actualCarried = item;
        this.externalCarried = true;
    }

    @Override
    public Object emptyItem() {
        return ItemStackProxy.EMPTY;
    }

    // 菜单专属状态改变投影时, 强制下一轮重发对应槽位.
    protected final void forceRemoteSlot(int rawSlot) {
        this.forcedSlots.set(rawSlot);
    }

    protected final void sendClientboundPacket(@NotNull Object packet) {
        this.packets.send(this.player, List.of(packet));
    }

    protected final int incrementStateId() {
        return AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy);
    }

    // OpenScreen 必须先于完整内容和菜单专属状态发送.
    private List<Object> openPackets(Component title, FullContents full) {
        ArrayList<Object> outgoing = new ArrayList<>(4); // NMS 客户端数据包列表
        outgoing.add(ClientboundOpenScreenPacketProxy.INSTANCE.newInstance(
                this.containerId,
                this.menuType,
                PaperAdventureProxy.INSTANCE.asVanilla(title)
        ));
        outgoing.add(full.packet());
        outgoing.add(full.offHandPacket());
        // 菜单专属全量状态与打开包共用一个网络批次.
        this.submitPackets(outgoing, true);
        return outgoing;
    }

    // 为完整内容包复制槽位和视觉光标, 并推进菜单 state id.
    private FullContents prepareFullContents(ItemStack[] slots, CursorSnapshot cursor) {
        // 数据包与客户端已知状态共用本批发送副本.
        ArrayList<Object> items = new ArrayList<>(slots.length); // NMS ItemStack 数据包副本
        for (int index = 0; index < slots.length; index++) {
            items.add(ItemStackProxy.INSTANCE.copy(this.toClientItem(index, slots[index])));
        }
        Object visualCursor = ItemUtils.getItemStackHandle(cursor.visual()); // 借用的 NMS ItemStack
        Object packet = ClientboundContainerSetContentPacketProxy.INSTANCE.newInstance(
                this.containerId,
                AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                items,
                ItemStackProxy.INSTANCE.copy(visualCursor)
        );
        return new FullContents(
                items,
                visualCursor,
                packet,
                this.createOffHandPacket(ItemUtils.getPlayerItemStackHandle(this.player, EquipmentSlot.OFF_HAND))
        );
    }

    // 完整批次进入发送路径后, 对齐所有远端状态并清空增量标记.
    private void commitFullContents(ItemStack[] slots, FullContents full) {
        // 槽位, 光标和副手对齐到刚进入发送路径的内容.
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            RemoteSlotProxy.INSTANCE.force(this.remoteSlots[slot], full.slots().get(slot));
            this.alignedSlots[slot] = slots[slot];
        }
        RemoteSlotProxy.INSTANCE.force(this.remoteCursor, full.visualCursor());
        RemoteSlotProxy.INSTANCE.force(this.remoteOffHand, ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(full.offHandPacket()));
        // 全量状态覆盖此前的预测与强制重发要求.
        this.predictedSlots.clear();
        this.predictedCarried = false;
        this.externalCarried = false;
        this.offHandDirty = false;
        this.forcedSlots.clear();
        this.commitPackets();
    }

    /**
     * 将 dirty, 客户端预测与强制复核项合并为一个增量网络批次.
     * <p>客户端已知状态在批次成功进入发送路径后更新.
     *
     * @param slots 服务端槽位渲染结果
     * @param dirtySlots 本轮脏槽位
     * @param cursor 菜单实际光标与客户端显示光标
     * @param cursorDirty 这一轮是否需要核对光标
     */
    private void synchronizeChanges(
            ItemStack[] slots,
            BitSet dirtySlots,
            CursorSnapshot cursor,
            boolean cursorDirty
    ) {
        // 汇总 dirty, 客户端预测, 强制重发与 Bukkit 事件写入.
        BitSet candidates = this.candidateSlots;
        candidates.clear();
        candidates.or(dirtySlots);
        candidates.or(this.predictedSlots);
        candidates.or(this.forcedSlots);
        this.view.drainTouchedSlots(this.viewTouchedSlots);
        candidates.or(this.viewTouchedSlots);
        boolean viewCursorTouched = this.view.takeCursorTouched();

        int candidateCount = candidates.cardinality();
        ArrayList<Object> outgoing = new ArrayList<>(candidateCount + 3); // NMS 客户端数据包列表
        BitSet changedSlots = this.changedSlots;
        changedSlots.clear();
        Object sentOffHand = null; // NMS ItemStack
        try {
            // 逐槽比较客户端已知状态, 为真正变化的槽位构造单槽包.
            for (
                    int slot = candidates.nextSetBit(0);
                    slot >= 0 && slot < slots.length;
                    slot = candidates.nextSetBit(slot + 1)
            ) {
                ItemStack rendered = slots[slot];
                // 普通 dirty 通知仍指向同一渲染引用时可以跳过投影.
                boolean mustVerify = this.forcedSlots.get(slot) || this.predictedSlots.get(slot) || this.viewTouchedSlots.get(slot);
                if (!mustVerify && this.alignedSlots[slot] == rendered) {
                    continue;
                }

                Object item = this.toClientItem(slot, rendered); // 借用的 NMS ItemStack
                if (!this.forcedSlots.get(slot) && RemoteSlotProxy.INSTANCE.matches(this.remoteSlots[slot], item)) {
                    // 客户端已经持有相同内容, 更新引用对齐缓存即可.
                    this.alignedSlots[slot] = rendered;
                    continue;
                }
                // 单槽包构造器会持有自己的物品副本.
                Object packet = ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                        this.containerId,
                        AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy),
                        slot,
                        item
                );
                outgoing.add(packet);
                changedSlots.set(slot);
                this.pendingRemoteItems[slot] = ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(packet);
            }

            // 副手被预测修改或与已知状态不同时重发.
            Object offHand = ItemUtils.getPlayerItemStackHandle(this.player, EquipmentSlot.OFF_HAND); // 借用的 NMS ItemStack
            if (this.offHandDirty || !RemoteSlotProxy.INSTANCE.matches(this.remoteOffHand, offHand)) {
                Object packet = this.createOffHandPacket(offHand);
                outgoing.add(packet);
                sentOffHand = ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(packet);
            }

            // 光标存在服务端或客户端侧写入迹象时才核对.
            boolean checkCursor = cursorDirty || this.predictedCarried || this.externalCarried || viewCursorTouched;
            boolean cursorChanged = false;
            Object sentVisualCursor = ItemStackProxy.EMPTY; // NMS ItemStack
            if (checkCursor) {
                sentVisualCursor = ItemUtils.getItemStackHandle(cursor.visual());
                if (!RemoteSlotProxy.INSTANCE.matches(this.remoteCursor, sentVisualCursor)) {
                    outgoing.add(ClientboundSetCursorItemPacketProxy.INSTANCE.newInstance(
                            ItemStackProxy.INSTANCE.copy(sentVisualCursor)
                    ));
                    cursorChanged = true;
                }
            }

            // 菜单专属状态附加到同一批次.
            this.submitPackets(outgoing, false);

            if (!outgoing.isEmpty()) {
                this.packets.send(this.player, outgoing);
            }

            // 网络批次进入 event loop 后发布新的客户端已知状态.
            for (
                    int slot = changedSlots.nextSetBit(0);
                    slot >= 0;
                    slot = changedSlots.nextSetBit(slot + 1)
            ) {
                RemoteSlotProxy.INSTANCE.force(this.remoteSlots[slot], this.pendingRemoteItems[slot]);
                this.alignedSlots[slot] = slots[slot];
            }
            if (cursorChanged) {
                RemoteSlotProxy.INSTANCE.force(this.remoteCursor, sentVisualCursor);
            }
            if (sentOffHand != null) {
                RemoteSlotProxy.INSTANCE.force(this.remoteOffHand, sentOffHand);
                this.offHandDirty = false;
            }
            // Bukkit 事件副本按实际发送和事件触碰范围对齐.
            changedSlots.or(this.viewTouchedSlots);
            this.view.apply(slots, changedSlots, cursor.actual(), cursorChanged || viewCursorTouched);
            // 预测已经消化, 强制标记保留本轮未覆盖的槽位.
            this.predictedSlots.clear();
            this.predictedCarried = false;
            this.externalCarried = false;
            this.forcedSlots.andNot(changedSlots);
            this.commitPackets();
        } finally {
            // 复用缓冲不得带入下一轮同步.
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

    // 新 RemoteSlot 先强制为空, 第一次比较不会继承未知状态.
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

    private void checkCommitted() {
        if (this.lifecycle != Lifecycle.COMMITTED) {
            throw new IllegalStateException("menu is not open");
        }
    }

    // 入站队列先关闭, Session 随后停止网络层捕获.
    private void closeSession() {
        this.incoming.close();
        MenuPacketGateway.Session previousSession = this.session;
        this.session = null;
        if (previousSession != null) {
            previousSession.close();
        }
    }

        // 只有打开流程实际清空过来源光标时才归还.
    private void restorePreparedCursor() {
        if (this.cursorClaimed) {
            AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, this.actualCarried);
        }
        this.actualCarried = ItemStackProxy.EMPTY;
        this.cursorClaimed = false;
    }

    // 副手属于玩家背包菜单, 数据包使用背包菜单的 container id 和 state id.
    private Object createOffHandPacket(Object offHand) {
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        return ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                AbstractContainerMenuProxy.INSTANCE.containerId(inventoryMenu),
                AbstractContainerMenuProxy.INSTANCE.incrementStateId(inventoryMenu),
                OFF_HAND_SLOT,
                offHand
        );
    }

    // CREATED -> PREPARED -> COMMITTED, 任意阶段都可进入 CLOSED.
    private enum Lifecycle {
        CREATED,   // 刚创建, 还没开始打开
        PREPARED,  // 打开预备完成, 光标已接管
        COMMITTED, // 初始批次发送成功, 菜单正式打开
        CLOSED     // 已关闭
    }

    /**
     * 一次完整同步所需的槽位和客户端显示光标副本.
     *
     * @param slots 本批发送的槽位内容
     * @param visualCursor 仅发送给客户端的可视光标
     * @param packet 完整内容包
     * @param offHandPacket 玩家原生 inventory menu 的副手包
     */
    private record FullContents(
            List<Object> slots, // NMS ItemStack 数据包副本
            Object visualCursor, // NMS ItemStack 可视光标
            Object packet, // NMS ClientboundContainerSetContentPacket
            Object offHandPacket // NMS ClientboundContainerSetSlotPacket
    ) {
    }
}
