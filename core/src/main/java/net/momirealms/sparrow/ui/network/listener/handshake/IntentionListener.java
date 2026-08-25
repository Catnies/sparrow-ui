package net.momirealms.sparrow.ui.network.listener.handshake;

import io.netty.channel.Channel;
import net.momirealms.sparrow.ui.network.ByteBufPacketEvent;
import net.momirealms.sparrow.ui.network.ByteBufPacketListener;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.NetworkUser;
import net.momirealms.sparrow.ui.network.PacketBuf;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

@ApiStatus.Internal
public final class IntentionListener implements ByteBufPacketListener {
    private final Consumer<Channel> relocate;

    public IntentionListener(@NotNull Consumer<Channel> relocate) {
        this.relocate = relocate;
    }

    @Override
    public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
        PacketBuf buffer = event.getBuffer();
        buffer.readVarInt();
        buffer.readUtf(255);
        buffer.readUnsignedShort();
        ConnectionState nextState = switch (buffer.readVarInt()) {
            case 1 -> ConnectionState.STATUS;
            case 2, 3 -> ConnectionState.LOGIN;
            default -> throw new IllegalArgumentException("Invalid intention state");
        };
        user.setConnectionState(nextState);
        if (nextState == ConnectionState.LOGIN) {
            // CraftEngine 存在时先提交自己的重排任务, Sparrow 随后收口最终顺序.
            user.channel().eventLoop().execute(() -> this.relocate.accept(user.channel()));
        }
    }
}
