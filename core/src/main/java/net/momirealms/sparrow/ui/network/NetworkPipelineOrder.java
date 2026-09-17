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

    // 每次调用都按 pipeline 此刻的样子重新算位置, 所以第三方是先装还是后装都不影响结果.
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
     * 摘下自己的两个 handler, 再照 pipeline 当前的样子装回去.
     *
     * @param manager handlers 所属的网络管理器
     * @param channel 要重定位的连接 channel
     */
    @ApiStatus.Internal
    public static void relocateByteBufHandlers(@NotNull NetworkManager manager, @NotNull Channel channel) {
        // 管理器已经关了就不用再碰 pipeline, 假人的 channel 上本来也没装过东西
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
            // 入站顺着 pipeline 往下走, 锚在最后一个在场的 codec 后面, Sparrow 于是落在解压, ViaVersion 和 CraftEngine 之后.
            pipeline.addAfter(anchor, name, decoder);
            return;
        }

        // 一个 codec 都还没装, 就贴着 vanilla 的位置装; PacketEvents 的 decoder 要留在 Sparrow 后面收帧, 所以优先插到它前面.
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
            // 出站逆着 pipeline 传播, 越靠后的 handler 越早拿到帧. Sparrow 锚在最后一个在场的 codec 之后,
            // 于是被取消的帧在 CraftEngine, PacketEvents 和 ViaVersion 看到它之前就没了.
            pipeline.addAfter(anchor, name, encoder);
            return;
        }
        // 同样一个 codec 都没装, 贴着 vanilla encoder 装, 等第三方再插进来时由重定位收口.
        pipeline.addBefore(target, name, encoder);
    }
}
