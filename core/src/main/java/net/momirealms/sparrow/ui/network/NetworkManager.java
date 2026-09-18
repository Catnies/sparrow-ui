package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.network.RegistrySnapshot.ByteBufRoute;
import net.momirealms.sparrow.ui.network.RegistrySnapshot.NmsRoute;
import net.momirealms.sparrow.ui.network.RegistrySnapshot.NmsTypeRoutes;
import net.momirealms.sparrow.ui.network.listener.configuration.FinishConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.game.ConfigurationAcknowledgedListener;
import net.momirealms.sparrow.ui.network.listener.game.LoginListener;
import net.momirealms.sparrow.ui.network.listener.game.StartConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.handshake.IntentionListener;
import net.momirealms.sparrow.ui.network.listener.login.LoginAcknowledgedListener;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.BundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundBundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerCommonPacketListenerImplProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerConnectionListenerProxy;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.util.VersionHelper;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 按注册顺序监听连接中的 ByteBuf 帧与 NMS 包对象, 支持改写和取消.
 * <p>全服共用 {@link SparrowUI#networkManager()} 返回的实例; 回调在连接的 Netty 线程同步执行.
 */
public final class NetworkManager implements Listener, AutoCloseable {
    private static final String MINECRAFT_SPLITTER = "splitter";

    private final PacketIdRegistry packetIds; // 运行期包 ID 索引, 注册监听器时拿它把包名换成路由下标

    // 这五个名字都会出现在 Minecraft 的 ChannelPipeline 上, 一律带插件前缀, 出问题时一眼能认出是谁装的
    final String connectionHandlerName;      // acceptor 上拦新子连接的 handler
    final String preInitializerName;         // 子连接注册前临时顶上的 initializer, 注入完就摘掉
    final String packetBridgeName;           // 贴着 NMS Connection, 两个方向都能看到解码后的包对象
    final String decoderName;                // 客户端 -> 服务端, 在原始 ByteBuf 帧上派发
    final String encoderName;                // 服务端 -> 客户端, 在原始 ByteBuf 帧上派发

    private volatile RegistrySnapshot registry; // 两层、双方向一起发布, 每次派发只捕获一份根快照
    private long sequence;                     // 持有 manager 锁时分配, 标识一次注册供凭证注销和异常定位
    private final Map<ChannelPipeline, NetworkUser> users = new ConcurrentHashMap<>();  // 装过 handler 的连接, 派发时由它把 channel 换成 NetworkUser
    private final Map<UUID, NetworkUser> onlineUsers = new ConcurrentHashMap<>();      // 玩家进世界后额外挂一份, 按玩家查连接就不用走反射
    private final Set<Channel> serverChannels = ConcurrentHashMap.newKeySet();         // acceptor channel, 关管理器时要把 handler 从它们上面摘掉
    final AtomicBoolean closed = new AtomicBoolean();                                  // 只让 close() 跑一次; pipeline 重定位每次都先看它
    @Nullable private Subscription acceptorHook;                                       // acceptor 名单的元素钩子凭证, 关掉它 NMS 手里的包装器就退回普通 List

    @ApiStatus.Internal
    public NetworkManager() {
        this.packetIds = new PacketIdRegistry();
        this.registry = RegistrySnapshot.empty();

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

    // 内置状态监听器在接管连接前注册, 后续监听器按同一条链的注册顺序追加.
    private void registerProtocolStateListeners() {
        this.listenByteBuf(PacketTypes.Handshaking.Serverbound.INTENTION, IntentionListener.INSTANCE);
        this.listenByteBuf(PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED, LoginAcknowledgedListener.INSTANCE);
        this.listenByteBuf(PacketTypes.Configuration.Serverbound.FINISH_CONFIGURATION, FinishConfigurationListener.INSTANCE);
        this.listenByteBuf(PacketTypes.Play.Clientbound.LOGIN, LoginListener.INSTANCE);
        this.listenByteBuf(PacketTypes.Play.Clientbound.START_CONFIGURATION, StartConfigurationListener.INSTANCE);
        this.listenByteBuf(PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED, ConfigurationAcknowledgedListener.INSTANCE);
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
     * 按注册顺序监听指定包, 回调在 Netty 线程同步执行.
     * @param type 逻辑包类型
     * @param handler 回调, 同一实例可能由多个连接并发调用
     * @return 独立注销凭证, 关闭后已捕获旧快照的派发仍可完成
     * @throws IllegalArgumentException 当前版本没有该包
     * @throws IllegalStateException 管理器已关闭
     */
    @NotNull
    public synchronized Subscription listenByteBuf(@NotNull PacketType type, @NotNull ByteBufPacketHandler handler) {
        this.requireOpen();
        // 版本相关的名称解析在注册时完成, 后续帧直接按数字 ID 定位.
        int id = this.packetIds.id(type);
        if (id < 0) {
            throw new IllegalArgumentException("Unavailable packet " + type + " on Minecraft " + VersionHelper.MINECRAFT_VERSION);
        }
        ByteBufRoute[] row = this.registry.byteBuf()[type.flow().ordinal()][type.state().ordinal()];
        ByteBufRoute route = row == null ? null : row[id];
        // 注册锁确定并发注册的先后. 所有新条目追加到末尾.
        long sequence = this.sequence++;
        PacketEntry<ByteBufPacketHandler>[] entries = PacketEntry.append(route == null ? RegistrySnapshot.EMPTY_BYTE_BUF : route.entries(), new PacketEntry<>(sequence, handler));
        // 新数组全部准备完成后再发布, 正在派发的帧继续读取手中的旧数组.
        this.registry = this.registry.withByteBuf(type, id, this.packetIds.count(type.state(), type.flow()), new ByteBufRoute(type, entries));
        return new PacketSubscription(type, id, sequence);
    }

    private void unregisterByteBuf(PacketType type, int id, long sequence) {
        // 由凭证在 manager 锁内调用. 最后一个条目移除后释放该路由槽位.
        ByteBufRoute route = this.registry.byteBuf()[type.flow().ordinal()][type.state().ordinal()][id];
        PacketEntry<ByteBufPacketHandler>[] entries = PacketEntry.remove(route.entries(), sequence);
        ByteBufRoute updated = entries.length == 0 ? null : new ByteBufRoute(type, entries);
        this.registry = this.registry.withByteBuf(type, id, this.packetIds.count(type.state(), type.flow()), updated);
    }

    /**
     * 按注册顺序监听逻辑类型对应的 NMS 包, 回调在 Netty 线程同步执行.
     * @param type 逻辑包类型
     * @param handler 对象回调, 第三个参数为当前匹配的叶子包
     * @return 独立注销凭证, 在途快照可继续执行
     * @throws IllegalArgumentException 当前版本没有该包
     * @throws IllegalStateException 管理器已关闭
     */
    @NotNull
    public synchronized Subscription listenNMS(@NotNull PacketType type, @NotNull NMSPacketHandler handler) {
        this.requireOpen();
        // 原生类型直接取自协议 visitor, 对象包派发时用 Packet.type() 返回的同一实例查询.
        Object nativeType = this.packetIds.nativeType(type);
        if (nativeType == null) {
            throw new IllegalArgumentException("Unavailable packet " + type + " on Minecraft " + VersionHelper.MINECRAFT_VERSION);
        }
        NmsTypeRoutes routes = this.registry.nms()[type.flow().ordinal()].get(nativeType);
        NmsRoute route = routes == null ? null : routes.routes()[type.state().ordinal()];
        long sequence = this.sequence++;
        PacketEntry<NMSPacketHandler>[] entries = PacketEntry.append(route == null ? RegistrySnapshot.EMPTY_NMS : route.entries(), new PacketEntry<>(sequence, handler));
        this.registry = this.registry.withNms(type, nativeType, this.packetIds.stateMask(nativeType), new NmsRoute(type, entries));
        return new PacketSubscription(type, -1, sequence);
    }

    private void unregisterNms(PacketType type, long sequence) {
        // 身份表、阶段数组和条目数组都通过新快照替换, bundle 已捕获的快照可执行到结束.
        Object nativeType = this.packetIds.nativeType(type);
        NmsRoute route = this.registry.nms()[type.flow().ordinal()].get(nativeType).routes()[type.state().ordinal()];
        PacketEntry<NMSPacketHandler>[] entries = PacketEntry.remove(route.entries(), sequence);
        this.registry = this.registry.withNms(type, nativeType, this.packetIds.stateMask(nativeType), entries.length == 0 ? null : new NmsRoute(type, entries));
    }

    // 每帧捕获一次快照, 命中后创建事件并按注册顺序执行整条链.
    private boolean handleByteBuf(NetworkUser user, ByteBuf buffer, boolean serverbound) throws Exception {
        if (!buffer.isReadable()) return false;
        if (user.bypassing()) return true;
        RegistrySnapshot snapshot = this.registry;
        ConnectionState state = serverbound ? user.decoderState() : user.encoderState();
        PacketFlow flow = serverbound ? PacketFlow.SERVERBOUND : PacketFlow.CLIENTBOUND;
        ByteBufRoute[] routes = snapshot.byteBuf()[flow.ordinal()][state.ordinal()];
        // 当前阶段完全没有工作时, 原帧连 ID 都不读取.
        if (routes == null) return true;
        int frameStart = buffer.readerIndex();
        int frameEnd = buffer.writerIndex();
        int packetId = PacketBuf.readVarInt(buffer);
        ByteBufRoute route = packetId < 0 || packetId >= routes.length ? null : routes[packetId];
        if (route == null) {
            // 试读 ID 已推进 readerIndex, 未命中时恢复帧起点再交给原版解码器.
            buffer.readerIndex(frameStart);
            return true;
        }
        int payloadStart = buffer.readerIndex();
        ByteBufPacketEvent event = new ByteBufPacketEvent(packetId, state, flow, buffer, payloadStart);
        PacketEntry<ByteBufPacketHandler>[] entries = route.entries();
        for (int index = 0; index < entries.length; index++) {
            PacketEntry<ByteBufPacketHandler> entry = entries[index];
            // 每个节点从已提交 payload 的开头读取, 写操作标记只记录当前节点.
            buffer.setIndex(payloadStart, frameEnd);
            event.begin();
            try {
                entry.handler().handle(user, event);
            } catch (Exception failure) {
                SparrowUI.getInstance().handleException("Failed to handle " + route.type() + " id=" + packetId + " handler=" + entry.handler().getClass().getName() + " sequence=" + entry.sequence(), failure);
                if (event.writing() || event.cancelled()) throw failure;
                // 当前节点只读失败时, 保留前面节点的已提交内容, 由下一轮或出口恢复指针.
                continue;
            }
            if (event.cancelled()) {
                return false;
            }
            if (event.writing()) {
                // 写入成功才提交新长度, 后面的只读节点即使读到末尾也不会缩短该帧.
                frameEnd = event.frameEnd();
            }
        }
        // 最终输出包含原包 ID 前缀和最后提交的 payload, 与最后一个节点读到了哪里无关.
        buffer.setIndex(frameStart, frameEnd);
        return true;
    }

    // 根包捕获的阶段和身份索引沿 bundle 递归传递, 事件在首个命中叶子时创建.
    @Nullable
    private NMSPacketEvent handleNms(NetworkUser user, Object root, Object packet, @Nullable NMSPacketEvent event, IdentityHashMap<Object, NmsTypeRoutes> routes, int state, boolean outbound) {
        if (outbound && ClientboundBundlePacketProxy.CLASS.isInstance(packet)) {
            // 所有叶子共用根事件与入口阶段. 任意叶子停止后, 其余叶子也不再展开.
            for (Object child : BundlePacketProxy.INSTANCE.subPackets(packet)) {
                event = this.handleNms(user, root, child, event, routes, state, true);
                if (event != null && event.stopped()) return event;
            }
            return event;
        }
        // 每个普通包只查询一次原生类型. 多阶段共用类型才需要入口阶段参与选择.
        NmsTypeRoutes typeRoutes = routes.get(PacketProxy.INSTANCE.type(packet));
        if (typeRoutes == null) return event;
        NmsRoute route = typeRoutes.routes()[typeRoutes.fixedState() < 0 ? state : typeRoutes.fixedState()];
        if (route == null) return event;
        if (event == null) {
            // bundle 在首个命中叶子时才创建事件, 嵌套发送会创建自己的独立事件.
            event = new NMSPacketEvent(root);
        }
        PacketEntry<NMSPacketHandler>[] entries = route.entries();
        for (int index = 0; index < entries.length; index++) {
            PacketEntry<NMSPacketHandler> entry = entries[index];
            try {
                entry.handler().handle(user, event, packet);
            } catch (Exception failure) {
                event.failure(failure);
                // NMS 回调可能已经原地修改对象, 失败时整根丢弃, 由桥完成资源与 promise 处理.
                SparrowUI.getInstance().handleException("Failed to handle NMS " + route.type() + " handler=" + entry.handler().getClass().getName() + " sequence=" + entry.sequence(), failure);
            } catch (Error failure) {
                event.failure(failure);
            }
            if (event.stopped()) {
                return event;
            }
        }
        return event;
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
     * <p>发送期间跳过所有监听器, 包括内置状态监听器.
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
     * <p>发送期间跳过所有监听器, 包括内置状态监听器.
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
        synchronized (this) {
            // 与注册使用同一把锁, 标记关闭并清空所有监听器后, 后续注册无法重新发布监听器.
            if (!this.closed.compareAndSet(false, true)) return;
            this.registry = RegistrySnapshot.empty();
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
    final class ByteBufDecoder extends ChannelInboundHandlerAdapter {
        private final NetworkUser user;

        ByteBufDecoder(NetworkUser user) {
            this.user = user;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf buffer)) {
                context.fireChannelRead(message);
                return;
            }
            boolean forward;
            try {
                forward = NetworkManager.this.handleByteBuf(this.user, buffer, true);
            } catch (Exception | Error failure) {
                // 此时尚未交给下游, 当前 adapter 负责释放失败帧, 再传播真实异常.
                buffer.release();
                context.fireExceptionCaught(failure);
                return;
            }
            if (forward) {
                context.fireChannelRead(buffer);
            } else {
                // 空帧和主动取消均由当前层消费, 放行分支把原有引用直接交给下游.
                buffer.release();
            }
        }
    }

    private final class NMSPacketBridge extends ChannelDuplexHandler {
        private final NetworkUser user;

        private NMSPacketBridge(NetworkUser user) {
            this.user = user;
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object packet) {
            RegistrySnapshot snapshot = NetworkManager.this.registry;
            IdentityHashMap<Object, NmsTypeRoutes> routes = snapshot.nms()[PacketFlow.SERVERBOUND.ordinal()];
            if (routes.isEmpty() || this.user.bypassing() || !PacketProxy.CLASS.isInstance(packet)) {
                // 方向表为空时无需读取 type(), 非 NMS 消息也沿原 pipeline 透传.
                context.fireChannelRead(packet);
                return;
            }
            NMSPacketEvent event;
            try {
                event = NetworkManager.this.handleNms(this.user, packet, packet, null, routes, this.user.decoderState().ordinal(), false);
            } catch (Exception | Error failure) {
                ReferenceCountUtil.release(packet);
                context.fireExceptionCaught(failure);
                return;
            }
            if (event != null && (event.cancelled() || event.failure() != null)) {
                // 根和替换各自最多消费一次, 事件负责避开 replacement 与根为同一实例的情况.
                event.discardReplacement();
                ReferenceCountUtil.release(packet);
                if (event.failure() != null) {
                    context.fireExceptionCaught(event.failure());
                }
                return;
            }
            Object result = event != null && event.replacement() != null ? event.replacement() : packet;
            if (result != packet) {
                // 接受替换后旧根由桥释放, 新根的所有权随后交给下游.
                ReferenceCountUtil.release(packet);
            }
            context.fireChannelRead(result);
        }

        @Override
        public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) {
            RegistrySnapshot snapshot = NetworkManager.this.registry;
            IdentityHashMap<Object, NmsTypeRoutes> routes = snapshot.nms()[PacketFlow.CLIENTBOUND.ordinal()];
            if (routes.isEmpty() || this.user.bypassing() || !PacketProxy.CLASS.isInstance(packet)) {
                // 出站方向为空或本次发送 bypass 时, bundle 也按原对象直接转交.
                context.write(packet, promise);
                return;
            }
            NMSPacketEvent event;
            try {
                event = NetworkManager.this.handleNms(this.user, packet, packet, null, routes, this.user.encoderState().ordinal(), true);
            } catch (Exception | Error failure) {
                ReferenceCountUtil.release(packet);
                promise.tryFailure(failure);
                return;
            }
            if (event != null && (event.cancelled() || event.failure() != null)) {
                // 失败优先于取消; 主动取消完成成功 promise, 回调错误完成失败 promise.
                event.discardReplacement();
                ReferenceCountUtil.release(packet);
                if (event.failure() != null) {
                    promise.tryFailure(event.failure());
                } else {
                    promise.trySuccess();
                }
                return;
            }
            Object result = event != null && event.replacement() != null ? event.replacement() : packet;
            if (result != packet) {
                ReferenceCountUtil.release(packet);
            }
            context.write(result, promise);
        }
    }

    @ChannelHandler.Sharable
    final class ByteBufEncoder extends ChannelOutboundHandlerAdapter {
        private final NetworkUser user;

        ByteBufEncoder(NetworkUser user) {
            this.user = user;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (!(message instanceof ByteBuf buffer)) {
                context.write(message, promise);
                return;
            }
            boolean forward;
            try {
                forward = NetworkManager.this.handleByteBuf(this.user, buffer, false);
            } catch (Exception | Error failure) {
                // 解析或回调失败发生在移交前, 释放原帧并用原始异常完成调用方的 promise.
                buffer.release();
                promise.tryFailure(failure);
                return;
            }
            if (forward) {
                // 调用下游后所有权已交出, 下游失败仍由下游处理.
                context.write(buffer, promise);
            } else {
                // 主动取消和空帧都属于正常消费, 出站调用方得到成功结果.
                buffer.release();
                promise.trySuccess();
            }
        }
    }

    private final class PacketSubscription implements Subscription {
        private final PacketType type;
        private final int id; // 非负为 ByteBuf 路由 ID, -1 为 NMS 路由
        private final long sequence;
        private volatile boolean closed;

        private PacketSubscription(PacketType type, int id, long sequence) {
            this.type = type;
            this.id = id;
            this.sequence = sequence;
        }

        @Override
        public boolean isClosed() {
            return this.closed || NetworkManager.this.closed.get();
        }

        @Override
        public void close() {
            synchronized (NetworkManager.this) {
                // 同一凭证只删除一次. manager 关闭时已整体清掉监听链, 凭证也随之视为关闭.
                if (this.isClosed()) return;
                if (this.id < 0) {
                    NetworkManager.this.unregisterNms(this.type, this.sequence);
                } else {
                    NetworkManager.this.unregisterByteBuf(this.type, this.id, this.sequence);
                }
                this.closed = true;
            }
        }
    }
}
