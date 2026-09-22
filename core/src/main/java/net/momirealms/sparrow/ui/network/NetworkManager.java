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
import net.momirealms.sparrow.ui.network.listener.configuration.FinishConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.game.ConfigurationAcknowledgedListener;
import net.momirealms.sparrow.ui.network.listener.game.LoginListener;
import net.momirealms.sparrow.ui.network.listener.game.StartConfigurationListener;
import net.momirealms.sparrow.ui.network.listener.handshake.IntentionListener;
import net.momirealms.sparrow.ui.network.listener.login.LoginAcknowledgedListener;
import net.momirealms.sparrow.ui.network.packet.*;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ConnectionProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.ProtocolSwapHandlerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.BundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundBundlePacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.MinecraftServerProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.server.network.ServerConnectionListenerProxy;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.util.VersionHelper;
import net.momirealms.sparrow.ui.util.PlayerUtils;
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

import java.nio.channels.ClosedChannelException;
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
        // 入站在对象层推进状态, 字节层和对象层都能先按切换前的阶段选定各自的监听链.
        this.listenNMS(new PacketType("minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND), IntentionListener.INSTANCE);
        this.listenNMS(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND), LoginAcknowledgedListener.INSTANCE);
        this.listenNMS(new PacketType("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND), FinishConfigurationListener.INSTANCE);
        this.listenNMS(new PacketType("minecraft:configuration_acknowledged", ConnectionState.PLAY, PacketFlow.SERVERBOUND), ConfigurationAcknowledgedListener.INSTANCE);
        // 出站先经过对象层再编码成字节, 状态在字节层推进.
        this.listenByteBuf(new PacketType("minecraft:login", ConnectionState.PLAY, PacketFlow.CLIENTBOUND), LoginListener.INSTANCE);
        this.listenByteBuf(new PacketType("minecraft:start_configuration", ConnectionState.PLAY, PacketFlow.CLIENTBOUND), StartConfigurationListener.INSTANCE);
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
        return PlayerUtils.getChannel(player);
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
        PacketEntry<ByteBufPacketHandler>[][] row = this.registry.byteBuf()[type.flow().ordinal()][type.state().ordinal()];
        PacketEntry<ByteBufPacketHandler>[] previous = row == null ? null : row[id];
        // 注册锁确定并发注册的先后. 所有新条目追加到末尾.
        long sequence = this.sequence++;
        PacketEntry<ByteBufPacketHandler>[] entries = PacketEntry.append(previous == null ? RegistrySnapshot.EMPTY_BYTE_BUF : previous, new PacketEntry<>(sequence, handler));
        // 新数组全部准备完成后再发布, 正在派发的帧继续读取手中的旧数组.
        this.registry = this.registry.withByteBuf(type, id, this.packetIds.count(type.state(), type.flow()), entries);
        return new PacketSubscription(type, id, sequence);
    }

    private void unregisterByteBuf(PacketType type, int id, long sequence) {
        // 由凭证在 manager 锁内调用. 最后一个条目移除后释放该路由槽位.
        PacketEntry<ByteBufPacketHandler>[] previous = this.registry.byteBuf()[type.flow().ordinal()][type.state().ordinal()][id];
        PacketEntry<ByteBufPacketHandler>[] entries = PacketEntry.remove(previous, sequence);
        this.registry = this.registry.withByteBuf(type, id, this.packetIds.count(type.state(), type.flow()), entries.length == 0 ? null : entries);
    }

    /**
     * 按注册顺序监听逻辑类型对应的 NMS 包, 回调在 Netty 线程同步执行.
     * <p>按 NMS 入口的连接阶段选定监听链; 当前链执行中的状态切换只影响后续派发, bundle 共用根包阶段.
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
        PacketEntry<NMSPacketHandler>[] previous = this.registry.nms()[type.flow().ordinal()][type.state().ordinal()].get(nativeType);
        long sequence = this.sequence++;
        PacketEntry<NMSPacketHandler>[] entries = PacketEntry.append(previous == null ? RegistrySnapshot.EMPTY_NMS : previous, new PacketEntry<>(sequence, handler));
        this.registry = this.registry.withNms(type, nativeType, entries);
        return new PacketSubscription(type, -1, sequence);
    }

    private void unregisterNms(PacketType type, long sequence) {
        // 身份表、阶段数组和条目数组都通过新快照替换, bundle 已捕获的快照可执行到结束.
        Object nativeType = this.packetIds.nativeType(type);
        PacketEntry<NMSPacketHandler>[] previous = this.registry.nms()[type.flow().ordinal()][type.state().ordinal()].get(nativeType);
        PacketEntry<NMSPacketHandler>[] entries = PacketEntry.remove(previous, sequence);
        this.registry = this.registry.withNms(type, nativeType, entries.length == 0 ? null : entries);
    }

    // 每帧捕获一次快照, 命中后创建事件并按注册顺序执行整条链.
    private boolean handleByteBuf(NetworkUser user, ByteBuf buffer, boolean serverbound) throws Exception {
        if (!buffer.isReadable()) return false;
        // 静默帧在读取包 ID 和创建事件之前放行.
        if (serverbound ? user.silentInbound() : user.silentOutbound()) return true;
        RegistrySnapshot snapshot = this.registry;
        ConnectionState state = serverbound ? user.decoderState() : user.encoderState();
        PacketFlow flow = serverbound ? PacketFlow.SERVERBOUND : PacketFlow.CLIENTBOUND;
        PacketEntry<ByteBufPacketHandler>[][] routes = snapshot.byteBuf()[flow.ordinal()][state.ordinal()];
        // 当前阶段完全没有工作时, 原帧连 ID 都不读取.
        if (routes == null) return true;
        int frameStart = buffer.readerIndex();
        int frameEnd = buffer.writerIndex();
        int packetId = PacketBuf.readVarInt(buffer);
        PacketEntry<ByteBufPacketHandler>[] entries = packetId < 0 || packetId >= routes.length ? null : routes[packetId];
        if (entries == null) {
            // 试读 ID 已推进 readerIndex, 未命中时恢复帧起点再交给原版解码器.
            buffer.readerIndex(frameStart);
            return true;
        }
        int payloadStart = buffer.readerIndex();
        ByteBufPacketEvent event = new ByteBufPacketEvent(packetId, state, flow, buffer, payloadStart);
        for (int index = 0; index < entries.length; index++) {
            PacketEntry<ByteBufPacketHandler> entry = entries[index];
            // 每个节点从已提交 payload 的开头读取, 写操作标记只记录当前节点.
            buffer.setIndex(payloadStart, frameEnd);
            event.begin();
            try {
                entry.handler().handle(user, event);
            } catch (Exception failure) {
                SparrowUI.getInstance().handleException("Failed to handle " + flow + " " + state + " id=" + packetId + " handler=" + entry.handler().getClass().getName() + " sequence=" + entry.sequence(), failure);
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
    private NMSPacketEvent handleNms(NetworkUser user, Object root, Object packet, @Nullable NMSPacketEvent event, IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]> routes, ConnectionState state, boolean outbound) {
        if (outbound && ClientboundBundlePacketProxy.CLASS.isInstance(packet)) {
            // 所有叶子共用根事件与入口阶段. 任意叶子停止后, 其余叶子也不再展开.
            for (Object child : BundlePacketProxy.INSTANCE.subPackets(packet)) {
                event = this.handleNms(user, root, child, event, routes, state, true);
                if (event != null && event.stopped()) return event;
            }
            return event;
        }
        // 阶段表已在根包入口选定, 普通包只需按原生类型身份查询一次监听数组.
        Object nativeType = PacketProxy.INSTANCE.type(packet);
        PacketEntry<NMSPacketHandler>[] entries = routes.get(nativeType);
        if (entries == null) return event;
        if (event == null) {
            // bundle 在首个命中叶子时才创建事件, 嵌套发送会创建自己的独立事件.
            event = new NMSPacketEvent(root);
        }
        for (int index = 0; index < entries.length; index++) {
            PacketEntry<NMSPacketHandler> entry = entries[index];
            try {
                entry.handler().handle(user, event, packet);
            } catch (Exception failure) {
                event.failure(failure);
                // NMS 回调可能已经原地修改对象, 失败时整根丢弃, 由桥完成资源与 promise 处理.
                SparrowUI.getInstance().handleException("Failed to handle NMS " + (outbound ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND) + " " + state + " type=" + nativeType + " handler=" + entry.handler().getClass().getName() + " sequence=" + entry.sequence(), failure);
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

    // 发送与模拟接收

    void sendByteBuf(NetworkUser user, ByteBuf frame, boolean silent) {
        this.requireOpen();
        this.transfer(user, frame, true, true, silent);
    }

    void sendPacket(NetworkUser user, Object packet, boolean silent) {
        this.requireOpen();
        if (!PacketProxy.CLASS.isInstance(packet)) {
            throw new IllegalArgumentException("Expected an NMS packet");
        }
        this.transfer(user, packet, true, false, silent);
    }

    void receiveByteBuf(NetworkUser user, ByteBuf frame, boolean silent) {
        this.requireOpen();
        this.transfer(user, frame, false, true, silent);
    }

    void receivePacket(NetworkUser user, Object packet, boolean silent) {
        this.requireOpen();
        if (!PacketProxy.CLASS.isInstance(packet)) {
            throw new IllegalArgumentException("Expected an NMS packet");
        }
        this.transfer(user, packet, false, false, silent);
    }

    void writePacket(NetworkUser user, PacketType type, Consumer<PacketBuf> writer, boolean outbound, boolean silent) {
        this.requireOpen();
        PacketFlow flow = outbound ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND;
        if (type.flow() != flow) {
            throw new IllegalArgumentException("Expected " + flow + " packet, got " + type);
        }
        int id = this.packetIds.id(type);
        if (id < 0) {
            throw new IllegalArgumentException("Unavailable packet " + type + " on Minecraft " + VersionHelper.MINECRAFT_VERSION);
        }
        ByteBuf frame = user.channel().alloc().buffer();
        try {
            PacketBuf payload = new PacketBuf(frame);
            payload.writeVarInt(id);
            writer.accept(payload);
        } catch (RuntimeException | Error failure) {
            frame.release();
            throw failure;
        }
        this.transfer(user, frame, outbound, true, silent);
    }

    // 消息在入队成功后由框架持有, 跨线程调用交给连接的 event loop 执行.
    private void transfer(NetworkUser user, Object message, boolean outbound, boolean bytes, boolean silent) {
        Channel channel = user.channel();
        if (channel.eventLoop().inEventLoop()) {
            this.transferOnEventLoop(user, message, outbound, bytes, silent);
        } else {
            try {
                channel.eventLoop().execute(() -> this.transferOnEventLoop(user, message, outbound, bytes, silent));
            } catch (RuntimeException failure) {
                ReferenceCountUtil.release(message);
                throw failure;
            }
        }
    }

    private void transferOnEventLoop(NetworkUser user, Object message, boolean outbound, boolean bytes, boolean silent) {
        // 空帧在注入前消费, 不交给原版 codec.
        if (bytes && !((ByteBuf) message).isReadable()) {
            ReferenceCountUtil.release(message);
            return;
        }
        Channel channel = user.channel();
        ChannelPipeline pipeline = channel.pipeline();
        ChannelHandlerContext context;
        try {
            // 排队期间或 writer 回调中可能关闭管理器, 实际移交消息时再确认状态.
            this.requireOpen();
            if (!channel.isOpen()) {
                throw new ClosedChannelException();
            }
            // 出站以字节监听节点为定位点; 入站按原始字节或已解码 NMS 包选择入口.
            String name;
            if (outbound) {
                name = this.encoderName;
            } else if (bytes) {
                name = this.decoderName;
            } else {
                name = this.packetBridgeName;
            }
            context = pipeline.context(name);
            if (context == null) {
                throw new IllegalStateException("Sparrow handler is not installed: " + name);
            }
            if (!outbound && !bytes && PacketProxy.INSTANCE.isTerminal(message)) {
                ChannelHandlerContext decoder = pipeline.context("decoder");
                if (decoder != null) {
                    // 原版在终止包到达对象监听前停读并将 decoder 换成 inbound_config, 后续配置任务依赖这个节点.
                    ProtocolSwapHandlerProxy.INSTANCE.handleInboundTerminalPacket(decoder, message);
                } else if (pipeline.context("inbound_config") == null) {
                    throw new IllegalStateException("Minecraft inbound protocol handler is not installed");
                }
                // 已解码的终止包可能经取消后再次注入, 此时沿用现有 inbound_config 等待原版安装新协议.
            }
        } catch (Exception | Error failure) {
            // 状态检查和协议准备都在消息移交前完成, 失败时由此处释放消息并报告.
            ReferenceCountUtil.release(message);
            pipeline.fireExceptionCaught(failure);
            return;
        }

        if (outbound) {
            // 普通调用也设置本次模式, 嵌套 API 调用结束后恢复外层状态.
            boolean previous = user.silentOutbound();
            user.silentOutbound(silent);
            try {
                // 从 pipeline 尾部进入, 第三方处理器继续按原顺序处理消息.
                channel.writeAndFlush(message);
            } finally {
                user.silentOutbound(previous);
            }
            return;
        }

        // 入站单独保存模式, 同步产生的出站响应使用自身的出站模式.
        boolean previous = user.silentInbound();
        user.silentInbound(silent);
        try {
            if (bytes) {
                // 从 Sparrow 字节监听层注入包 ID + payload, 后面继续走原版解码.
                ((ByteBufDecoder) context.handler()).channelRead(context, message);
            } else {
                // 从 NMS 监听层注入对象包, 按实际执行时的连接阶段派发.
                ((NMSPacketBridge) context.handler()).channelRead(context, message);
            }
            // 为本次模拟读取补发完成事件, 让后续 handler 完成这一轮读取的收尾工作.
            context.fireChannelReadComplete();
        } finally {
            user.silentInbound(previous);
        }
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
            ConnectionState state = this.user.decoderState();
            // 先选定本次监听链, 内置监听器随后推进状态; 同一包的剩余回调继续使用已选定的表.
            IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]> routes = snapshot.nms()[PacketFlow.SERVERBOUND.ordinal()][state.ordinal()];
            if (this.user.silentInbound() || routes.isEmpty() || !PacketProxy.CLASS.isInstance(packet)) {
                // 静默、空路由和非 NMS 消息直接沿原 pipeline 透传.
                context.fireChannelRead(packet);
                return;
            }
            NMSPacketEvent event;
            try {
                event = NetworkManager.this.handleNms(this.user, packet, packet, null, routes, state, false);
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
            ConnectionState state = this.user.encoderState();
            IdentityHashMap<Object, PacketEntry<NMSPacketHandler>[]> routes = snapshot.nms()[PacketFlow.CLIENTBOUND.ordinal()][state.ordinal()];
            if (this.user.silentOutbound() || routes.isEmpty() || !PacketProxy.CLASS.isInstance(packet)) {
                // 静默或当前出站阶段为空时, bundle 也按原对象直接转交.
                context.write(packet, promise);
                return;
            }
            NMSPacketEvent event;
            try {
                event = NetworkManager.this.handleNms(this.user, packet, packet, null, routes, state, true);
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
