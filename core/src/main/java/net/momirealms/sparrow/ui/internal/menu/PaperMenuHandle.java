package net.momirealms.sparrow.ui.internal.menu;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.RemoteSlot;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

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
class PaperMenuHandle implements MenuHandle {
    private static final int INCOMING_CAPACITY = 256;

    private final PacketListener packets;
    private final Player player;
    private final ServerPlayer serverPlayer;
    private final MenuType<?> menuType;
    private final int containerId;
    private final long generation;
    private final IncomingPacketQueue<MenuInput> incoming = new IncomingPacketQueue<>(INCOMING_CAPACITY);
    private final AbstractContainerMenu replacedMenu;
    private final ProtocolInventoryView view;
    private final MenuProxy proxy;
    private final RemoteSlot[] remoteSlots;
    private final RemoteSlot remoteCursor;
    private final BitSet predictedSlots = new BitSet();
    private final BitSet forcedSlots = new BitSet();
    private final BitSet candidateSlots = new BitSet();
    private final BitSet viewTouchedSlots = new BitSet();
    private final BitSet changedSlots = new BitSet();
    private final net.minecraft.world.item.ItemStack[] pendingRemoteItems;

    private @Nullable PacketListener.Session session;
    private net.minecraft.world.item.ItemStack cursor = net.minecraft.world.item.ItemStack.EMPTY;
    private boolean predictedCarried;
    private boolean committed;
    private boolean closed;

    PaperMenuHandle(
            PacketListener packets,
            Player player,
            MenuType<?> menuType,
            InventoryType inventoryType,
            org.bukkit.inventory.MenuType bukkitMenuType,
            int topSlots,
            long generation
    ) {
        this.packets = packets;
        this.player = player;
        this.serverPlayer = ((CraftPlayer) player).getHandle();
        this.menuType = menuType;
        this.containerId = this.serverPlayer.nextContainerCounter();
        this.generation = generation;
        this.replacedMenu = this.serverPlayer.containerMenu;
        this.view = new ProtocolInventoryView(player, topSlots, inventoryType, bukkitMenuType);
        this.proxy = new MenuProxy();
        this.remoteSlots = new RemoteSlot[topSlots + 36];
        for (int slot = 0; slot < this.remoteSlots.length; slot++) {
            this.remoteSlots[slot] = this.createRemoteSlot();
        }
        this.pendingRemoteItems = new net.minecraft.world.item.ItemStack[this.remoteSlots.length];
        this.remoteCursor = this.createRemoteSlot();
    }

    @Override
    public int containerId() {
        return this.containerId;
    }

    @Override
    public @NotNull InventoryView view() {
        return this.view;
    }

    @Override
    public int playerInventoryVersion() {
        return this.serverPlayer.getInventory().getTimesChanged();
    }

    @Override
    public int stateId() {
        return this.proxy.getStateId();
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
        if (interaction.stateId() != this.proxy.getStateId()) {
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
    public @NotNull List<MenuInput> drainInputs(int limit) {
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
        List<Packet<? super ClientGamePacketListener>> outgoing = this.openPackets(title, full);

        // 先开始捕获入站容器包, 再把服务端活动菜单切换到代理.
        PacketListener.Session openedSession = this.packets.open(
                this.player,
                this.containerId,
                input -> this.incoming.offer(this.generation, input)
        );
        this.session = openedSession;
        this.serverPlayer.containerMenu = this.proxy;

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
            this.serverPlayer.containerMenu = this.replacedMenu;
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
            ArrayList<Packet<? super ClientGamePacketListener>> outgoing = new ArrayList<>(2);
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
        this.serverPlayer.containerMenu = this.proxy;
        List<Packet<? super ClientGamePacketListener>> outgoing = this.openPackets(title, full);
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
        this.packets.send(this.player, List.of(new ClientboundPingPacket(id)));
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
            if (this.serverPlayer.containerMenu == this.proxy) {
                this.serverPlayer.containerMenu = this.replacedMenu;
            }
            return;
        }
        // 新窗口已接管客户端时, 不能再发送旧窗口的关闭包.
        if (mode == CloseMode.REPLACED) {
            return;
        }
        if (this.serverPlayer.containerMenu != this.proxy) {
            return;
        }
        // 仅当前代理仍处于活动状态时才恢复库存菜单并发送最终快照.
        this.serverPlayer.containerMenu = this.serverPlayer.inventoryMenu;

        Throwable failure = null;
        if (mode == CloseMode.PLUGIN) {
            try {
                this.packets.send(this.player, List.of(new ClientboundContainerClosePacket(this.containerId)));
            } catch (RuntimeException | Error throwable) {
                failure = throwable;
            }
        }
        try {
            this.serverPlayer.inventoryMenu.sendAllDataToRemote();
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
    private List<Packet<? super ClientGamePacketListener>> openPackets(Component title, FullContents full) {
        ArrayList<Packet<? super ClientGamePacketListener>> outgoing = new ArrayList<>(3);
        outgoing.add(new ClientboundOpenScreenPacket(
                this.containerId,
                this.menuType,
                PaperAdventure.asVanilla(title)
        ));
        outgoing.add(full.packet());
        this.appendMenuDataPackets(outgoing, true);
        return outgoing;
    }

    /**
     * 冻结一次完整状态并推进 NMS 菜单 state id.
     */
    private FullContents prepareFull(ItemStack[] slots, ItemStack cursor) {
        ArrayList<net.minecraft.world.item.ItemStack> items = new ArrayList<>(slots.length);
        for (int index = 0; index < slots.length; index++) {
            items.add((net.minecraft.world.item.ItemStack) ItemStackProxy.INSTANCE.copy(this.toClientItem(index, slots[index])));
        }
        net.minecraft.world.item.ItemStack menuCursor = (net.minecraft.world.item.ItemStack) ItemUtils.getItemStackNMSHandle(cursor);
        net.minecraft.world.item.ItemStack packetCursor = (net.minecraft.world.item.ItemStack) ItemStackProxy.INSTANCE.copy(menuCursor);
        ClientboundContainerSetContentPacket packet = new ClientboundContainerSetContentPacket(
                this.containerId,
                this.proxy.incrementStateId(),
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
        ArrayList<Packet<? super ClientGamePacketListener>> outgoing = new ArrayList<>(candidateCount + 2);
        BitSet changedSlots = this.changedSlots;
        changedSlots.clear();
        try {
            for (
                    int slot = candidates.nextSetBit(0);
                    slot >= 0 && slot < slots.length;
                    slot = candidates.nextSetBit(slot + 1)
            ) {
                net.minecraft.world.item.ItemStack item = this.toClientItem(slot, slots[slot]);
                if (!this.forcedSlots.get(slot) && this.remoteSlots[slot].matches(item)) {
                    continue;
                }
                // 当前目标版本的单槽包构造器会取得自己的物品副本, 这里不在比较前重复复制.
                ClientboundContainerSetSlotPacket packet =
                        new ClientboundContainerSetSlotPacket(this.containerId, this.proxy.incrementStateId(), slot, item);
                outgoing.add(packet);
                changedSlots.set(slot);
                this.pendingRemoteItems[slot] = packet.getItem();
            }

            boolean checkCursor = cursorDirty || this.predictedCarried || viewCursorTouched;
            boolean cursorChanged = false;
            net.minecraft.world.item.ItemStack sentCursor = this.cursor;
            if (checkCursor) {
                sentCursor = (net.minecraft.world.item.ItemStack) ItemUtils.getItemStackNMSHandle(cursor);
                if (!this.remoteCursor.matches(sentCursor)) {
                    outgoing.add(new ClientboundSetCursorItemPacket(
                            (net.minecraft.world.item.ItemStack) ItemStackProxy.INSTANCE.copy(sentCursor)
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
                this.remoteSlots[slot].force(this.pendingRemoteItems[slot]);
            }
            if (checkCursor) {
                this.cursor = sentCursor;
            }
            if (cursorChanged) {
                this.remoteCursor.force(sentCursor);
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
            this.remoteSlots[slot].force(full.slots().get(slot));
        }
        this.remoteCursor.force(full.cursor());
        this.cursor = full.cursor();
        this.predictedSlots.clear();
        this.predictedCarried = false;
        this.forcedSlots.clear();
    }

    private RemoteSlot createRemoteSlot() {
        RemoteSlot slot = this.serverPlayer.containerSynchronizer.createSlot();
        slot.force(net.minecraft.world.item.ItemStack.EMPTY);
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
    protected net.minecraft.world.item.ItemStack toClientItem(int rawSlot, ItemStack item) {
        return (net.minecraft.world.item.ItemStack) ItemUtils.getItemStackNMSHandle(item);
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
    protected void appendMenuDataPackets(
            @NotNull List<Packet<? super ClientGamePacketListener>> outgoing,
            boolean forceFull
    ) {
    }

    /**
     * 在统一网络批次成功排入 Netty event loop 后提交菜单专属远端镜像.
     */
    protected void commitMenuDataPackets() {
    }

    /**
     * 防止 NMS 根据客户端预测自行广播物品状态的空菜单代理.
     */
    private final class MenuProxy extends AbstractContainerMenu {

        private MenuProxy() {
            super(PaperMenuHandle.this.menuType, PaperMenuHandle.this.containerId);
        }

        @Override
        public net.minecraft.world.item.ItemStack getCarried() {
            return PaperMenuHandle.this.cursor;
        }

        @Override
        public void setCarried(net.minecraft.world.item.ItemStack item) {
            PaperMenuHandle.this.cursor = item;
        }

        @Override
        public void broadcastCarriedItem() {
            // PaperMenuHandle 明确发送光标状态
        }

        @Override
        public void broadcastChanges() {
            // Window 是唯一的槽位同步权威.
        }

        @Override
        public void broadcastFullState() {
            // Window 是唯一的完整状态同步权威.
        }

        @NonNull
        @Override
        public InventoryView getBukkitView() {
            return PaperMenuHandle.this.view;
        }

        @Override
        public net.minecraft.world.item.@NonNull ItemStack quickMoveStack(
                net.minecraft.world.entity.player.@NonNull Player player,
                int slot
        ) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
    }

    /**
     * 一次完整同步所需的数据包快照和实体线程菜单状态.
     *
     * @param slots 冻结槽位
     * @param cursor 菜单在实体线程持有的光标状态.
     * @param packet 完整内容包
     */
    private record FullContents(
            List<net.minecraft.world.item.ItemStack> slots,
            net.minecraft.world.item.ItemStack cursor,
            ClientboundContainerSetContentPacket packet
    ) {
    }

}
