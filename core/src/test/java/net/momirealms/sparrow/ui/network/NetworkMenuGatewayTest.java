package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import net.momirealms.sparrow.ui.network.packet.PacketTypes;
import net.momirealms.sparrow.ui.window.filter.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.window.handle.MenuInput;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NetworkMenuGatewayTest {
    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;
    private Player player;
    private AutoCloseable gateway;
    private final List<MenuInput> inputs = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        var server = MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.user = this.manager.injectConnectionChannel(this.channel);
        this.user.setConnectionState(ConnectionState.PLAY);
        this.player = new PlayerMock(server, "GatewayTest");
        this.user.player(this.player);
        Class<?> gatewayClass = Class.forName("net.momirealms.sparrow.ui.window.handle.MenuPacketGateway");
        Constructor<?> constructor = gatewayClass.getDeclaredConstructor(Plugin.class, NetworkManager.class);
        constructor.setAccessible(true);
        this.gateway = (AutoCloseable) constructor.newInstance(SparrowUI.getInstance().getPlugin(), this.manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.gateway.close();
        this.channel.finishAndReleaseAll();
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private AutoCloseable open(ClientboundPacketFilter filter) throws Exception {
        Method method = this.gateway.getClass().getDeclaredMethod("open", Player.class, int.class, Consumer.class, ClientboundPacketFilter.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(this.gateway, this.player, 7, (Consumer<MenuInput>) this.inputs::add, filter);
    }

    private ByteBuf frame(PacketType type, Consumer<PacketBuf> payload) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.manager.packetIds().id(type));
        payload.accept(buffer);
        return buffer.source();
    }

    private void consumed(PacketType type, Consumer<PacketBuf> payload) {
        ByteBuf frame = this.frame(type, payload);
        assertFalse(this.channel.writeInbound(frame));
        assertEquals(0, frame.refCnt());
    }

    @Test
    void translatesAllEightInboundRoutesAndPreservesPongForwarding() throws Exception {
        ByteBuf inactive = this.frame(PacketTypes.Play.Serverbound.RENAME_ITEM, b -> b.writeUtf("inactive"));
        this.channel.writeInbound(inactive);
        assertSame(inactive, this.channel.readInbound());
        inactive.release();
        assertTrue(this.inputs.isEmpty());
        try (AutoCloseable session = this.open(null)) {
            this.consumed(PacketTypes.Play.Serverbound.BUNDLE_ITEM_SELECTED, b -> b.writeVarInt(2).writeVarInt(3));
            this.consumed(PacketTypes.Play.Serverbound.CONTAINER_CLOSE, b -> b.writeVarInt(7));
            this.consumed(PacketTypes.Play.Serverbound.RENAME_ITEM, b -> b.writeUtf("rename"));
            this.consumed(PacketTypes.Play.Serverbound.CONTAINER_SLOT_STATE_CHANGED, b -> b.writeVarInt(2).writeVarInt(7).writeBoolean(true));
            this.consumed(PacketTypes.Play.Serverbound.CONTAINER_BUTTON_CLICK, b -> b.writeVarInt(7).writeVarInt(3));
            this.consumed(PacketTypes.Play.Serverbound.PLACE_RECIPE, b -> b.writeVarInt(7).writeVarInt(42).writeBoolean(true));
            this.consumed(PacketTypes.Play.Serverbound.SELECT_TRADE, b -> b.writeVarInt(4));
            ByteBuf pong = this.frame(PacketTypes.Play.Serverbound.PONG, b -> b.writeInt(123456));
            this.channel.writeInbound(pong);
            assertSame(pong, this.channel.readInbound());
            pong.release();
            assertEquals(List.of(
                    new MenuInput.Common.BundleSelection(7, 2, 3),
                    new MenuInput.Common.Close(7),
                    new MenuInput.WindowSpecific.Rename("rename"),
                    new MenuInput.WindowSpecific.CrafterSlotState(7, 2, true),
                    new MenuInput.WindowSpecific.ButtonClick(7, 3),
                    new MenuInput.WindowSpecific.RecipePlace(7, 42, true),
                    new MenuInput.WindowSpecific.TradeSelect(7, 4),
                    new MenuInput.Common.Pong(123456)
            ), this.inputs);
        }
        ByteBuf ended = this.frame(PacketTypes.Play.Serverbound.RENAME_ITEM, b -> b.writeUtf("ended"));
        this.channel.writeInbound(ended);
        assertSame(ended, this.channel.readInbound());
        ended.release();
        assertEquals(8, this.inputs.size());
    }

    @Test
    void filtersBothOutboundRoutesAndClosesSubscriptions() throws Exception {
        this.open(ids -> new int[]{ids.id(PacketTypes.Play.Clientbound.MERCHANT_OFFERS), ids.id(PacketTypes.Play.Clientbound.UPDATE_RECIPES)});
        for (PacketType type : List.of(PacketTypes.Play.Clientbound.MERCHANT_OFFERS, PacketTypes.Play.Clientbound.UPDATE_RECIPES)) {
            ByteBuf frame = this.frame(type, b -> b.writeByte(0));
            assertFalse(this.channel.writeOutbound(frame));
            assertEquals(0, frame.refCnt());
            // 菜单主动发送的替代包使用静默入口, 同一会话的过滤器应放行.
            ByteBuf replacement = this.frame(type, b -> b.writeByte(1));
            this.user.sendByteBufSilently(replacement);
            assertSame(replacement, this.channel.readOutbound());
            replacement.release();
        }
        this.gateway.close();
        ByteBuf frame = this.frame(PacketTypes.Play.Clientbound.MERCHANT_OFFERS, b -> b.writeByte(0));
        this.channel.writeOutbound(frame);
        assertSame(frame, this.channel.readOutbound());
        frame.release();
        ByteBuf inbound = this.frame(PacketTypes.Play.Serverbound.RENAME_ITEM, b -> b.writeUtf("closed"));
        this.channel.writeInbound(inbound);
        assertSame(inbound, this.channel.readInbound());
        inbound.release();
        assertTrue(this.inputs.isEmpty());
    }
}
