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
import net.momirealms.sparrow.ui.Subscription;
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
 * 接管服务端每条连接上的数据包, 让菜单能在出入两个方向改写或取消它们.
 *
 * <p>一条连接上装两层: 一层贴着原始 ByteBuf 帧, 一层贴着解码后的 NMS 包对象. 全服只需要一个实例,
 * 从 {@link SparrowUI#networkManager()} 取; handler 名是写死的, 再建一个会把前一个装好的顶掉.
 */
@ApiStatus.Experimental
public final class NetworkManager implements Listener, AutoCloseable {
    private static final String MINECRAFT_SPLITTER = "splitter";

    private final PacketIdRegistry packetIds; // 运行期包 ID 索引, 注册监听器时拿它把包名换成路由下标

    // 这五个名字都会出现在 Minecraft 的 ChannelPipeline 上, 一律带插件前缀, 出问题时一眼能认出是谁装的
    final String connectionHandlerName;      // acceptor 上拦新子连接的 handler
    final String preInitializerName;         // 子连接注册前临时顶上的 initializer, 注入完就摘掉
    final String packetBridgeName;           // 贴着 NMS Connection, 两个方向都能看到解码后的包对象
    final String decoderName;                // 客户端 -> 服务端, 在原始 ByteBuf 帧上派发
    final String encoderName;                // 服务端 -> 客户端, 在原始 ByteBuf 帧上派发

    // 派发线程不拿锁, 直接读这四个 volatile 引用; 注册时整体换一份新表, 已经在跑的派发用手上那份跑完就好
    private volatile ByteBufPacketListenerHolder[][] serverboundByteBufListeners;
    private volatile ByteBufPacketListenerHolder[][] clientboundByteBufListeners;
    private volatile Map<Class<?>, NMSPacketListener> serverboundNMSListeners = Map.of(); // 按 NMS Class 查, 发布新快照而不改旧表
    private volatile Map<Class<?>, NMSPacketListener> clientboundNMSListeners = Map.of();

    private final Map<ChannelPipeline, NetworkUser> users = new ConcurrentHashMap<>();  // 装过 handler 的连接, 派发时由它把 channel 换成 NetworkUser
    private final Map<UUID, NetworkUser> onlineUsers = new ConcurrentHashMap<>();      // 玩家进世界后额外挂一份, 按玩家查连接就不用走反射
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();         // acceptor channel, 关管理器时要把 handler 从它们上面摘掉
    final AtomicBoolean closed = new AtomicBoolean();                                  // 只让 close() 跑一次; pipeline 重定位每次都先看它
    @Nullable private Subscription acceptorHook;                                       // acceptor 名单的元素钩子凭证, 关掉它 NMS 手里的包装器就退回普通 List

    /**
     * 建好管理器, 并马上接管服务端 acceptor 和已经在线的玩家连接.
     * <p>全服只该有一份: handler 名固定, 再建一个会把前一份的注入顶掉.
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

    // 每个协议阶段一张定长表, 下标就是包 ID, 派发时不用查 Map 也不用加锁.
    // 长度问 PacketIdRegistry 要, 版本之间包数不同, 表也就跟着不同.
    private static ByteBufPacketListenerHolder[][] createByteBufListeners(PacketIdRegistry packetIds, PacketFlow flow) {
        ConnectionState[] states = ConnectionState.values();
        ByteBufPacketListenerHolder[][] listeners = new ByteBufPacketListenerHolder[states.length][];
        for (int stateIndex = 0; stateIndex < states.length; stateIndex++) {
            listeners[stateIndex] = new ByteBufPacketListenerHolder[packetIds.count(states[stateIndex], flow)];
        }
        return listeners;
    }

    // 协议阶段靠这几个包推进: 收到或发出它们时把 NetworkUser 的阶段换掉, 后面的帧才知道该查哪张路由表.
    private void registerProtocolStateListeners() {
        this.registerByteBufPacketListener(IntentionListener.INSTANCE, "minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(LoginAcknowledgedListener.INSTANCE, "minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(FinishConfigurationListener.INSTANCE, "minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
        this.registerByteBufPacketListener(LoginListener.INSTANCE, "minecraft:login", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.registerByteBufPacketListener(StartConfigurationListener.INSTANCE, "minecraft:start_configuration", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.registerByteBufPacketListener(ConfigurationAcknowledgedListener.INSTANCE, "minecraft:configuration_acknowledged", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
    }

    // 接管 acceptor: 已有的当场补注入, 之后新增的靠 ListSignal 的元素钩子接住.
    private void installServerInjection(Object server) {
        Object serverConnection = MinecraftServerProxy.INSTANCE.getConnection(server);
        List<ChannelFuture> channels = ServerConnectionListenerProxy.INSTANCE.channels(serverConnection);
        ListSignal<ChannelFuture> listener = ListSignal.wrap(channels);
        // 凭证是这个钩子的唯一强引用. close() 关掉它之后, NMS 手里那份包装器就退回普通 List, 不会再吊着本管理器.
        this.acceptorHook = listener.beforeAdd(future -> {
            this.injectAcceptorChannel(future);
            return future;
        });
        synchronized (channels) {
            // 拿着原来的 monitor 补注入已有的 acceptor, 再把包装器发布出去, 这中间进来的新 acceptor 一个都漏不掉.
            for (int index = 0; index < channels.size(); index++) {
                this.injectAcceptorChannel(channels.get(index));
            }
            ServerConnectionListenerProxy.INSTANCE.channels(serverConnection, listener);
        }
    }

    // 在 acceptor pipeline 上装一个 handler, 它读出新的子连接时就把那条连接先注入掉.
    // 锚点按平台挑: Spigot 系, Floodgate 和 vanilla 的初始 handler 名字各不相同, 一个都没命中就装到最前面.
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

    // 库是中途装进来的时候, 把和在线玩家对得上号的已有连接补上注入.
    // 对不上号的先放过, 它们进世界时会走 handleJoin 那条路.
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

    // 给一条连接建好 NetworkUser 再装上两层 handler. 重复调用沿用同一个 user, 并按当时 pipeline 的样子把 handler 重装一遍.
    @Nullable
    NetworkUser injectConnectionChannel(Channel channel) {
        if (isFakeChannel(channel)) return null;
        ChannelPipeline pipeline = channel.pipeline();
        // splitter 是 vanilla 基础 pipeline 搭完的标志; 没有它说明这条连接不是 Minecraft 协议, 直接关掉.
        if (pipeline.get(MINECRAFT_SPLITTER) == null) {
            channel.close();
            return null;
        }

        NetworkUser user = this.users.get(pipeline);
        if (user == null) {
            NetworkUser created = new NetworkUser(this, channel);
            NetworkUser existing = this.users.putIfAbsent(pipeline, created);
            user = existing != null ? existing : created;
            // 已经关掉的 channel 会同步回调 closeFuture, 所以先登记再挂清理, 免得清理抢先一步把登记又抹掉.
            if (existing == null) {
                channel.closeFuture().addListener((ChannelFutureListener) future -> this.handleDisconnection(created));
            }
        }
        // 先把旧的摘掉再交给下面重装, 重复注入因此不会留下两份 handler.
        this.removeConnectionHandlers(user);

        // 对象桥紧贴 NMS Connection 前方装: 解码出来的包对象进 Connection 之前先经过它, 出站也走这同一个 handler.
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (ConnectionProxy.CLASS.isInstance(entry.getValue())) {
                pipeline.addBefore(entry.getKey(), this.packetBridgeName, new NMSPacketBridge(user));
                break;
            }
        }
        // 两个 ByteBuf handler 该插在哪由 NetworkPipelineOrder 现算, 这里不用管第三方装了什么.
        NetworkPipelineOrder.addByteBufHandlers(
                this,
                pipeline,
                new ByteBufDecoder(user),
                new ByteBufEncoder(user)
        );
        return user;
    }

    // Leaves 的假人有完整的 NMS 连接对象, 但 channel 从没真接过包, 往上装 handler 会出事.
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
        // 假人没有真实 channel, 放过.
        if (channel == null || isFakeChannel(channel)) return;
        NetworkUser user = this.user(channel);
        if (user != null) {
            this.bindPlayer(user, player);
            return;
        }
        // 走到这里说明这条连接是中途插进来的, acceptor 和在线玩家两条注入都没赶上.
        // 此刻玩家已经进了游戏, 补注完直接把两个方向都按 PLAY 起算.
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

    // channel 关掉之后把两份索引都摘掉, 再从 pipeline 上收回自己装的 handler.
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

    // 从 Bukkit 玩家一路摸到 NMS Connection 上的 channel; 假人没有真连接, 这里会给出 null 或者 FakeChannel.
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
     * <p>注册名在当前版本不存在时静默跳过, 同一个监听器因此可以跨版本共用.
     *
     * @param listener 监听器
     * @param name 完整注册名
     * @param state 包所属协议阶段
     * @param flow 包的传输方向
     * @throws IllegalStateException 管理器已关闭, 或者这条路由上已经有人了
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
        // 只把命中的那一行和状态索引复制出来改, 派发线程手上的旧表继续有效, 不用加锁.
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
     * <p>Class 在当前版本不存在时传 null 就行, 注册会被跳过.
     *
     * @param listener 对象层监听器
     * @param packetClass NMS 包类型, null 表示这个版本没有它
     * @param flow 包的传输方向, 决定监听器收到的是 onPacketReceive 还是 onPacketSend
     * @throws IllegalStateException 管理器已关闭, 或者这个类上已经有人了
     */
    public synchronized void registerNMSPacketListener(@NotNull NMSPacketListener listener, @Nullable Class<?> packetClass, @NotNull PacketFlow flow) {
        this.requireOpen();
        if (packetClass == null) return;
        boolean serverbound = flow == PacketFlow.SERVERBOUND;
        Map<Class<?>, NMSPacketListener> current = serverbound ? this.serverboundNMSListeners : this.clientboundNMSListeners;
        if (current.containsKey(packetClass)) {
            throw new IllegalStateException("NMS packet listener already registered for " + packetClass.getName() + " (" + flow + ")");
        }
        // 发布一份新的只读快照, 已经在派发的线程可以把手上的旧表读完.
        HashMap<Class<?>, NMSPacketListener> listeners = new HashMap<>(current);
        listeners.put(packetClass, listener);
        if (serverbound) {
            this.serverboundNMSListeners = Map.copyOf(listeners);
        } else {
            this.clientboundNMSListeners = Map.copyOf(listeners);
        }
    }

    // 在原始帧上按包 ID 派发监听器, 返回值决定这一帧还往不往下走.
    // serverbound 决定查哪张表, 调哪个回调; 两个方向的 handler 各自传常量.
    private boolean handleByteBuf(NetworkUser user, ByteBuf buffer, boolean serverbound) {
        if (!buffer.isReadable() || user.bypassing()) {
            return buffer.isReadable();
        }
        // 先读包 ID 试路由. 没命中就把读指针拨回去, 连事件对象都不建, 这一帧照常往下走.
        int initialReaderIndex = buffer.readerIndex();
        int packetId = PacketBuf.readVarInt(buffer);
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
        // 真的命中才记下写指针并建事件, 后面出异常要靠它把整帧还原.
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
            // 半路改过或取消过的帧不能就这么放出去, 一律丢掉; 只是读失败的话把指针还原, 原样的帧还给原版处理.
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

    // bundle 的子包跟着根包走同一套监听, 事件挂在根包上, 所以取消和替换落到整个 bundle 上, 而不是单个子包.
    // 监听器表沿递归往下传, 一个 bundle 里所有子包读到的都是同一份快照.
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
     * 这个 Bukkit 玩家当前绑在哪个 {@link NetworkUser} 上.
     *
     * @param player Bukkit 玩家
     * @return 对应的 NetworkUser; 注入还没建立, 或者玩家没有真实连接时为 null
     */
    @Nullable
    public NetworkUser user(@NotNull Player player) {
        NetworkUser online = this.onlineUsers.get(player.getUniqueId());
        if (online != null) return online;
        Channel channel = this.channel(player);
        return channel == null ? null : this.users.get(channel.pipeline());
    }

    /**
     * 这条 channel 当前对应哪个 {@link NetworkUser}.
     *
     * @param channel Minecraft 连接 channel
     * @return 对应的 NetworkUser; 注入还没建立时为 null
     */
    @Nullable
    public NetworkUser user(@NotNull Channel channel) {
        return this.users.get(channel.pipeline());
    }

    /**
     * 当前服务端的运行期包 ID 索引.
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
     * 给连接发一个 NMS 客户端包, 写入落在连接自己的 event loop 上.
     * <p>发送期间临时绕过本管理器的监听器, 库自己造的包不会再被自己处理一遍.
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
     * 给连接发一批 NMS 客户端包, 多个包合成一个原版 bundle 一起走.
     * <p>绕过监听器的规则与单包发送一致.
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
     * 发一条已经带好包 ID 的预序列化帧, buffer 的所有权随之交给 Netty pipeline.
     *
     * @param user 接收数据包的连接
     * @param buffer 预序列化帧
     * @throws IllegalStateException 管理器已关闭时
     */
    public void sendByteBuf(@NotNull NetworkUser user, @NotNull ByteBuf buffer) {
        this.requireOpen();
        this.writeBypassed(user, buffer);
    }

    // 写入前后成对开关 bypass, 让这次发送不被自己的监听器再处理一遍.
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

    // 已经在 event loop 里就当场跑, 省一次排队, 也省得调用方以为任务还没执行.
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
        // 摘掉钩子, 之后再开的 acceptor 就不再被接管了.
        Subscription acceptorHook = this.acceptorHook;
        if (acceptorHook != null) {
            acceptorHook.close();
            this.acceptorHook = null;
        }
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

    // acceptor 读出来的元素就是一条刚建好的子连接: 先给它挂上预注入 initializer, 再交回 vanilla 继续初始化.
    private final class ServerChannelHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            Channel channel = (Channel) message;
            removeHandler(channel.pipeline(), NetworkManager.this.preInitializerName);
            channel.pipeline().addLast(NetworkManager.this.preInitializerName, new PreChannelInitializer());
            super.channelRead(context, message);
        }
    }

    // 抢在 vanilla 的 initializer 之前把连接注入掉, 跑完把自己摘下来, 让 vanilla 照常往下走.
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

    // 两个 ByteBuf handler 都是每条连接一个实例; 标 Sharable 是为了重定位时先 remove 再 add 不被 Netty 拒绝.
    @ChannelHandler.Sharable
    final class ByteBufDecoder extends MessageToMessageDecoder<ByteBuf> {
        private final NetworkUser user;

        ByteBufDecoder(NetworkUser user) {
            this.user = user;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf buffer, List<Object> output) {
            // 放行就 retain 一份交给下一个 handler, 不放行这一帧就到此为止.
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
            // 这个方向一个监听器都没有, 包对象连碰都不用碰.
            Map<Class<?>, NMSPacketListener> listeners = NetworkManager.this.serverboundNMSListeners;
            if (listeners.isEmpty()) {
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
            // 这个方向没有监听器, 或者这次发送本来就该绕过时, 连 bundle 都不用展开.
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
                // encode 抛的取消异常会被包成 EncoderException, 翻一遍因果链认领下来, 认到就当写入成功.
                if (this.hasCause(exception, CancelPacketException.INSTANCE)) {
                    promise.trySuccess();
                    return;
                }
                throw exception;
            }
        }

        // vanilla 的 compress 挂在 prepender 之后, 出站时它总是排在本 handler 后面执行, 所以这里拿到的一定是明文帧.
        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf buffer, List<Object> output) {
            NetworkManager.this.handleByteBuf(this.user, buffer, false);
            if (buffer.isReadable()) {
                output.add(buffer.retain());
                return;
            }
            throw CancelPacketException.INSTANCE;
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

    // 路由表里的一项: 监听器连同它的注册名, 出错时报名字比报包 ID 好查.
    private record ByteBufPacketListenerHolder(String name, ByteBufPacketListener listener) {
    }

    // 出站帧被取消时用它从编码链里跳出来, 由 ByteBufEncoder 自己认领, 不会漏到别的 handler 上.
    private static final class CancelPacketException extends RuntimeException {
        private static final CancelPacketException INSTANCE = new CancelPacketException();

        private CancelPacketException() {
            super(null, null, false, false);
        }
    }
}
