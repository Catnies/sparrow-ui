package net.momirealms.sparrow.ui.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@ApiStatus.Internal
public final class NetworkPipelineOrder {
    private static final String MINECRAFT_DECODER = "decoder";
    private static final String MINECRAFT_ENCODER = "encoder";
    private static final String MINECRAFT_INBOUND_CONFIG = "inbound_config";
    private static final String MINECRAFT_OUTBOUND_CONFIG = "outbound_config";
    private static final String MINECRAFT_DECOMPRESSOR = "decompress";
    private static final String MINECRAFT_COMPRESSOR = "compress";
    private static final String VIA_DECODER = "via-decoder";
    private static final String VIA_ENCODER = "via-encoder";
    private static final String CRAFTENGINE_DECODER = "craftengine_decoder";
    private static final String CRAFTENGINE_ENCODER = "craftengine_encoder";
    private static final String PACKET_EVENTS_DECODER_PREFIX = "pe-decoder-";
    private static final String PACKET_EVENTS_ENCODER_PREFIX = "pe-encoder-";

    private NetworkPipelineOrder() {
    }

    // 按当前第三方 codec 的实际位置安装 Sparrow ByteBuf handlers.
    // 入站要读服务端当前版本的 wire 布局, 排在 ViaVersion 的版本转换之后,
    // 出站要让取消掉的帧对第三方彻底不可见, 所以排在它们之前.
    static void addByteBufHandlers(
            NetworkManager manager,
            ChannelPipeline pipeline,
            ChannelHandler decoder,
            ChannelHandler encoder
    ) {
        addDecoder(pipeline, manager.decoderName, decoder);
        addEncoder(pipeline, manager.encoderName, encoder);
    }

    /**
     * 按当前 pipeline 重新安装连接的 ByteBuf handlers.
     *
     * @param manager handlers 所属的网络管理器
     * @param channel 要重定位的连接 channel
     */
    @ApiStatus.Internal
    public static void relocateByteBufHandlers(@NotNull NetworkManager manager, @NotNull Channel channel) {
        if (manager.closed.get() || NetworkManager.isFakeChannel(channel)) return;
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(manager.encoderName) == null) return;
        ChannelHandler encoder = pipeline.remove(manager.encoderName);
        ChannelHandler decoder = pipeline.remove(manager.decoderName);
        addByteBufHandlers(manager, pipeline, decoder, encoder);
    }

    static void removeByteBufHandlers(NetworkManager manager, ChannelPipeline pipeline) {
        if (pipeline.get(manager.decoderName) != null) {
            pipeline.remove(manager.decoderName);
        }
        if (pipeline.get(manager.encoderName) != null) {
            pipeline.remove(manager.encoderName);
        }
    }

    private static void addDecoder(ChannelPipeline pipeline, String name, ChannelHandler decoder) {
        List<String> names = pipeline.names();
        String vanillaTarget = names.contains(MINECRAFT_INBOUND_CONFIG) ? MINECRAFT_INBOUND_CONFIG : MINECRAFT_DECODER;
        int vanillaIndex = names.indexOf(vanillaTarget);
        String anchor = null;
        String packetEventsTarget = null;
        for (int index = 0; index < vanillaIndex; index++) {
            String candidate = names.get(index);
            if (candidate.equals(MINECRAFT_DECOMPRESSOR) || candidate.equals(VIA_DECODER) || candidate.equals(CRAFTENGINE_DECODER)) {
                anchor = candidate;
            } else if (packetEventsTarget == null && candidate.startsWith(PACKET_EVENTS_DECODER_PREFIX)) {
                packetEventsTarget = candidate;
            }
        }
        if (anchor != null) {
            // 入站正向传播, Sparrow 跟在解压, ViaVersion 和 CraftEngine 之后.
            pipeline.addAfter(anchor, name, decoder);
            return;
        }

        // PacketEvents 在 Sparrow 之后接收入站帧.
        pipeline.addBefore(packetEventsTarget == null ? vanillaTarget : packetEventsTarget, name, decoder);
    }

    private static void addEncoder(ChannelPipeline pipeline, String name, ChannelHandler encoder) {
        List<String> names = pipeline.names();
        String target = names.contains(MINECRAFT_OUTBOUND_CONFIG) ? MINECRAFT_OUTBOUND_CONFIG : MINECRAFT_ENCODER;
        int targetIndex = names.indexOf(target);
        String anchor = null;
        for (int index = 0; index < targetIndex; index++) {
            String candidate = names.get(index);
            if (candidate.equals(MINECRAFT_COMPRESSOR)
                    || candidate.equals(VIA_ENCODER)
                    || candidate.equals(CRAFTENGINE_ENCODER)
                    || candidate.startsWith(PACKET_EVENTS_ENCODER_PREFIX)) {
                anchor = candidate;
            }
        }
        if (anchor != null) {
            // 出站反向传播, Sparrow 在 pipeline 中后置并先于 CraftEngine, PacketEvents 和 ViaVersion 处理帧.
            pipeline.addAfter(anchor, name, encoder);
            return;
        }
        pipeline.addBefore(target, name, encoder);
    }
}
