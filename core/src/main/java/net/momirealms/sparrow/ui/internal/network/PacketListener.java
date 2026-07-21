package net.momirealms.sparrow.ui.internal.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.momirealms.sparrow.ui.internal.menu.ClientMenuPrediction;
import net.momirealms.sparrow.ui.internal.menu.IncomingPacketQueue;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 每条玩家连接只安装一个窄 Netty handler, 活动 Window 通过可替换 {@link Session} 接收入站容器包.
 *
 * <p>Window 在玩家实体线程创建或替换 Session. handler 始终在连接的 Netty event loop 中执行,
 * 因此会话引用使用原子操作交接, 而实际领域消息进入 {@link IncomingPacketQueue} 后才由实体线程消费.</p>
 */
public final class PacketListener implements Listener, AutoCloseable {
    private static final String MINECRAFT_HANDLER = "packet_handler";

    private final String handlerName;
    private final BiConsumer<? super String, ? super Throwable> exceptionHandler;
    private final Map<UUID, ConnectionHandler> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 注册玩家上下线监听器, 并为当前在线玩家异步注入网络 handler.
     *
     * @param plugin 注册监听器的插件
     * @param exceptionHandler 捕获或注入网络包失败时的错误处理器
     */
    public PacketListener(
            @NotNull Plugin plugin,
            @NotNull BiConsumer<? super String, ? super Throwable> exceptionHandler
    ) {
        this.handlerName = "sparrow_ui_" + plugin.getName().toLowerCase(Locale.ROOT) + "_packets";
        this.exceptionHandler = exceptionHandler;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.inject(player);
        }
    }

    /**
     * 为一个 Window 会话安装新的入站包接收端.
     *
     * <p>新 Session 先替换活动引用但尚未提交. 若随后的打开包发送失败, 调用方必须
     * {@link Session#rollback()} 恢复此前会话; 成功后调用 {@link Session#commit()} 丢弃回滚点.</p>
     *
     * @param player 连接所属玩家
     * @param containerId 要捕获的容器编号
     * @param generation Window 代际
     * @param incoming 接收领域化入站消息的队列
     * @return 可提交、回滚或关闭的会话
     * @throws IllegalStateException gateway 已关闭
     */
    public @NotNull Session open(
            @NotNull Player player,
            int containerId,
            long generation,
            @NotNull IncomingPacketQueue<MenuInput> incoming
    ) {
        this.requireOpen();
        ConnectionHandler handler = this.handlers.computeIfAbsent(player.getUniqueId(), ignored -> this.inject(player));
        while (true) {
            Session previous = handler.active.get();
            Session session = new Session(handler, previous, containerId, generation, incoming);
            if (handler.active.compareAndSet(previous, session)) {
                return session;
            }
        }
    }

    /**
     * 将一个或多个客户端包切换到玩家连接的 Netty event loop 后发送.
     *
     * <p>多个包会包装为 bundle, 保持窗口状态更新在客户端侧的同一处理批次内.</p>
     *
     * @param player 接收数据包的玩家
     * @param packets 要发送的数据包
     * @throws IllegalStateException gateway 已关闭
     */
    public void send(
            @NotNull Player player,
            @NotNull List<? extends Packet<? super ClientGamePacketListener>> packets
    ) {
        this.requireOpen();
        if (packets.isEmpty()) {
            return;
        }
        Packet<? super ClientGamePacketListener> packet;
        if (packets.size() == 1) {
            packet = packets.getFirst();
        } else {
            ArrayList<Packet<? super ClientGamePacketListener>> bundled = new ArrayList<>(packets.size());
            bundled.addAll(packets);
            packet = new ClientboundBundlePacket(bundled);
        }
        this.handlers.computeIfAbsent(player.getUniqueId(), ignored -> this.inject(player)).send(packet);
    }

    /**
     * 停止所有会话并从各连接的 pipeline 中移除注入的 handler.
     */
    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = null;
        for (ConnectionHandler handler : List.copyOf(this.handlers.values())) {
            try {
                this.remove(handler);
            } catch (RuntimeException | Error throwable) {
                if (failure == null) {
                    failure = throwable;
                } else {
                    failure.addSuppressed(throwable);
                }
            }
        }
        this.handlers.clear();
        if (failure != null) {
            rethrow(failure);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void handleJoin(PlayerJoinEvent event) {
        if (this.closed.get()) {
            return;
        }
        this.handlers.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> this.inject(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        ConnectionHandler handler = this.handlers.remove(event.getPlayer().getUniqueId());
        if (handler != null) {
            handler.active.set(null);
        }
    }

    /**
     * 在连接自己的 event loop 上安装 handler.
     *
     * <p>不能从玩家实体线程直接修改 Netty pipeline, 否则会与正在执行的入站读取竞争.</p>
     */
    private ConnectionHandler inject(Player player) {
        Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        ConnectionHandler handler = new ConnectionHandler(player.getName(), channel);
        Runnable injection = () -> {
            try {
                if (channel.pipeline().get(this.handlerName) == null) {
                    channel.pipeline().addBefore(MINECRAFT_HANDLER, this.handlerName, handler);
                }
            } catch (RuntimeException exception) {
                this.exceptionHandler.accept("Failed to inject SparrowUI packet handler", exception);
            }
        };
        if (channel.eventLoop().inEventLoop()) {
            injection.run();
        } else {
            channel.eventLoop().execute(injection);
        }
        return handler;
    }

    private void requireOpen() {
        if (this.closed.get()) {
            throw new IllegalStateException("packet gateway is closed");
        }
    }

    /**
     * 停用会话并在其所属 event loop 上移除 handler.
     */
    private void remove(ConnectionHandler handler) {
        handler.active.set(null);
        Runnable removal = () -> {
            try {
                if (handler.channel.pipeline().get(this.handlerName) == handler) {
                    handler.channel.pipeline().remove(this.handlerName);
                }
            } catch (NoSuchElementException ignored) {
                // 连接关闭或服务器停服时 pipeline 可能已经移除 handler.
            }
        };
        if (handler.channel.eventLoop().inEventLoop()) {
            removal.run();
        } else {
            handler.channel.eventLoop().execute(removal);
        }
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
    }

    /**
     * 一个可回滚的活动 Window 入站包绑定.
     *
     * <p>构造时即开始捕获数据包, 以避免打开包发送与客户端首个点击之间的空窗. 成功打开后
     * {@link #commit()} 固化替换; 失败时 {@link #rollback()} 恢复前一个 Session.</p>
     */
    public static final class Session implements AutoCloseable {
        private final ConnectionHandler owner;
        private final AtomicReference<Session> replaced;
        private final int containerId;
        private final long generation;
        private final IncomingPacketQueue<MenuInput> incoming;

        private Session(
                ConnectionHandler owner,
                Session replaced,
                int containerId,
                long generation,
                IncomingPacketQueue<MenuInput> incoming
        ) {
            this.owner = owner;
            this.replaced = new AtomicReference<>(replaced);
            this.containerId = containerId;
            this.generation = generation;
            this.incoming = incoming;
        }

        /**
         * 终止当前 Session, 不恢复已被替换的旧会话.
         */
        @Override
        public void close() {
            this.replaced.set(null);
            this.owner.active.compareAndSet(this, null);
        }

        /**
         * 确认当前 Session 已成功打开, 不再保留旧会话作为回滚点.
         */
        public void commit() {
            this.replaced.set(null);
        }

        /**
         * 恢复打开当前 Session 前仍处于活动状态的会话.
         */
        public void rollback() {
            Session previous = this.replaced.getAndSet(null);
            this.owner.active.compareAndSet(this, previous);
        }

        /**
         * 将本会话关心的客户端包转换为领域消息.
         *
         * <p>容器操作会被消费, 避免 NMS 根据客户端预测修改代理菜单. Pong 仅被观察以确认
         * Window 状态, 仍须继续交给原版处理.</p>
         */
        private boolean accept(Packet<?> packet) {
            MenuInput input;
            boolean consume = true;
            switch (packet) {
                case ServerboundContainerClickPacket click -> input = interaction(click);
                case ServerboundContainerClosePacket close -> input = new MenuInput.Close(close.getContainerId());
                case ServerboundSelectBundleItemPacket selection -> input = new MenuInput.BundleSelection(
                        this.containerId,
                        selection.slotId(),
                        selection.selectedItemIndex()
                );
                case ServerboundPongPacket pong -> {
                    input = new MenuInput.Pong(pong.getId());
                    consume = false;
                }
                default -> {
                    return false;
                }
            }
            // Pong 只监听而不拦截; 其他 Window 包由领域层作为权威处理.
            this.incoming.offer(this.generation, input);
            return consume;
        }

        /**
         * 将 NMS 容器输入完整解码为稳定的 Bukkit 点击类型或拖拽步骤.
         * 非法 button 组合保留为 {@link ClickType#UNKNOWN}, 交给实体线程触发权威状态纠正.
         */
        private static MenuInput.Interaction interaction(ServerboundContainerClickPacket click) {
            return switch (click.containerInput()) {
                case PICKUP -> switch (click.buttonNum()) {
                    case 0 -> singleClick(
                            click,
                            click.slotNum() == -999 ? ClickType.WINDOW_BORDER_LEFT : ClickType.LEFT
                    );
                    case 1 -> singleClick(
                            click,
                            click.slotNum() == -999 ? ClickType.WINDOW_BORDER_RIGHT : ClickType.RIGHT
                    );
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case QUICK_MOVE -> switch (click.buttonNum()) {
                    case 0 -> singleClick(click, ClickType.SHIFT_LEFT);
                    case 1 -> singleClick(click, ClickType.SHIFT_RIGHT);
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case SWAP -> {
                    if (click.buttonNum() >= 0 && click.buttonNum() <= 8) {
                        yield singleClick(click, ClickType.NUMBER_KEY, click.buttonNum());
                    }
                    if (click.buttonNum() == 40) {
                        yield singleClick(click, ClickType.SWAP_OFFHAND);
                    }
                    yield singleClick(click, ClickType.UNKNOWN);
                }
                case CLONE -> singleClick(
                        click,
                        click.buttonNum() == 2 ? ClickType.MIDDLE : ClickType.UNKNOWN
                );
                case THROW -> switch (click.buttonNum()) {
                    case 0 -> singleClick(click, ClickType.DROP);
                    case 1 -> singleClick(click, ClickType.CONTROL_DROP);
                    default -> singleClick(click, ClickType.UNKNOWN);
                };
                case QUICK_CRAFT -> dragStep(click);
                case PICKUP_ALL -> singleClick(
                        click,
                        click.buttonNum() == 0 ? ClickType.DOUBLE_CLICK : ClickType.UNKNOWN
                );
            };
        }

        private static MenuInput.Click singleClick(
                ServerboundContainerClickPacket packet,
                ClickType clickType
        ) {
            return singleClick(packet, clickType, -1);
        }

        private static MenuInput.Click singleClick(
                ServerboundContainerClickPacket packet,
                ClickType clickType,
                int hotbarButton
        ) {
            return new MenuInput.Click(
                    packet.containerId(),
                    packet.stateId(),
                    packet.slotNum(),
                    clickType,
                    hotbarButton,
                    ClientMenuPrediction.from(packet)
            );
        }

        /**
         * 解码 QUICK_CRAFT 的非连续 button 编码.
         * 无效编码转为 UNKNOWN 单次点击, 使解释器同时重置未完成手势并请求状态纠正.
         */
        private static MenuInput.Interaction dragStep(ServerboundContainerClickPacket packet) {
            return switch (packet.buttonNum()) {
                case 0 -> dragStep(packet, ClickType.LEFT, MenuInput.DragPhase.START);
                case 1 -> dragStep(packet, ClickType.LEFT, MenuInput.DragPhase.ADD);
                case 2 -> dragStep(packet, ClickType.LEFT, MenuInput.DragPhase.END);
                case 4 -> dragStep(packet, ClickType.RIGHT, MenuInput.DragPhase.START);
                case 5 -> dragStep(packet, ClickType.RIGHT, MenuInput.DragPhase.ADD);
                case 6 -> dragStep(packet, ClickType.RIGHT, MenuInput.DragPhase.END);
                case 8 -> dragStep(packet, ClickType.MIDDLE, MenuInput.DragPhase.START);
                case 9 -> dragStep(packet, ClickType.MIDDLE, MenuInput.DragPhase.ADD);
                case 10 -> dragStep(packet, ClickType.MIDDLE, MenuInput.DragPhase.END);
                default -> singleClick(packet, ClickType.UNKNOWN);
            };
        }

        private static MenuInput.DragStep dragStep(
                ServerboundContainerClickPacket packet,
                ClickType clickType,
                MenuInput.DragPhase phase
        ) {
            return new MenuInput.DragStep(
                    packet.containerId(),
                    packet.stateId(),
                    packet.slotNum(),
                    clickType,
                    phase,
                    ClientMenuPrediction.from(packet)
            );
        }
    }

    /**
     * 连接级 handler, 根据原子活动引用决定捕获还是继续传递入站包.
     */
    private final class ConnectionHandler extends ChannelInboundHandlerAdapter {
        private final String playerName;
        private final Channel channel;
        private final AtomicReference<Session> active = new AtomicReference<>();

        private ConnectionHandler(String playerName, Channel channel) {
            this.playerName = playerName;
            this.channel = channel;
        }

        private void send(Packet<? super ClientGamePacketListener> packet) {
            Runnable send = () -> this.channel.writeAndFlush(packet);
            if (this.channel.eventLoop().inEventLoop()) {
                send.run();
            } else {
                this.channel.eventLoop().execute(send);
            }
        }

        /**
         * 仅消费当前 Session 接管的容器操作. 其他包以及被动观察的 Pong 继续传给原版链路.
         */
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            Session session = this.active.get();
            try {
                if (session != null && message instanceof Packet<?> packet && session.accept(packet)) {
                    return;
                }
            } catch (RuntimeException | Error throwable) {
                PacketListener.this.exceptionHandler.accept(
                        "Failed to capture an incoming Window packet for " + this.playerName,
                        throwable
                );
                return;
            }
            super.channelRead(context, message);
        }
    }
}
