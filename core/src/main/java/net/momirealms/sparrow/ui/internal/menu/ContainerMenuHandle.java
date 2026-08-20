package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.internal.network.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.internal.network.ClientboundStateProjection;
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

    private final PacketListener packets;
    private final Player player;
    private final Object serverPlayer;
    private final Object menuType; // NMS MenuType<?>, 决定客户端打开的原版菜单类型
    private final int containerId; // 此次菜单会话独占的容器编号
    private final long generation; // 当前 Window 代际, 用于丢弃迟到的旧会话输入
    private final IncomingPacketQueue<MenuInput> incoming = new IncomingPacketQueue<>(INCOMING_CAPACITY); // Netty 到实体线程的有界队列
    private final ProtocolInventoryView view; // 提供给 Bukkit 事件读取和临时写入的 InventoryView
    private final Object proxy; // NMS AbstractContainerMenu, 安装到玩家并禁用原版自动同步的ASM生成实现类菜单
    private final Object[] remoteSlots; // NMS RemoteSlot[], Paper 维护的客户端已知槽位状态
    private final Object remoteCursor;  // NMS RemoteSlot, Paper 维护的客户端已知光标状态
    private final Object remoteOffHand; // NMS RemoteSlot, Paper 维护的玩家副手客户端已知状态
    private final BitSet predictedSlots = new BitSet();     // 客户端预测声称发生变化的槽位
    private final BitSet forcedSlots = new BitSet();        // 即使内容相同也必须重发的槽位
    private final BitSet candidateSlots = new BitSet();     // 复用的本轮增量同步候选集合
    private final BitSet viewTouchedSlots = new BitSet();   // 复用的 Bukkit 事件状态副本写入集合
    private final BitSet changedSlots = new BitSet();       // 复用的本轮已发送或需恢复 Bukkit 事件状态副本的槽位集合
    private final Object[] pendingRemoteItems; // NMS ItemStack[], 进入发送路径前暂存单槽包持有的物品副本
    private final ItemStack[] alignedSlots;    // 已确认与客户端已知状态一致的服务端渲染结果, 按引用判断内容有没有变

    private Object replacedMenu; // NMS AbstractContainerMenu, 光标转移来源与打开失败时的恢复目标
    private @Nullable PacketListener.Session session;       // 当前入站捕获会话, 打开前和关闭后为空
    private Object actualCarried = ItemStackProxy.EMPTY;    // NMS ItemStack, 代理菜单实际持有的光标
    private boolean predictedCarried;   // 客户端预测是否要求重新核对光标
    private boolean externalCarried;    // 外部直接写过菜单光标, 它多半自带一个协议包会改掉客户端显示
    private boolean offHandDirty;       // 客户端 F 键预测要求无条件重发服务端副手物品
    private boolean cursorClaimed;      // 来源菜单的实际光标是否已经被清空
    private Lifecycle lifecycle = Lifecycle.CREATED; // 菜单会话的生命周期状态

    ContainerMenuHandle(
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

    ContainerMenuHandle(
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
        // 给每个协议槽位(raw slot), 光标和副手各建一份客户端已知状态
        this.remoteSlots = new Object[this.view.countSlots()]; // NMS RemoteSlot[]
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            this.remoteSlots[slot] = this.createRemoteSlot();
        }
        this.remoteCursor = this.createRemoteSlot();
        this.remoteOffHand = this.createRemoteSlot();
        this.pendingRemoteItems = new Object[this.remoteSlots.length]; // NMS ItemStack[]
        this.alignedSlots = new ItemStack[this.remoteSlots.length];
    }

    // 让其他菜单走完原版关闭流程, 再从来源菜单接管其实际光标.
    @Override
    public void prepareOpen(boolean replacingWindow) {
        if (this.lifecycle != Lifecycle.CREATED) {
            throw new IllegalStateException("menu opening cannot be prepared in state " + this.lifecycle);
        }

        // 替换同类代理菜单时不用先关; 其他情况先让当前菜单走原版关闭流程
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer);   // NMS AbstractContainerMenu
        Object currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);     // NMS AbstractContainerMenu
        boolean replacingProxy = replacingWindow && currentMenu.getClass() == this.proxy.getClass();
        if (!replacingProxy && currentMenu != inventoryMenu) {
            ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, InventoryCloseEvent.Reason.OPEN_NEW);
            currentMenu = PlayerProxy.INSTANCE.containerMenu(this.serverPlayer);
        }

        // 记录来源菜单, 并把它实际持有的光标接管过来
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

        // 先复制本批完整发送状态, 使数据包, 客户端已知状态与 Bukkit 事件状态副本共享同一份服务端输入
        FullContents full = this.prepareFullContents(slots, cursor);
        List<Object> outgoing = this.openPackets(title, full); // NMS 客户端数据包列表

        // 先开始捕获入站容器包, 再把服务端活动菜单切换到代理.
        PacketListener.Session openedSession = this.packets.open(
                this.player,
                this.containerId,
                input -> this.incoming.offer(this.generation, input),
                this.clientboundPacketFilter()
        );
        this.session = openedSession;
        AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, ItemStackProxy.EMPTY);
        // 从玩家背包打开菜单时, 恢复背包同步, 避免之后的物品变化不显示
        AbstractContainerMenuProxy.INSTANCE.resumeRemoteUpdates(this.replacedMenu);
        this.cursorClaimed = true;
        PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.proxy);

        // 网络批次成功排入 event loop 后启用会话; 同步失败则完整恢复打开前状态
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

    /**
     * 列出当前菜单要拦下的原版出站包规则.
     *
     * <p>子类自己发送对应内容时, 应在这里声明规则, 防止原版稍后发来的数据覆盖客户端.</p>
     *
     * @return 要拦下的规则; 不处理时为 null
     */
    @Nullable
    protected ClientboundPacketFilter clientboundPacketFilter() {
        return null;
    }

    /**
     * 在同步的候选槽位定下来之前调用, 子类可以在这里准备菜单专属状态.
     *
     * @param dirtySlots 本轮 dirty 槽位
     * @param forceFull 是否强制完整同步
     */
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
            // 强制全量: 复制本批完整发送状态, 进入发送路径后整体更新客户端已知状态
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
     * 把菜单专属的客户端包加进这一轮统一的网络批次, 并记下进入发送路径后要发布的状态.
     * <p>整个批次成功排进 Netty event loop 后, 基类会调 {@link #commitPackets()}.
     *  {@code forceFull} 为 true 时, 实现要放上客户端重建这部分状态所需的完整数据.
     *
     * @param outgoing 本轮可变数据包列表
     * @param forceFull 是否强制完整同步
     */
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
    }

    /**
     * 确认刚刚那一轮菜单专属数据包已经成功排进 Netty event loop.
     * <p>实现只应该发布 {@link #submitPackets(List, boolean)} 为本轮记下的状态, 比如清掉
     * 对应的 dirty 标记, 更新客户端已知状态. 被调到只说明包已经交给网络发送路径, 不代表客户端
     * 已经收到或处理.
     */
    protected void commitPackets() {
    }

    /**
     * 把服务端槽位渲染结果转成发给客户端的 NMS 物品.
     * 子类可以在这里返回协议占位物, 不影响 Window 槽位内容.
     * <p>同一个槽位收到同一份渲染结果时, 返回内容必须保持一致: 渲染结果没变的槽位会跳过本方法.
     * 实现如果还依赖菜单自身的状态, 那份状态改变时必须调 {@link #forceRemoteSlot(int)} 要求重发受影响的槽位.
     *
     * @param rawSlot 协议槽位(raw slot)
     * @param item 服务端槽位渲染结果
     * @return 客户端显示物品
     */
    protected Object toClientItem(int rawSlot, ItemStack item) {
        return ItemUtils.getItemStackHandle(item);
    }

    /**
     * {@inheritDoc}
     *
     * <p>与后面两个取回方法不同, 这里会检查菜单是否已经提交: 重置的是下一次事件要读的 Bukkit 事件状态副本,
     * 菜单都没了就不该再往里写.
     */
    @Override
    public void resetBukkitEventView(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor) {
        this.checkCommitted();
        this.checkSlotCount(slots);
        this.view.resetForEvent(slots, renderedSlots, cursor);
    }

    /**
     * {@inheritDoc}
     *
     * <p>只读取 Bukkit 事件状态副本, 不检查菜单是否已经提交: 监听器完全可以在事件里关掉 Window, 那份写入照样要取回来.
     */
    @Override
    @Nullable
    public ItemStack takeBukkitEventCursor() {
        return this.view.takeEventCursor();
    }

    /**
     * {@inheritDoc}
     *
     * <p>同样只读取 Bukkit 事件状态副本, 不检查菜单是否已经提交.
     */
    @Override
    public void drainBukkitEventSlots(@NotNull BitSet destination) {
        this.view.drainEventTouchedSlots(destination);
    }

    /**
     * {@inheritDoc}
     *
     * <p>客户端没有单独更新标题的包, 只能重发 OpenScreen 加完整内容.
     */
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
     * 按关闭原因和当前菜单的所有权, 接入 Paper 的容器关闭流程.
     * <p>如果 Paper 或新 Window 已经关闭了原版容器, 这里不再重复关闭. 但旧菜单仍会检查自己
     * 改写过的客户端内容是否已由新菜单接手; 没人接手时, 在关闭后发回原版数据. 断线时不发包.</p>
     *
     * @param reason 关闭原因
     */
    @Override
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
        if (this.lifecycle == Lifecycle.CLOSED) {
            return;
        }
        // 先标记关闭并停止入站捕获, 重复调用安全
        Lifecycle previous = this.lifecycle;
        ClientboundStateProjection releasedProjection = null;
        if (previous == Lifecycle.COMMITTED && reason != InventoryCloseEvent.Reason.DISCONNECT) {
            PacketListener.Session currentSession = this.session;
            if (currentSession != null) {
                releasedProjection = currentSession.releasedClientboundStateProjection();
            }
        }
        this.lifecycle = Lifecycle.CLOSED;
        Throwable failure = ThrowableUtils.captureUnchecked(null, this::closeSession);
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        // 菜单还没有进入已打开状态, 只需把原菜单换回去并归还光标
        if (previous != Lifecycle.COMMITTED) {
            if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                PlayerProxy.INSTANCE.containerMenu(this.serverPlayer, this.replacedMenu);
            }
            this.restorePreparedCursor();
        }
        // 活动菜单已经不是代理了, 关闭流程已被别人接管
        else if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
            try {
                // 玩家关闭/断线只发 Bukkit 事件, 其他原因走服务端主动关闭
                if (reason == InventoryCloseEvent.Reason.PLAYER || reason == InventoryCloseEvent.Reason.DISCONNECT) {
                    CraftEventFactoryProxy.INSTANCE.handleInventoryCloseEvent(this.serverPlayer, reason);
                } else {
                    ServerPlayerProxy.INSTANCE.closeContainer(this.serverPlayer, reason);
                }
            } catch (RuntimeException | Error throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
            // 事件处理器没把菜单换走时, 兜底执行一次 doCloseContainer
            if (PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == this.proxy) {
                try {
                    ServerPlayerProxy.INSTANCE.doCloseContainer(this.serverPlayer);
                } catch (RuntimeException | Error throwable) {
                    failure = ThrowableUtils.combine(failure, throwable);
                }
            }
        }
        ThrowableUtils.throwIfUnchecked(failure);
        // 回到玩家背包菜单后重发完整状态, 清掉 Window 留下的客户端显示状态
        if (reason != InventoryCloseEvent.Reason.DISCONNECT && PlayerProxy.INSTANCE.containerMenu(this.serverPlayer) == inventoryMenu) {
            try {
                AbstractContainerMenuProxy.INSTANCE.sendAllDataToRemote(inventoryMenu);
            } catch (RuntimeException | Error throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
        }
        // 在旧容器关闭后, 把没有新菜单接手的客户端内容恢复为原版数据.
        if (releasedProjection != null) {
            this.packets.send(this.player, List.of(releasedProjection.createNativeRestorePacket()));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现只关闭入站捕获会话.
     */
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
        // 容器编号对不上, 说明交互根本不是发给这次菜单会话的
        // state id 过时只说明客户端视图落后了一拍, 不代表这次交互一定无效, 与原版一样客户端预测照收, 之后再复核.
        if (interaction.containerId() != this.containerId) return false;
        // F 键换副手会被客户端本地预测, 标记副手需要无条件重发
        if (interaction instanceof MenuInput.Common.Click click && click.clickType() == ClickType.SWAP_OFFHAND) {
            this.offHandDirty = true;
        }
        // 把客户端声称的变化收进预测集合, 顺便记下光标是否被动过
        if (interaction.prediction() instanceof ClientMenuPrediction prediction) {
            this.predictedCarried |= prediction.apply(this.remoteSlots, this.remoteCursor, this.predictedSlots);
        }
        // 校验通过后让子类更新状态.
        this.handleAcceptedInteraction();
        return true;
    }

    /**
     * 交互通过校验, 预测也收下来之后调用, 子类可以在这里更新菜单专属状态.
     */
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
        // unwrap 借用底层句柄后复制为独立所有权, 菜单不持有调用方实例
        this.actualCarried = cursor.isEmpty()
                ? ItemStackProxy.EMPTY
                : ItemStackProxy.INSTANCE.copy(ItemUtils.getItemStackHandle(cursor));
    }

    @Override
    public Object carried() {
        return this.actualCarried;
    }

    // 只从代理菜单的 setCarried 覆盖进来, 也就是只有外部 NMS/CraftBukkit 调用会走到这里.
    // HumanEntity#setItemOnCursor 这类写法在写完之后会自己发一个光标包, 把客户端显示改成实际光标 ——
    // 而 Window 展示的可能是另一份视觉光标. 标记下来, 让本轮同步重新核对一次, 否则这次错位会一直留着.
    @Override
    public void carried(Object item) {
        this.actualCarried = item;
        this.externalCarried = true;
    }

    @Override
    public Object emptyItem() {
        return ItemStackProxy.EMPTY;
    }

    /**
     * 强制下一次增量同步重发指定槽位.
     *
     * @param rawSlot 协议槽位(raw slot)
     */
    protected final void forceRemoteSlot(int rawSlot) {
        this.forcedSlots.set(rawSlot);
    }

    /**
     * 发送一个菜单专属协议包.
     *
     * @param packet NMS 客户端包
     */
    protected final void sendClientboundPacket(@NotNull Object packet) {
        this.packets.send(this.player, List.of(packet));
    }

    /**
     * 推进并返回当前菜单的 NMS state id.
     *
     * @return 新的 state id
     */
    protected final int incrementStateId() {
        return AbstractContainerMenuProxy.INSTANCE.incrementStateId(this.proxy);
    }

    /**
     * 组装一次打开或标题刷新要发的完整协议序列.
     *
     * @param title 标题
     * @param full 本批完整发送状态
     * @return 按发送顺序排列的数据包列表
     */
    private List<Object> openPackets(Component title, FullContents full) {
        ArrayList<Object> outgoing = new ArrayList<>(4); // NMS 客户端数据包列表
        // 先打开界面, 再发完整内容和副手
        outgoing.add(ClientboundOpenScreenPacketProxy.INSTANCE.newInstance(
                this.containerId,
                this.menuType,
                PaperAdventureProxy.INSTANCE.asVanilla(title)
        ));
        outgoing.add(full.packet());
        outgoing.add(full.offHandPacket());
        // 子类把菜单专属的包和全量状态附加到同一批次
        this.submitPackets(outgoing, true);
        return outgoing;
    }

    /**
     * 复制一次完整发送状态, 并推进 NMS 菜单的 state id.
     *
     * @param slots 服务端槽位渲染结果
     * @param cursor 菜单实际光标与客户端显示光标
     * @return 本批完整发送状态
     */
    private FullContents prepareFullContents(ItemStack[] slots, CursorSnapshot cursor) {
        // 每个槽位都复制一份, 数据包, 客户端已知状态和 Bukkit 事件状态副本共用这份发送状态
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

    /**
     * 完整状态进入发送路径后, 用它更新客户端已知状态: 槽位, 光标, 副手全部对齐,
     * 预测和强制标记清零.
     *
     * @param slots 本批发送的服务端槽位渲染结果
     * @param full 刚发出去的完整状态
     */
    private void commitFullContents(ItemStack[] slots, FullContents full) {
        // 槽位, 光标, 副手的客户端已知状态全部对齐到刚进入发送路径的内容
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            RemoteSlotProxy.INSTANCE.force(this.remoteSlots[slot], full.slots().get(slot));
            this.alignedSlots[slot] = slots[slot];
        }
        RemoteSlotProxy.INSTANCE.force(this.remoteCursor, full.visualCursor());
        RemoteSlotProxy.INSTANCE.force(this.remoteOffHand, ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(full.offHandPacket()));
        // 预测, 强制重发等标记随客户端已知状态的全量更新清零
        this.predictedSlots.clear();
        this.predictedCarried = false;
        this.externalCarried = false;
        this.offHandDirty = false;
        this.forcedSlots.clear();
        this.commitPackets();
    }

    /**
     * 增量同步: 只看脏槽位, 客户端预测过的槽位和光标, 把差异凑成一个
     * 网络批次发出去, 成功进入发送路径后才更新客户端已知状态.
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
        // 汇总本轮候选: 脏槽位 + 客户端预测 + 强制重发 + Bukkit 事件状态副本被写过的槽位
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
            // 逐槽位和客户端已知状态比较, 只给真正变了的槽位发单槽包
            for (
                    int slot = candidates.nextSetBit(0);
                    slot >= 0 && slot < slots.length;
                    slot = candidates.nextSetBit(slot + 1)
            ) {
                ItemStack rendered = slots[slot];
                // 只由失效标记触发
                boolean mustVerify = this.forcedSlots.get(slot) || this.predictedSlots.get(slot) || this.viewTouchedSlots.get(slot);
                if (!mustVerify && this.alignedSlots[slot] == rendered) {
                    continue;
                }

                Object item = this.toClientItem(slot, rendered); // 借用的 NMS ItemStack
                if (!this.forcedSlots.get(slot) && RemoteSlotProxy.INSTANCE.matches(this.remoteSlots[slot], item)) {
                    // 客户端已知状态本来就等于这份物品, 与本批数据包是否发出无关
                    this.alignedSlots[slot] = rendered;
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
                this.pendingRemoteItems[slot] = ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(packet);
            }

            // 副手: 有脏标记或与客户端已知状态不一致时重发
            Object offHand = ItemUtils.getPlayerItemStackHandle(this.player, EquipmentSlot.OFF_HAND); // 借用的 NMS ItemStack
            if (this.offHandDirty || !RemoteSlotProxy.INSTANCE.matches(this.remoteOffHand, offHand)) {
                Object packet = this.createOffHandPacket(offHand);
                outgoing.add(packet);
                sentOffHand = ClientboundContainerSetSlotPacketProxy.INSTANCE.getItem(packet);
            }

            // 光标: 明确脏了, 被客户端预测过, 被外部直接写过或被 Bukkit 事件状态副本碰过才核对
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

            // 子类把菜单专属的包附加到同一批次
            this.submitPackets(outgoing, false);

            if (!outgoing.isEmpty()) {
                this.packets.send(this.player, outgoing);
            }

            // 只有网络批次成功排入 event loop 后才更新客户端已知状态
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
            // Bukkit 事件状态副本按本轮实际发送的槽位对齐
            changedSlots.or(this.viewTouchedSlots);
            this.view.apply(slots, changedSlots, cursor.actual(), cursorChanged || viewCursorTouched);
            // 预测已消化; 强制标记只保留本轮没发出去的
            this.predictedSlots.clear();
            this.predictedCarried = false;
            this.externalCarried = false;
            this.forcedSlots.andNot(changedSlots);
            this.commitPackets();
        } finally {
            // 清掉复用缓冲, pendingRemoteItems 不能留到下一轮
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
     * 向 Paper 的容器同步器注册一份 RemoteSlot 客户端已知状态并初始化为空,
     * 这样第一次比较不会把任何服务端物品误当成"客户端已知".
     *
     * @return NMS RemoteSlot
     */
    private Object createRemoteSlot() {
        Object synchronizer = ServerPlayerProxy.INSTANCE.containerSynchronizer(this.serverPlayer); // NMS ContainerSynchronizer
        Object slot = ContainerSynchronizerProxy.INSTANCE.createSlot(synchronizer); // NMS RemoteSlot
        RemoteSlotProxy.INSTANCE.force(slot, ItemStackProxy.EMPTY);
        return slot;
    }

    /**
     * 校验传入的槽位数组长度和菜单协议槽位数一致.
     *
     * @param slots 槽位数组
     * @throws IllegalArgumentException 长度不一致时抛出
     */
    private void checkSlotCount(ItemStack[] slots) {
        if (slots.length != this.remoteSlots.length) {
            throw new IllegalArgumentException("menu requires " + this.remoteSlots.length + " slots, got " + slots.length);
        }
    }

    /**
     * 校验菜单处于 {@link Lifecycle#COMMITTED} 已打开状态.
     *
     * @throws IllegalStateException 菜单未打开时抛出
     */
    private void checkCommitted() {
        if (this.lifecycle != Lifecycle.COMMITTED) {
            throw new IllegalStateException("menu is not open");
        }
    }

    /**
     * 停止捕获本 Window 的入站包并关闭捕获会话, 重复调用安全.
     */
    private void closeSession() {
        // 无论后续关闭路径如何, 都先停止捕获本 Window 的入站包.
        this.incoming.close();
        PacketListener.Session previousSession = this.session;
        this.session = null;
        if (previousSession != null) {
            previousSession.close();
        }
    }

    /**
     * 把打开预备阶段捕获的菜单实际光标归还给来源菜单.
     * 只有光标已被清空 (cursorClaimed) 才归还, 避免覆盖来源菜单此后自己设置的光标.
     */
    private void restorePreparedCursor() {
        if (this.cursorClaimed) {
            AbstractContainerMenuProxy.INSTANCE.setCarried(this.replacedMenu, this.actualCarried);
        }
        this.actualCarried = ItemStackProxy.EMPTY;
        this.cursorClaimed = false;
    }

    /**
     * 给指定的副手物品副本造一个同步包. 副手属于玩家原生的背包菜单,
     * 所以包要用背包菜单的容器编号和 state id.
     *
     * @param offHand 副手物品副本(NMS ItemStack)
     * @return 副手同步包
     */
    private Object createOffHandPacket(Object offHand) {
        Object inventoryMenu = PlayerProxy.INSTANCE.inventoryMenu(this.serverPlayer); // NMS AbstractContainerMenu
        return ClientboundContainerSetSlotPacketProxy.INSTANCE.newInstance(
                AbstractContainerMenuProxy.INSTANCE.containerId(inventoryMenu),
                AbstractContainerMenuProxy.INSTANCE.incrementStateId(inventoryMenu),
                OFF_HAND_SLOT,
                offHand
        );
    }

    /**
     * 菜单会话的生命周期状态.
     * CREATED 经打开预备进入 PREPARED, 初始批次成功进入发送路径后进入 COMMITTED, 任意状态关闭后进入 CLOSED.
     */
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
