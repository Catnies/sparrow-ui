package net.momirealms.sparrow.ui.network;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPipelineOrderTest {

    private NetworkManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
    }

    @AfterEach
    void tearDown() {
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private EmbeddedChannel channelWith(String... names) {
        EmbeddedChannel channel = new EmbeddedChannel();
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            if (name.contains("encoder") || name.equals("compress") || name.equals("prepender") || name.equals("outbound_config")) {
                channel.pipeline().addLast(name, new ChannelOutboundHandlerAdapter());
            } else {
                channel.pipeline().addLast(name, new ChannelInboundHandlerAdapter());
            }
        }
        NetworkPipelineOrder.addByteBufHandlers(
                this.manager,
                channel.pipeline(),
                new ChannelInboundHandlerAdapter(),
                new ChannelOutboundHandlerAdapter()
        );
        return channel;
    }

    private void assertOrder(ChannelPipeline pipeline, String earlier, String later) {
        List<String> names = pipeline.names();

        assertTrue(names.indexOf(earlier) < names.indexOf(later),
                earlier + " 应当排在 " + later + " 之前, 实际顺序: " + names);
    }

    @Test
    void sitsNextToTheVanillaCodec() {
        EmbeddedChannel channel = this.channelWith("splitter", "decoder", "prepender", "encoder");
        this.assertOrder(channel.pipeline(), this.manager.decoderName, "decoder");
        this.assertOrder(channel.pipeline(), this.manager.encoderName, "encoder");
    }

    @Test
    void fallsBackToTheConfigurationCodecNames() {
        EmbeddedChannel channel = this.channelWith("splitter", "inbound_config", "prepender", "outbound_config");
        this.assertOrder(channel.pipeline(), this.manager.decoderName, "inbound_config");
        this.assertOrder(channel.pipeline(), this.manager.encoderName, "outbound_config");
    }

    @Test
    void readsInboundAfterDecompression() {
        EmbeddedChannel channel = this.channelWith("splitter", "decompress", "decoder", "prepender", "encoder");
        this.assertOrder(channel.pipeline(), "decompress", this.manager.decoderName);
    }

    @Test
    void readsInboundAfterViaVersion() {
        EmbeddedChannel channel = this.channelWith("splitter", "via-decoder", "decoder", "prepender", "encoder");
        this.assertOrder(channel.pipeline(), "via-decoder", this.manager.decoderName);
    }

    @Test
    void readsInboundAfterCraftEngine() {
        EmbeddedChannel channel = this.channelWith("splitter", "craftengine_decoder", "decoder", "prepender", "encoder");
        this.assertOrder(channel.pipeline(), "craftengine_decoder", this.manager.decoderName);
    }

    @Test
    void handsInboundToPacketEventsAfterwards() {
        EmbeddedChannel channel = this.channelWith("splitter", "pe-decoder-1", "decoder", "prepender", "encoder");
        this.assertOrder(channel.pipeline(), this.manager.decoderName, "pe-decoder-1");
    }

    @Test
    void writesOutboundBeforeCompression() {
        EmbeddedChannel channel = this.channelWith("splitter", "decoder", "prepender", "compress", "encoder");
        this.assertOrder(channel.pipeline(), "compress", this.manager.encoderName);
    }

    @Test
    void writesOutboundBeforeEveryThirdPartyEncoder() {
        EmbeddedChannel channel = this.channelWith("splitter", "decoder", "prepender", "via-encoder", "pe-encoder-1", "craftengine_encoder", "encoder");
        this.assertOrder(channel.pipeline(), "via-encoder", this.manager.encoderName);
        this.assertOrder(channel.pipeline(), "pe-encoder-1", this.manager.encoderName);
        this.assertOrder(channel.pipeline(), "craftengine_encoder", this.manager.encoderName);
    }
}
