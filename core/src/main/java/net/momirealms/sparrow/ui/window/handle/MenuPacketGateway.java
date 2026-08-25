package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.network.filter.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.network.filter.ClientboundStateProjection;
import net.momirealms.sparrow.ui.network.listener.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.listener.ByteBufPacketListener;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.listener.NMSPacketEvent;
import net.momirealms.sparrow.ui.network.listener.NMSPacketListener;
import net.momirealms.sparrow.ui.network.NetworkManager;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketBuf;
import net.momirealms.sparrow.ui.network.PacketFlow;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ServerboundContainerClickPacketProxy;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// 把菜单协议包转换为稳定的 MenuInput, Session 交接仍由玩家实体线程决定.
final class MenuPacketGateway implements Listener, AutoCloseable {
    private final NetworkManager network;
    private final Map<UUID, AtomicReference<Session>> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    MenuPacketGateway(@NotNull Plugin plugin, @NotNull NetworkManager network) {
        this.network = network;
        this.registerListeners();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @NotNull
    Session open(@NotNull Player player, int containerId, @NotNull Consumer<? super MenuInput> inputSink, @Nullable ClientboundPacketFilter clientboundPacketFilter) {
        if (this.closed.get()) throw new IllegalStateException("menu packet gateway is closed");
        AtomicReference<Session> active = this.sessions.computeIfAbsent(player.getUniqueId(), ignored -> new AtomicReference<>());
        while (true) {
            Session replaced = active.get();
            Session session = new Session(active, replaced, containerId, inputSink, clientboundPacketFilter, this.network);
            if (active.compareAndSet(replaced, session)) {
                return session;
            }
        }
    }

    void send(@NotNull Player player, @NotNull List<?> packets) {
        if (this.closed.get()) throw new IllegalStateException("menu packet gateway is closed");
        NetworkUser user = this.network.user(player);
        if (user == null) throw new IllegalStateException("player channel is not registered in NetworkManager: " + player.getName());
        this.network.send(user, packets);
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        HandlerList.unregisterAll(this);
        for (AtomicReference<Session> active : this.sessions.values()) {
            active.set(null);
        }
        this.sessions.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        AtomicReference<Session> active = this.sessions.remove(event.getPlayer().getUniqueId());
        if (active != null) {
            active.set(null);
        }
    }

    private void registerListeners() {
        // 选择收纳袋里的物品. 布局: VarInt slotId, VarInt selectedItemIndex.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                PacketBuf buffer = event.getBuffer();
                session.accept(new MenuInput.Common.BundleSelection(session.containerId, buffer.readVarInt(), buffer.readVarInt()));
            }
        }, "minecraft:bundle_item_selected", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 关闭容器. 布局: VarInt containerId.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                session.accept(new MenuInput.Common.Close(event.getBuffer().readVarInt()));
            }
        }, "minecraft:container_close", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // Ping - Pong. 布局: 定长 4 字节 id, 与其余包的 VarInt 不同.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session != null) {
                    session.accept(new MenuInput.Common.Pong(event.getBuffer().readInt()));
                }
            }
        }, "minecraft:pong", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 重命名. 布局: Utf name.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                session.accept(new MenuInput.WindowSpecific.Rename(event.getBuffer().readUtf()));
            }
        }, "minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 切换合成器输入槽. 布局: VarInt slotId, VarInt containerId, Bool newState —— 槽位在容器编号之前.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                PacketBuf buffer = event.getBuffer();
                int slotId = buffer.readVarInt();
                int containerId = buffer.readVarInt();
                session.accept(new MenuInput.WindowSpecific.CrafterSlotState(containerId, slotId, buffer.readBoolean()));
            }
        }, "minecraft:container_slot_state_changed", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 选择原版按钮. 布局: VarInt containerId, VarInt buttonId.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                PacketBuf buffer = event.getBuffer();
                session.accept(new MenuInput.WindowSpecific.ButtonClick(buffer.readVarInt(), buffer.readVarInt()));
            }
        }, "minecraft:container_button_click", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 从配方书选择配方. 布局: VarInt containerId, VarInt recipeDisplayId, Bool useMaxItems.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                PacketBuf buffer = event.getBuffer();
                session.accept(new MenuInput.WindowSpecific.RecipePlace(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()));
            }
        }, "minecraft:place_recipe", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 选择村民交易栏位. 布局: VarInt index, 包内不带容器编号.
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                session.accept(new MenuInput.WindowSpecific.TradeSelect(session.containerId, event.getBuffer().readVarInt()));
            }
        }, "minecraft:select_trade", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        // 屏蔽商人配方包流出
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session != null && session.suppresses(event.packetId())) {
                    event.cancelled(true);
                }
            }
        }, "minecraft:merchant_offers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        // 屏蔽切石机配方包流出
        this.network.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                Session session = MenuPacketGateway.this.active(user);
                if (session != null && session.suppresses(event.packetId())) {
                    event.cancelled(true);
                }
            }
        }, "minecraft:update_recipes", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);

        this.network.registerNMSPacketListener(new NMSPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
                Session session = MenuPacketGateway.this.active(user);
                if (session == null) return;
                event.cancelled(true);
                session.accept(Session.interaction(packet));
            }
        }, ServerboundContainerClickPacketProxy.CLASS, PacketFlow.SERVERBOUND);
    }

    @Nullable
    private Session active(NetworkUser user) {
        UUID uuid = user.uuid();
        if (uuid == null) return null;
        AtomicReference<Session> active = this.sessions.get(uuid);
        return active == null ? null : active.get();
    }

    static final class Session implements AutoCloseable {
        private final AtomicReference<Session> owner;
        private final AtomicReference<Session> replaced;
        private final int containerId;
        private final Consumer<? super MenuInput> inputSink;
        private final @Nullable ClientboundPacketFilter clientboundPacketFilter;
        private final int[] suppressedPacketIds;

        private Session(
                AtomicReference<Session> owner,
                @Nullable Session replaced,
                int containerId,
                Consumer<? super MenuInput> inputSink,
                @Nullable ClientboundPacketFilter clientboundPacketFilter,
                NetworkManager network
        ) {
            this.owner = owner;
            this.replaced = new AtomicReference<>(replaced);
            this.containerId = containerId;
            this.inputSink = inputSink;
            this.clientboundPacketFilter = clientboundPacketFilter;
            this.suppressedPacketIds = clientboundPacketFilter == null ? new int[0] : clientboundPacketFilter.suppressedPacketIds(network.packetIds());
        }

        @Override
        public void close() {
            this.replaced.set(null);
            this.owner.compareAndSet(this, null);
        }

        void commit() {
            this.replaced.set(null);
        }

        void rollback() {
            Session previous = this.replaced.getAndSet(null);
            this.owner.compareAndSet(this, previous);
        }

        @Nullable
        ClientboundStateProjection releasedClientboundStateProjection() {
            Session successor = this.owner.get();
            ClientboundPacketFilter successorFilter = successor == null || successor == this
                    ? null
                    : successor.clientboundPacketFilter;
            return this.clientboundPacketFilter instanceof ClientboundStateProjection projection && !projection.continuedBy(successorFilter)
                    ? projection
                    : null;
        }

        private void accept(MenuInput input) {
            this.inputSink.accept(input);
        }

        private boolean suppresses(int packetId) {
            for (int index = 0; index < this.suppressedPacketIds.length; index++) {
                if (this.suppressedPacketIds[index] == packetId) {
                    return true;
                }
            }
            return false;
        }

        static MenuInput.Common.Interaction interaction(Object click) {
            ServerboundContainerClickPacketProxy proxy = ServerboundContainerClickPacketProxy.INSTANCE;
            Enum<?> containerInput = VersionHelper.isOrAbove26_1() ? proxy.containerInput(click) : proxy.clickType(click);
            return switch (containerInput.name()) {
                case "PICKUP" -> switch (proxy.buttonNum(click)) {
                    case 0 -> singleClick(click, proxy.slotNum(click) == -999 ? ClickType.WINDOW_BORDER_LEFT : ClickType.LEFT);
                    case 1 -> singleClick(click, proxy.slotNum(click) == -999 ? ClickType.WINDOW_BORDER_RIGHT : ClickType.RIGHT);
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case "QUICK_MOVE" -> switch (proxy.buttonNum(click)) {
                    case 0 -> singleClick(click, ClickType.SHIFT_LEFT);
                    case 1 -> singleClick(click, ClickType.SHIFT_RIGHT);
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case "SWAP" -> {
                    if (proxy.buttonNum(click) >= 0 && proxy.buttonNum(click) <= 8) {
                        yield singleClick(click, ClickType.NUMBER_KEY, proxy.buttonNum(click));
                    }
                    if (proxy.buttonNum(click) == 40) {
                        yield singleClick(click, ClickType.SWAP_OFFHAND);
                    }
                    yield singleClick(click, ClickType.UNKNOWN);
                }
                case "CLONE" -> singleClick(click, proxy.buttonNum(click) == 2 ? ClickType.MIDDLE : ClickType.UNKNOWN);
                case "THROW" -> switch (proxy.buttonNum(click)) {
                    case 0 -> singleClick(click, ClickType.DROP);
                    case 1 -> singleClick(click, ClickType.CONTROL_DROP);
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case "QUICK_CRAFT" -> dragStep(click);
                case "PICKUP_ALL" -> singleClick(click, proxy.buttonNum(click) == 0 ? ClickType.DOUBLE_CLICK : ClickType.UNKNOWN);
                default -> singleClick(click, ClickType.UNKNOWN);
            };
        }

        private static MenuInput.Common.Click singleClick(Object packet, ClickType clickType) {
            return singleClick(packet, clickType, -1);
        }

        private static MenuInput.Common.Click singleClick(Object packet, ClickType clickType, int hotbarButton) {
            ServerboundContainerClickPacketProxy proxy = ServerboundContainerClickPacketProxy.INSTANCE;
            return new MenuInput.Common.Click(
                    proxy.containerId(packet),
                    proxy.stateId(packet),
                    proxy.slotNum(packet),
                    clickType,
                    hotbarButton,
                    ClientMenuPrediction.from(packet)
            );
        }

        // QUICK_CRAFT 的 button 编码不连续, 无效值交给实体线程触发状态纠正.
        private static MenuInput.Common.Interaction dragStep(Object packet) {
            return switch (ServerboundContainerClickPacketProxy.INSTANCE.buttonNum(packet)) {
                case 0 -> dragStep(packet, ClickType.LEFT, MenuInput.Common.DragPhase.START);
                case 1 -> dragStep(packet, ClickType.LEFT, MenuInput.Common.DragPhase.ADD);
                case 2 -> dragStep(packet, ClickType.LEFT, MenuInput.Common.DragPhase.END);
                case 4 -> dragStep(packet, ClickType.RIGHT, MenuInput.Common.DragPhase.START);
                case 5 -> dragStep(packet, ClickType.RIGHT, MenuInput.Common.DragPhase.ADD);
                case 6 -> dragStep(packet, ClickType.RIGHT, MenuInput.Common.DragPhase.END);
                case 8 -> dragStep(packet, ClickType.MIDDLE, MenuInput.Common.DragPhase.START);
                case 9 -> dragStep(packet, ClickType.MIDDLE, MenuInput.Common.DragPhase.ADD);
                case 10 -> dragStep(packet, ClickType.MIDDLE, MenuInput.Common.DragPhase.END);
                default -> singleClick(packet, ClickType.UNKNOWN);
            };
        }

        private static MenuInput.Common.DragStep dragStep(Object packet, ClickType clickType, MenuInput.Common.DragPhase phase) {
            return new MenuInput.Common.DragStep(
                    ServerboundContainerClickPacketProxy.INSTANCE.containerId(packet),
                    ServerboundContainerClickPacketProxy.INSTANCE.stateId(packet),
                    ServerboundContainerClickPacketProxy.INSTANCE.slotNum(packet),
                    clickType,
                    phase,
                    ClientMenuPrediction.from(packet)
            );
        }
    }

}
