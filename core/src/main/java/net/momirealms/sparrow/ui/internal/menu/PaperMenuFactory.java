package net.momirealms.sparrow.ui.internal.menu;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
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
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Paper/NMS 容器适配器.
 *
 * <p>调用方必须保证 {@link #create(Player, int, long, IncomingPacketQueue)} 与生成的
 * {@link MenuHandle} 方法运行在玩家实体线程. 此类只负责构造协议菜单, 实际网络写入由
 * {@link PacketListener} 切换到 Netty event loop.</p>
 */
public final class PaperMenuFactory implements MenuFactory, AutoCloseable {
    private final PacketListener packets;

    /**
     * 创建共享一个 {@link PacketListener} 的菜单工厂.
     *
     * @param plugin 注册网络监听器的插件
     */
    public PaperMenuFactory(@NotNull Plugin plugin) {
        this.packets = new PacketListener(plugin, SparrowUI.getInstance()::handleException);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull MenuHandle create(
            @NotNull Player viewer,
            int topSlots,
            long generation,
            @NotNull IncomingPacketQueue<MenuInput> incoming
    ) {
        return new PaperMenuHandle(this.packets, viewer, topSlots, generation, incoming);
    }

    /**
     * 卸载所有已注入的玩家网络 handler.
     */
    @Override
    public void close() {
        this.packets.close();
    }

    /**
     * 一个会话专属的 NMS 菜单代理.
     *
     * <p>代理只向 Bukkit 暴露 {@link ProtocolInventoryView}, 并禁用 NMS 的自动同步. 此 Adapter
     * 使用 Paper {@link RemoteSlot} 维护远端镜像, Window 只提交权威状态和 dirty 候选.</p>
     */
    private static final class PaperMenuHandle implements MenuHandle {
        private final PacketListener packets;
        private final Player player;
        private final ServerPlayer serverPlayer;
        private final MenuType<?> menuType;
        private final int containerId;
        private final long generation;
        private final IncomingPacketQueue<MenuInput> incoming;
        private final AbstractContainerMenu replacedMenu;
        private final ProtocolInventoryView view;
        private final MenuProxy proxy;
        private final RemoteSlot[] remoteSlots;
        private final RemoteSlot remoteCursor;
        private final BitSet predictedSlots = new BitSet();

        private @Nullable PacketListener.Session session;
        private net.minecraft.world.item.ItemStack carried = net.minecraft.world.item.ItemStack.EMPTY;
        private boolean predictedCarried;
        private boolean committed;
        private boolean closed;

        private PaperMenuHandle(
                PacketListener packets,
                Player player,
                int topSlots,
                long generation,
                IncomingPacketQueue<MenuInput> incoming
        ) {
            this.packets = packets;
            this.player = player;
            this.serverPlayer = ((CraftPlayer) player).getHandle();
            this.menuType = PaperMenuFactory.menuType(topSlots);
            this.containerId = this.serverPlayer.nextContainerCounter();
            this.generation = generation;
            this.incoming = incoming;
            this.replacedMenu = this.serverPlayer.containerMenu;
            this.view = new ProtocolInventoryView(player, topSlots);
            this.proxy = new MenuProxy();
            this.remoteSlots = new RemoteSlot[topSlots + 36];
            for (int slot = 0; slot < this.remoteSlots.length; slot++) {
                this.remoteSlots[slot] = this.createRemoteSlot();
            }
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
        public boolean accepts(@NotNull MenuInput.Interaction interaction) {
            this.checkCommitted();
            if (interaction.containerId() != this.containerId) {
                return false;
            }
            if (interaction.stateId() != this.proxy.getStateId()) {
                return false;
            }
            if (interaction.prediction() instanceof ClientMenuPrediction prediction) {
                this.predictedCarried |= prediction.apply(
                        this.remoteSlots,
                        this.remoteCursor,
                        this.predictedSlots
                );
            }
            return true;
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
                    this.generation,
                    this.incoming
            );
            this.session = openedSession;
            this.serverPlayer.containerMenu = this.proxy;

            // 网络批次成功排入 event loop 后提交会话; 同步失败则完整恢复打开前状态
            try {
                this.packets.send(this.player, outgoing);
                openedSession.commit();
                this.commitFull(full);
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
            if (forceFull) {
                FullContents full = this.prepareFull(slots, cursor);
                this.packets.send(this.player, List.of(full.packet()));
                this.commitFull(full);
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
            this.packets.send(this.player, this.openPackets(title, full));
            this.commitFull(full);
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
            if (this.closed) {
                return;
            }
            this.closed = true;
            // 无论后续关闭路径如何, 都先停止捕获本 Window 的入站包.
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
            rethrow(failure);
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
            ArrayList<Packet<? super ClientGamePacketListener>> outgoing = new ArrayList<>(2);
            outgoing.add(new ClientboundOpenScreenPacket(
                    this.containerId,
                    this.menuType,
                    PaperAdventure.asVanilla(title)
            ));
            outgoing.add(full.packet());
            return List.copyOf(outgoing);
        }

        /**
         * 冻结一次完整状态并推进 NMS 菜单 state id.
         */
        private FullContents prepareFull(ItemStack[] slots, ItemStack cursor) {
            ArrayList<net.minecraft.world.item.ItemStack> items = new ArrayList<>(slots.length);
            for (int index = 0; index < slots.length; index++) {
                items.add(PaperMenuFactory.toNms(slots[index]));
            }
            List<net.minecraft.world.item.ItemStack> frozenItems = List.copyOf(items);
            net.minecraft.world.item.ItemStack frozenCursor = PaperMenuFactory.toNms(cursor);
            ClientboundContainerSetContentPacket packet = new ClientboundContainerSetContentPacket(
                    this.containerId,
                    this.proxy.incrementStateId(),
                    frozenItems,
                    frozenCursor
            );
            return new FullContents(frozenItems, frozenCursor, packet);
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
            BitSet candidates = (BitSet) dirtySlots.clone();
            candidates.or(this.predictedSlots);
            BitSet viewTouchedSlots = this.view.takeTouchedSlots();
            candidates.or(viewTouchedSlots);
            boolean viewCursorTouched = this.view.takeCursorTouched();

            int candidateCount = candidates.cardinality();
            ArrayList<Packet<? super ClientGamePacketListener>> outgoing = new ArrayList<>(candidateCount + 1);
            BitSet changedSlots = new BitSet();
            int[] sentSlots = new int[candidateCount];
            net.minecraft.world.item.ItemStack[] sentItems = new net.minecraft.world.item.ItemStack[candidateCount];
            int sentCount = 0;
            for (
                    int slot = candidates.nextSetBit(0);
                    slot >= 0 && slot < slots.length;
                    slot = candidates.nextSetBit(slot + 1)
            ) {
                net.minecraft.world.item.ItemStack item = PaperMenuFactory.toNms(slots[slot]);
                if (this.remoteSlots[slot].matches(item)) {
                    continue;
                }
                ClientboundContainerSetSlotPacket packet = new ClientboundContainerSetSlotPacket(
                        this.containerId,
                        this.proxy.incrementStateId(),
                        slot,
                        item
                );
                outgoing.add(packet);
                changedSlots.set(slot);
                sentSlots[sentCount] = slot;
                sentItems[sentCount] = packet.getItem();
                sentCount++;
            }

            boolean checkCursor = cursorDirty || this.predictedCarried || viewCursorTouched;
            boolean cursorChanged = false;
            net.minecraft.world.item.ItemStack sentCursor = this.carried;
            if (checkCursor) {
                sentCursor = PaperMenuFactory.toNms(cursor);
                if (!this.remoteCursor.matches(sentCursor)) {
                    outgoing.add(new ClientboundSetCursorItemPacket(sentCursor));
                    cursorChanged = true;
                }
            }

            if (!outgoing.isEmpty()) {
                this.packets.send(this.player, List.copyOf(outgoing));
            }

            // 只有网络批次成功排入 event loop 后才提交远端镜像
            for (int update = 0; update < sentCount; update++) {
                this.remoteSlots[sentSlots[update]].force(sentItems[update]);
            }
            if (checkCursor) {
                this.carried = sentCursor;
            }
            if (cursorChanged) {
                this.remoteCursor.force(sentCursor);
            }
            changedSlots.or(viewTouchedSlots);
            this.view.apply(slots, changedSlots, cursor, cursorChanged || viewCursorTouched);
            this.predictedSlots.clear();
            this.predictedCarried = false;
        }

        /**
         * 将一次完整发送提交为新的远端镜像.
         */
        private void commitFull(FullContents full) {
            for (int slot = 0; slot < this.remoteSlots.length; slot++) {
                this.remoteSlots[slot].force(full.slots().get(slot));
            }
            this.remoteCursor.force(full.cursor());
            this.carried = full.cursor();
            this.predictedSlots.clear();
            this.predictedCarried = false;
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
         * 防止 NMS 根据客户端预测自行广播物品状态的空菜单代理.
         */
        private final class MenuProxy extends AbstractContainerMenu {

            private MenuProxy() {
                super(PaperMenuHandle.this.menuType, PaperMenuHandle.this.containerId);
            }

            @Override
            public net.minecraft.world.item.ItemStack getCarried() {
                return PaperMenuHandle.this.carried;
            }

            @Override
            public void setCarried(net.minecraft.world.item.ItemStack item) {
                PaperMenuHandle.this.carried = item;
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

            @Override
            public InventoryView getBukkitView() {
                return PaperMenuHandle.this.view;
            }

            @Override
            public net.minecraft.world.item.ItemStack quickMoveStack(
                    net.minecraft.world.entity.player.Player player,
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
         * 一次完整包与提交远端镜像所共享的冻结 NMS 状态.
         *
         * @param slots 冻结槽位
         * @param cursor 冻结光标
         * @param packet 完整内容包
         */
        private record FullContents(
                List<net.minecraft.world.item.ItemStack> slots,
                net.minecraft.world.item.ItemStack cursor,
                ClientboundContainerSetContentPacket packet
        ) {
        }
    }

    private static MenuType<?> menuType(int topSlots) {
        return switch (topSlots) {
            case 9 -> MenuType.GENERIC_9x1;
            case 18 -> MenuType.GENERIC_9x2;
            case 27 -> MenuType.GENERIC_9x3;
            case 36 -> MenuType.GENERIC_9x4;
            case 45 -> MenuType.GENERIC_9x5;
            case 54 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("top inventory must contain between one and six rows");
        };
    }

    private static net.minecraft.world.item.ItemStack toNms(ItemStack item) {
        if (item.isEmpty()) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        return CraftItemStack.unwrap(item).copy();
    }

    private static void rethrow(@Nullable Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
    }
}
