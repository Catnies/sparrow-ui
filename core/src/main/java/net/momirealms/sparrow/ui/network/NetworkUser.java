package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

public final class NetworkUser {
    private final NetworkManager networkManager;
    private final Channel channel;
    // 两个方向各记一份协议阶段: 阶段切换包一发出去出站那侧就先动, 对方的确认包回来之前两边是对不上的
    private volatile ConnectionState decoderState = ConnectionState.HANDSHAKING;
    private volatile ConnectionState encoderState = ConnectionState.HANDSHAKING;
    private volatile @Nullable Player player;   // 连接建起来的时候还没有玩家对象, 要等进世界才绑得上
    // 仅在连接 event loop 的同步转发期间读写, 两个方向分别保存当前操作的模式.
    private boolean silentOutbound;
    private boolean silentInbound;

    NetworkUser(@NotNull NetworkManager networkManager, @NotNull Channel channel) {
        this.networkManager = networkManager;
        this.channel = channel;
    }

    @NotNull
    public NetworkManager networkManager() {
        return this.networkManager;
    }

    @NotNull
    public Channel channel() {
        return this.channel;
    }

    @NotNull
    public ConnectionState decoderState() {
        return this.decoderState;
    }

    @NotNull
    public ConnectionState encoderState() {
        return this.encoderState;
    }

    @Nullable
    public Player player() {
        return this.player;
    }

    @Nullable
    public UUID uuid() {
        Player player = this.player;
        return player == null ? null : player.getUniqueId();
    }

    @Nullable
    public String name() {
        Player player = this.player;
        return player == null ? null : player.getName();
    }

    void player(@Nullable Player player) {
        this.player = player;
    }

    boolean silentOutbound() {
        return this.silentOutbound;
    }

    void silentOutbound(boolean silent) {
        this.silentOutbound = silent;
    }

    boolean silentInbound() {
        return this.silentInbound;
    }

    void silentInbound(boolean silent) {
        this.silentInbound = silent;
    }

    // 握手和登录这些阶段切换的包会把两个方向一起换掉
    @ApiStatus.Internal
    public void setConnectionState(@NotNull ConnectionState state) {
        this.decoderState = state;
        this.encoderState = state;
    }

    @ApiStatus.Internal
    public void decoderState(@NotNull ConnectionState state) {
        this.decoderState = state;
    }

    @ApiStatus.Internal
    public void encoderState(@NotNull ConnectionState state) {
        this.encoderState = state;
    }

    /**
     * 向客户端发送 NMS 包, 经过对应的 Sparrow 监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示对端或服务器已处理完成.
     * @param packet NMS 客户端包
     */
    public void sendPacket(@NotNull Object packet) {
        this.networkManager.sendPacket(this, packet, false);
    }

    /**
     * 向客户端发送字节帧, 经过对应的 Sparrow 监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示对端或服务器已处理完成.
     * @param frame 当前服务端协议的包 ID + payload, 不带长度、压缩或加密头
     */
    public void sendByteBuf(@NotNull ByteBuf frame) {
        this.networkManager.sendByteBuf(this, frame, false);
    }

    /**
     * 向客户端发送指定类型的字节帧, 自动写入当前版本的包 ID, 经过对应的 Sparrow 监听器.
     * <p>writer 在调用线程同步执行, 只向缓冲末尾追加 payload; 缓冲不能保存到回调之外.
     * @param type 当前方向的逻辑包类型
     * @param writer payload 写入回调
     */
    public void sendPacket(@NotNull PacketType type, @NotNull Consumer<PacketBuf> writer) {
        this.networkManager.writePacket(this, type, writer, true, false);
    }

    /**
     * 向客户端发送 NMS 包, 在同步转发期间跳过 Sparrow 出站监听器, 包括内置状态监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示对端已处理完成.
     * <p><strong>静默范围仅覆盖同一 event loop 上的同步传播.</strong> 中间 handler 延迟转发或切换执行器后不保留静默模式.
     * 同步重入的原始 channel.write 会沿用当前出站模式; 需要普通发送时使用本类普通入口, 或由调用方排入 event loop 后续任务.
     * @param packet NMS 客户端包, bundle 由调用者自行构造
     */
    public void sendPacketSilently(@NotNull Object packet) {
        this.networkManager.sendPacket(this, packet, true);
    }

    /**
     * 向客户端发送字节帧, 静默范围与 {@link #sendPacketSilently(Object)} 相同.
     * @param frame 当前服务端协议的包 ID + payload, 不带长度、压缩或加密头; 接受后所有权交给框架
     */
    public void sendByteBufSilently(@NotNull ByteBuf frame) {
        this.networkManager.sendByteBuf(this, frame, true);
    }

    /**
     * 写入当前版本包 ID 和 payload 后静默发送, 静默范围与 {@link #sendPacketSilently(Object)} 相同.
     * @param type 当前方向的逻辑包类型
     * @param writer 在调用线程同步追加 payload, 缓冲不得保存到回调之外
     */
    public void sendPacketSilently(@NotNull PacketType type, @NotNull Consumer<PacketBuf> writer) {
        this.networkManager.writePacket(this, type, writer, true, true);
    }

    /**
     * 模拟客户端向服务器发送 NMS 包, 经过对应的 Sparrow 监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示对端或服务器已处理完成.
     * @param packet NMS 服务端包
     */
    public void receivePacket(@NotNull Object packet) {
        this.networkManager.receivePacket(this, packet, false);
    }

    /**
     * 模拟客户端向服务器发送字节帧, 经过对应的 Sparrow 监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示对端或服务器已处理完成.
     * @param frame 当前服务端协议的包 ID + payload, 不带长度、压缩或加密头
     */
    public void receiveByteBuf(@NotNull ByteBuf frame) {
        this.networkManager.receiveByteBuf(this, frame, false);
    }

    /**
     * 模拟客户端向服务器发送指定类型的字节帧, 自动写入当前版本的包 ID, 经过对应的 Sparrow 监听器.
     * <p>writer 在调用线程同步执行, 只向缓冲末尾追加 payload; 缓冲不能保存到回调之外.
     * @param type 当前方向的逻辑包类型
     * @param writer payload 写入回调
     */
    public void receivePacket(@NotNull PacketType type, @NotNull Consumer<PacketBuf> writer) {
        this.networkManager.writePacket(this, type, writer, false, false);
    }

    /**
     * 模拟客户端发送 NMS 包, 在同步转发期间跳过 Sparrow 入站监听器, 包括内置状态监听器.
     * <p>在连接 event loop 执行; 接受后消息所有权交给框架, 返回不表示服务器已处理完成.
     * <p><strong>静默范围仅覆盖同一 event loop 上的同步传播.</strong> 中间 handler 延迟转发或切换执行器后不保留静默模式.
     * 入站模式独立于出站模式; 通过本类普通接收入口重入时使用普通模式.
     * @param packet NMS 服务端包
     */
    public void receivePacketSilently(@NotNull Object packet) {
        this.networkManager.receivePacket(this, packet, true);
    }

    /**
     * 模拟客户端发送字节帧, 静默范围与 {@link #receivePacketSilently(Object)} 相同.
     * @param frame 当前服务端协议的包 ID + payload, 不带长度、压缩或加密头; 接受后所有权交给框架
     */
    public void receiveByteBufSilently(@NotNull ByteBuf frame) {
        this.networkManager.receiveByteBuf(this, frame, true);
    }

    /**
     * 写入当前版本包 ID 和 payload 后模拟静默收包, 静默范围与 {@link #receivePacketSilently(Object)} 相同.
     * @param type 当前方向的逻辑包类型
     * @param writer 在调用线程同步追加 payload, 缓冲不得保存到回调之外
     */
    public void receivePacketSilently(@NotNull PacketType type, @NotNull Consumer<PacketBuf> writer) {
        this.networkManager.writePacket(this, type, writer, false, true);
    }

}
