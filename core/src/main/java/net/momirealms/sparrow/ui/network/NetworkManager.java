package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.listener.configuration.FinishConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.game.ConfigurationAcknowledgedListener;
import net.momirealms.sparrow.ui.network.listener.game.LoginListener;
import net.momirealms.sparrow.ui.network.listener.game.StartConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.handshake.IntentionListener;
import net.momirealms.sparrow.ui.network.listener.login.LoginAcknowledgedListener;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.BundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundBundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerConnectionListenerProxy;
import net.momirealms.sparrow.ui.state.ListSignal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 数据包监听的接管者, 在服务端 acceptor 与每条玩家连接上安装 ByteBuf 与 NMS 对象两层 handlers.
 *
 * <p>全服共用一个实例, 经 {@link SparrowUI#networkManager()} 获取. 另行构造的实例会使用相同的 handler 名,
 * 后者的注入将顶掉前者.</p>
 */
@ApiStatus.Experimental
public final class NetworkManager implements Listener, AutoCloseable {
    private static final String MINECRAFT_SPLITTER = "splitter";

    private final PacketIdRegistry packetIds; // 当前服务端运行期包 ID 索引
    final String connectionHandlerName;      // acceptor 上的子连接捕获 handler
    final String preInitializerName;         // 子连接注册前的临时 initializer
    final String packetBridgeName;           // NMS 对象层双向监听 handler
    final String decoderName;                // ByteBuf 入站监听 handler
    final String encoderName;                // ByteBuf 出站监听 handler

    private volatile ByteBufPacketListenerHolder[][] serverboundByteBufListeners;
    private volatile ByteBufPacketListenerHolder[][] clientboundByteBufListeners;
    private volatile Map<Class<?>, NMSPacketListener> serverboundNMSListeners = Map.of(); // NMS Class COW 快照
    private volatile Map<Class<?>, NMSPacketListener> clientboundNMSListeners = Map.of();

    private final Map<ChannelPipeline, NetworkUser> users = new ConcurrentHashMap<>();
    private final Map<UUID, NetworkUser> onlineUsers = new ConcurrentHashMap<>();
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();
    final AtomicBoolean closed = new AtomicBoolean(); // 与 pipeline 重定位共享的关闭门闩

    /**
     * 创建管理器并立即接管服务端 acceptor 与已有玩家连接.
     */
    @ApiStatus.Internal
    public NetworkManager() {
        this.packetIds = new PacketIdRegistry();
        this.serverboundByteBufListeners = createByteBufListeners(this.packetIds, PacketFlow.SERVERBOUND);
        this.clientboundByteBufListeners = createByteBufListeners(this.packetIds, PacketFlow.CLIENTBOUND);

        Plugin plugin = SparrowUI.getInstance().getPlugin();
        String prefix = "sparrow_ui_" + plugin.getName().toLowerCase(Locale.ROOT);
        this.connectionHandlerName = prefix + "_connection_handler";
        this.preInitializerName = prefix + "_pre_initializer";
        this.packetBridgeName = prefix + "_packet_bridge";
        this.decoderName = prefix + "_decoder";
        this.encoderName = prefix + "_encoder";

        this.registerProtocolStateListeners();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        Object server = MinecraftServerProxy.INSTANCE.getServer();
        Object serverConnection = MinecraftServerProxy.INSTANCE.getConnection(server);
        this.installServerInjection(server);
        this.injectExistingConnections(ServerConnectionListenerProxy.INSTANCE.connections(serverConnection));
    }

    // 按运行期 ID 空间创建无锁读取的定长路由表.
    private static ByteBufPacketListenerHolder[][] createByteBufListeners(PacketIdRegistry packetIds, PacketFlow flow) {
        ConnectionState[] states = ConnectionState.values();
        ByteBufPacketListenerHolder[][] listeners = new ByteBufPacketListenerHolder[states.length][];
        for (int stateIndex = 0; stateIndex < states.length; stateIndex++) {
            listeners[stateIndex] = new ByteBufPacketListenerHolder[packetIds.count(states[stateIndex], flow)];
        }
        return listeners;
    }

    // 注册两条方向各自推进协议阶段所需的内部监听器.
    private void registerProtocolStateListeners() {
        this.registerByteBufPacketListener(IntentionListener.INSTANCE, "minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(LoginAcknowledgedListener.INSTANCE, "minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(FinishConfigurationListener.INSTANCE, "minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(LoginListener.INSTANCE, "minecraft:login", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.registerByteBufPacketListener(StartConfigurationListener.INSTANCE, "minecraft:start_configuration", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.registerByteBufPacketListener(ConfigurationAcknowledgedListener.INSTANCE, "minecraft:configuration_acknowledged", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
    }

    // 注入已有 acceptor 与在线连接, 并接管后续新增的 acceptor.
    private void installServerInjection(Object server) {
        Object serverConnection = MinecraftServerProxy.INSTANCE.getConnection(server);
        List<ChannelFuture> channels = ServerConnectionListenerProxy.INSTANCE.channels(serverConnection);
        ListSignal<ChannelFuture> listener = ListSignal.wrap(channels).beforeAdd(future -> {
            this.injectAcceptorChannel(future);
            return future;
        });
        synchronized (channels) {
            // 持有原 channels monitor 补注入已有 acceptor, 再发布监听新增元素的包装器.
            for (int index = 0; index < channels.size(); index++) {
                this.injectAcceptorChannel(channels.get(index));
            }
            ServerConnectionListenerProxy.INSTANCE.channels(serverConnection, listener);
        }
    }

    // 在 acceptor pipeline 上安装子连接预注入 handler.
    private void injectAcceptorChannel(ChannelFuture future) {
        if (this.closed.get()) return;
        Channel channel = future.channel();
        this.serverChannels.add(channel);

        ChannelPipeline pipeline = channel.pipeline();
        removeHandler(pipeline, this.connectionHandlerName);
        ServerChannelHandler handler = new ServerChannelHandler();
        if (pipeline.get("SpigotNettyServerChannelHandler#0") != null) {
            pipeline.addAfter("SpigotNettyServerChannelHandler#0", this.connectionHandlerName, handler);
        } else if (pipeline.get("floodgate-init") != null) {
            pipeline.addAfter("floodgate-init", this.connectionHandlerName, handler);
        } else if (pipeline.get("MinecraftPipeline#0") != null) {
            pipeline.addAfter("MinecraftPipeline#0", this.connectionHandlerName, handler);
        } else {
            pipeline.addFirst(this.connectionHandlerName, handler);
        }
    }

    // 只接管能与当前在线 Bukkit 玩家对应的已有连接.
    private void injectExistingConnections(List<?> connections) {
        HashMap<Channel, Player> playersByChannel = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = this.channel(player);
            if (channel != null) {
                playersByChannel.put(channel, player);
            }
        }
        List<?> snapshot;
        // connections 是 NMS synchronizedList, 复合快照需要持有它的 monitor.
        synchronized (connections) {
            snapshot = List.copyOf(connections);
        }
        for (int index = 0; index < snapshot.size(); index++) {
            Channel channel = (Channel) ConnectionProxy.INSTANCE.channel(snapshot.get(index));
            Player player = playersByChannel.get(channel);
            if (player == null) continue;
            this.execute(channel, () -> {
                if (this.closed.get()) return;
                NetworkUser user = this.injectConnectionChannel(channel);
                if (user != null) {
                    user.setConnectionState(ConnectionState.PLAY);
                    this.bindPlayer(user, player);
                }
            });
        }
    }

    // 在已完成 vanilla 初始化的连接上建立用户索引与两层数据包 handlers.
    @Nullable
    NetworkUser injectConnectionChannel(Channel channel) {
        if (isFakeChannel(channel)) return null;
        ChannelPipeline pipeline = channel.pipeline();
        // splitter 出现后 vanilla 基础 pipeline 已稳定, 后续 handlers 可以使用固定锚点.
        if (pipeline.get(MINECRAFT_SPLITTER) == null) {
            channel.close();
            return null;
        }

        NetworkUser user = this.users.get(pipeline);
        if (user == null) {
            NetworkUser created = new NetworkUser(this, channel);
            NetworkUser existing = this.users.putIfAbsent(pipeline, created);
            user = existing != null ? existing : created;
            // 已经关闭的 channel 会同步回调, 因此登记完成之后才挂断开清理.
            if (existing == null) {
                channel.closeFuture().addListener((ChannelFutureListener) future -> this.handleDisconnection(created));
            }
        }
        // 重复注入沿用同一个 NetworkUser, handler 则按当前第三方顺序重新安装.
        this.removeConnectionHandlers(user);

        // 对象桥紧贴 NMS Connection 前方, 两个方向都能看到解码后的包对象.
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (ConnectionProxy.CLASS.isInstance(entry.getValue())) {
                pipeline.addBefore(entry.getKey(), this.packetBridgeName, new NMSPacketBridge(user));
                break;
            }
        }
        // Buffer handlers 的第三方相对位置由 NetworkPipelineOrder 单独维护.
        NetworkPipelineOrder.addByteBufHandlers(
                this,
                pipeline,
                new ByteBufDecoder(user),
                new ByteBufEncoder(user)
        );
        return user;
    }

    static boolean isFakeChannel(Channel channel) {
        String name = channel.getClass().getSimpleName();
        return name.equals("FakeChannel") || name.equals("SpoofedChannel");
    }

    // 玩家绑定与连接回收

    @EventHandler(priority = EventPriority.LOWEST)
    private void handleJoin(PlayerJoinEvent event) {
        if (this.closed.get()) return;
        Player player = event.getPlayer();
        Channel channel = this.channel(player);
        // 跳过假人.
        if (channel == null || isFakeChannel(channel)) return;
        NetworkUser user = this.user(channel);
        if (user != null) {
            this.bindPlayer(user, player);
            return;
        }
        // 说明这条连接在管理器安装之前就登录到一半, acceptor 与在线玩家两条注入都错过了.
        // 这里重新补注入, 并且玩家此刻已经进入游戏, 两个方向的协议阶段都是 PLAY.
        this.execute(channel, () -> {
            if (this.closed.get()) return;
            NetworkUser injected = this.injectConnectionChannel(channel);
            if (injected == null) {
                SparrowUI.getInstance().handleException("Missing SparrowUI network user for " + player.getName(), new IllegalStateException("player channel was not injected"));
                return;
            }
            injected.setConnectionState(ConnectionState.PLAY);
            this.bindPlayer(injected, player);
        });
    }

    private void bindPlayer(NetworkUser user, Player player) {
        user.player(player);
        this.onlineUsers.put(player.getUniqueId(), user);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        NetworkUser user = this.onlineUsers.remove(event.getPlayer().getUniqueId());
        if (user != null && user.player() == event.getPlayer()) {
            user.player(null);
        }
    }

    // 同时撤销 pipeline 与 UUID 两份索引, 随后清理当前连接的 handlers.
    private void handleDisconnection(NetworkUser user) {
        this.users.remove(user.channel().pipeline(), user);
        UUID uuid = user.uuid();
        if (uuid != null) {
            this.onlineUsers.remove(uuid, user);
        }
        user.player(null);
        this.removeConnectionHandlers(user);
    }

    private void removeConnectionHandlers(NetworkUser user) {
        ChannelPipeline pipeline = user.channel().pipeline();
        removeHandler(pipeline, this.packetBridgeName);
        NetworkPipelineOrder.removeByteBufHandlers(this, pipeline);
    }

    private static void removeHandler(ChannelPipeline pipeline, String name) {
        if (pipeline.get(name) != null) {
            pipeline.remove(name);
        }
    }

    // 假人拥有完整的 NMS 连接对象, 但它从未真正建立过 channel.
    @Nullable
    private Channel channel(Player player) {
        Object serverPlayer = CraftEntityProxy.INSTANCE.entity(player);
        Object packetListener = ServerPlayerProxy.INSTANCE.connection(serverPlayer);
        Object connection = ServerCommonPacketListenerImplProxy.INSTANCE.connection(packetListener);
        return (Channel) ConnectionProxy.INSTANCE.channel(connection);
    }

    // 监听器注册与派发

    /**
     * 按运行期注册名, 协议阶段和方向注册 ByteBuf 监听器.
     *
     * @param listener 监听器
     * @param name 完整注册名, 当前版本不存在时跳过注册
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @throws IllegalStateException 管理器已关闭或该路由已有监听器时
     */
    public synchronized void registerByteBufPacketListener(@NotNull ByteBufPacketListener listener, @NotNull String name, @NotNull ConnectionState state, @NotNull PacketFlow flow) {
        this.requireOpen();
        int packetId = this.packetIds.byName(name, state, flow);
        if (packetId == -1) return;
        boolean serverbound = flow == PacketFlow.SERVERBOUND;
        ByteBufPacketListenerHolder[][] current = serverbound ? this.serverboundByteBufListeners : this.clientboundByteBufListeners;
        ByteBufPacketListenerHolder[] currentRoute = current[state.ordinal()];
        if (currentRoute[packetId] != null) {
            throw new IllegalStateException("Packet listener already registered for " + name + " (" + state + "/" + flow + "/" + packetId + ")");
        }
        // 只复制命中的 route 与状态索引, Netty 线程继续无锁读取旧快照.
        ByteBufPacketListenerHolder[] updatedRoute = currentRoute.clone();
        updatedRoute[packetId] = new ByteBufPacketListenerHolder(name, listener);
        ByteBufPacketListenerHolder[][] updated = current.clone();
        updated[state.ordinal()] = updatedRoute;
        if (serverbound) {
            this.serverboundByteBufListeners = updated;
        } else {
            this.clientboundByteBufListeners = updated;
        }
    }

    /**
     * 按 NMS 包的运行期 Class 和方向注册对象层监听器.
     *
     * @param listener 对象层监听器, null 时跳过注册
     * @param packetClass NMS 包类型, null 时跳过注册
     * @param flow 包的传输方向, 决定监听器收到的是 onPacketReceive 还是 onPacketSend
     * @throws IllegalStateException 管理器已关闭或该方向的该类型已有监听器时
     */
    public synchronized void registerNMSPacketListener(@Nullable NMSPacketListener listener, @Nullable Class<?> packetClass, @NotNull PacketFlow flow) {
        this.requireOpen();
        if (listener == null || packetClass == null) return;
        boolean serverbound = flow == PacketFlow.SERVERBOUND;
        Map<Class<?>, NMSPacketListener> current = serverbound ? this.serverboundNMSListeners : this.clientboundNMSListeners;
        if (current.containsKey(packetClass)) {
            throw new IllegalStateException("NMS packet listener already registered for " + packetClass.getName() + " (" + flow + ")");
        }
        // 发布新的只读快照, 已经进入派发的线程仍可安全读完旧表.
        HashMap<Class<?>, NMSPacketListener> listeners = new HashMap<>(current);
        listeners.put(packetClass, listener);
        if (serverbound) {
            this.serverboundNMSListeners = Map.copyOf(listeners);
        } else {
            this.clientboundNMSListeners = Map.copyOf(listeners);
        }
    }

    // 在原始帧上按 route 派发监听器, 并维护取消、改写与异常后的指针契约.
    // serverbound 决定读哪个方向的定长表与回调, 两个 handler 各自恒定传常量.
    private boolean handleByteBuf(NetworkUser user, ByteBuf buffer, boolean serverbound) {
        if (!buffer.isReadable() || user.bypassing()) {
            return buffer.isReadable();
        }
        // 先读包 ID 探测定长路由, 未命中时恢复 readerIndex 且不创建事件对象.
        int initialReaderIndex = buffer.readerIndex();
        int packetId;
        try {
            packetId = PacketBuf.readVarInt(buffer);
        } catch (Throwable throwable) {
            buffer.readerIndex(initialReaderIndex);
            throw throwable;
        }
        ByteBufPacketListenerHolder[] listeners = serverbound
                ? this.serverboundByteBufListeners[user.decoderState().ordinal()]
                : this.clientboundByteBufListeners[user.encoderState().ordinal()];
        if (packetId < 0 || packetId >= listeners.length) {
            buffer.readerIndex(initialReaderIndex);
            return true;
        }
        ByteBufPacketListenerHolder holder = listeners[packetId];
        if (holder == null) {
            buffer.readerIndex(initialReaderIndex);
            return true;
        }
        // 命中后才记录完整写指针并创建事件, 供异常路径恢复原帧.
        int initialWriterIndex = buffer.writerIndex();
        PacketBuf packetBuffer = new PacketBuf(buffer);
        ByteBufPacketEvent event = new ByteBufPacketEvent(packetId, packetBuffer, buffer.readerIndex());
        try {
            if (serverbound) {
                holder.listener().onPacketReceive(user, event);
            } else {
                holder.listener().onPacketSend(user, event);
            }
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to handle packet " + holder.name(), throwable);
            // 已经变更或取消的半成品帧不可继续传播, 纯读取失败则恢复原始指针.
            if (event.changed() || event.cancelled()) {
                buffer.clear();
                return false;
            }
            buffer.setIndex(initialReaderIndex, initialWriterIndex);
            return true;
        }
        if (event.cancelled()) {
            buffer.clear();
            return false;
        }
        if (!event.changed()) {
            buffer.setIndex(initialReaderIndex, initialWriterIndex);
        }
        return buffer.isReadable();
    }

    // bundle 子包递归共用根事件, 让取消与替换作用于完整的出站对象.
    // 表随递归传递, 整个 bundle 因此读的是同一份快照.
    @Nullable
    private NMSPacketEvent handleNMSPacketSend(NetworkUser user, Object root, Object packet, @Nullable NMSPacketEvent event, Map<Class<?>, NMSPacketListener> listeners) {
        if (ClientboundBundlePacketProxy.CLASS.isInstance(packet)) {
            for (Object child : BundlePacketProxy.INSTANCE.subPackets(packet)) {
                event = this.handleNMSPacketSend(user, root, child, event, listeners);
            }
            return event;
        }
        NMSPacketListener listener = listeners.get(packet.getClass());
        if (listener == null) {
            return event;
        }
        NMSPacketEvent resolved = event == null ? new NMSPacketEvent(root) : event;
        try {
            listener.onPacketSend(user, resolved, packet);
        } catch (Throwable throwable) {
            SparrowUI.getInstance().handleException("Failed to handle NMS packet " + packet.getClass().getName(), throwable);
        }
        return resolved;
    }

    // 查询与诊断

    /**
     * 返回 Bukkit 玩家当前绑定的连接用户.
     *
     * @param player Bukkit 玩家
     * @return 连接用户, 注入尚未建立或玩家没有真实连接时为 null
     */
    @Nullable
    public NetworkUser user(@NotNull Player player) {
        // 已进入游戏的玩家直接按 UUID 命中, 只有绑定之前才需要解析 channel.
        NetworkUser online = this.onlineUsers.get(player.getUniqueId());
        if (online != null) {
            return online;
        }
        Channel channel = this.channel(player);
        return channel == null ? null : this.users.get(channel.pipeline());
    }

    /**
     * 返回 channel 当前绑定的连接用户.
     *
     * @param channel Minecraft 连接 channel
     * @return 连接用户, 注入尚未建立时为 null
     */
    @Nullable
    public NetworkUser user(@NotNull Channel channel) {
        return this.users.get(channel.pipeline());
    }

    /**
     * 返回当前服务端的运行期包 ID 索引.
     *
     * @return 包 ID 注册表
     */
    @NotNull
    public PacketIdRegistry packetIds() {
        return this.packetIds;
    }

    /**
     * 按协议阶段和方向输出当前服务端的运行期包 ID 表.
     *
     * @param output 每行表项的接收者
     */
    public void dumpPacketIds(@NotNull Consumer<String> output) {
        this.packetIds.dump(output);
    }

    // 发送

    /**
     * 在连接 event loop 中发送一个 NMS 客户端包, 并绕过当前管理器自己的监听器.
     *
     * @param user 接收数据包的连接
     * @param packet NMS 客户端包
     * @throws IllegalStateException 管理器已关闭时
     */
    public void send(@NotNull NetworkUser user, @NotNull Object packet) {
        this.requireOpen();
        this.writeBypassed(user, packet);
    }

    /**
     * 在连接 event loop 中发送一批 NMS 客户端包, 多个包会合成一个原版 bundle.
     *
     * @param user 接收数据包的连接
     * @param packets NMS 客户端包列表
     * @throws IllegalStateException 管理器已关闭时
     */
    public void send(@NotNull NetworkUser user, @NotNull List<?> packets) {
        this.requireOpen();
        if (packets.isEmpty()) {
            return;
        }
        Object packet;
        if (packets.size() == 1) {
            packet = packets.getFirst();
        } else {
            ArrayList<Object> bundled = new ArrayList<>(packets.size());
            bundled.addAll(packets);
            packet = ClientboundBundlePacketProxy.INSTANCE.newInstance(bundled);
        }
        this.writeBypassed(user, packet);
    }

    /**
     * 发送已经包含包 ID 的预序列化帧, buffer 所有权随调用转交给 Netty pipeline.
     *
     * @param user 接收数据包的连接
     * @param buffer 预序列化帧
     * @throws IllegalStateException 管理器已关闭时
     */
    public void sendByteBuf(@NotNull NetworkUser user, @NotNull ByteBuf buffer) {
        this.requireOpen();
        this.writeBypassed(user, buffer);
    }

    private void writeBypassed(NetworkUser user, Object message) {
        Runnable write = () -> {
            user.beginBypass();
            try {
                user.channel().writeAndFlush(message);
            } finally {
                user.endBypass();
            }
        };
        this.execute(user.channel(), write);
    }

    private void execute(Channel channel, Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    // 关闭

    private void requireOpen() {
        if (this.closed.get()) {
            throw new IllegalStateException("network manager is closed");
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        HandlerList.unregisterAll(this);
        for (Channel channel : Set.copyOf(this.serverChannels)) {
            this.execute(channel, () -> removeHandler(channel.pipeline(), this.connectionHandlerName));
        }
        List<NetworkUser> users = List.copyOf(this.users.values());
        for (int index = 0; index < users.size(); index++) {
            NetworkUser user = users.get(index);
            this.execute(user.channel(), () -> this.removeConnectionHandlers(user));
        }
        this.serverChannels.clear();
        this.users.clear();
        this.onlineUsers.clear();
    }

    // Netty handlers

    private final class ServerChannelHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            Channel channel = (Channel) message;
            removeHandler(channel.pipeline(), NetworkManager.this.preInitializerName);
            channel.pipeline().addLast(NetworkManager.this.preInitializerName, new PreChannelInitializer());
            super.channelRead(context, message);
        }
    }

    private final class PreChannelInitializer extends ChannelInboundHandlerAdapter {
        private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(ChannelInitializer.class);

        @Override
        public void channelRegistered(ChannelHandlerContext context) {
            try {
                if (!NetworkManager.this.closed.get()) {
                    NetworkManager.this.injectConnectionChannel(context.channel());
                }
            } catch (Throwable throwable) {
                this.exceptionCaught(context, throwable);
            } finally {
                if (context.pipeline().context(this) != null) {
                    context.pipeline().remove(this);
                }
            }
            context.pipeline().fireChannelRegistered();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable throwable) {
            LOGGER.warn("Failed to inject channel: " + context.channel(), throwable);
            context.close();
        }
    }

    @ChannelHandler.Sharable
    final class ByteBufDecoder extends MessageToMessageDecoder<ByteBuf> {
        private final NetworkUser user;

        ByteBufDecoder(NetworkUser user) {
            this.user = user;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf buffer, List<Object> output) {
            if (NetworkManager.this.handleByteBuf(this.user, buffer, true)) {
                output.add(buffer.retain());
            }
        }
    }

    private final class NMSPacketBridge extends ChannelDuplexHandler {
        private final NetworkUser user;

        private NMSPacketBridge(NetworkUser user) {
            this.user = user;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
            // 该方向没有监听器时不必碰包对象.
            Map<Class<?>, NMSPacketListener> listeners = NetworkManager.this.serverboundNMSListeners;
            if (listeners.isEmpty() || this.user.bypassing()) {
                super.channelRead(context, packet);
                return;
            }
            NMSPacketListener listener = listeners.get(packet.getClass());
            if (listener == null) {
                super.channelRead(context, packet);
                return;
            }
            NMSPacketEvent event = new NMSPacketEvent(packet);
            try {
                listener.onPacketReceive(this.user, event, packet);
            } catch (Throwable throwable) {
                SparrowUI.getInstance().handleException("Failed to handle NMS packet " + packet.getClass().getName(), throwable);
            }
            if (event.cancelled()) {
                return;
            }
            super.channelRead(context, event.usingReplacement() ? event.replacement() : packet);
        }

        @Override
        public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            // 该方向没有监听器时不必展开 bundle, 也不必碰包对象.
            Map<Class<?>, NMSPacketListener> listeners = NetworkManager.this.clientboundNMSListeners;
            if (listeners.isEmpty() || this.user.bypassing()) {
                super.write(context, packet, promise);
                return;
            }
            NMSPacketEvent event = NetworkManager.this.handleNMSPacketSend(this.user, packet, packet, null, listeners);
            if (event == null) {
                super.write(context, packet, promise);
                return;
            }
            if (event.cancelled()) {
                promise.trySuccess();
                return;
            }
            super.write(context, event.usingReplacement() ? event.replacement() : packet, promise);
        }
    }

    @ChannelHandler.Sharable
    final class ByteBufEncoder extends MessageToMessageEncoder<ByteBuf> {
        private final NetworkUser user;

        ByteBufEncoder(NetworkUser user) {
            this.user = user;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            try {
                super.write(context, message, promise);
            } catch (EncoderException exception) {
                if (this.hasCause(exception, CancelPacketException.INSTANCE)) {
                    promise.trySuccess();
                    return;
                }
                throw exception;
            }
        }

        // vanilla 把 compress 挂在 prepender 之后, 出站时它恒在本 handler 之后执行, 因此这里看到的一定是明文帧.
        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf buffer, List<Object> output) {
            NetworkManager.this.handleByteBuf(this.user, buffer, false);
            if (buffer.isReadable()) {
                output.add(buffer.retain());
                return;
            }
            throw CancelPacketException.INSTANCE;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable throwable) throws Exception {
            if (this.hasCause(throwable, CancelPacketException.INSTANCE)) {
                return;
            }
            super.exceptionCaught(context, throwable);
        }

        private boolean hasCause(Throwable throwable, Throwable expected) {
            Throwable current = throwable;
            while (current != null) {
                if (current == expected) return true;
                current = current.getCause();
            }
            return false;
        }
    }

    private record ByteBufPacketListenerHolder(String name, ByteBufPacketListener listener) {
    }

    private static final class CancelPacketException extends RuntimeException {
        private static final CancelPacketException INSTANCE = new CancelPacketException();

        private CancelPacketException() {
            super(null, null, false, false);
        }
    }
}
