package net.minecraft.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.chat.Component;

public class Connection extends ChannelInboundHandlerAdapter {

    public Channel channel;

    public void disconnect(Component reason) {
        throw new UnsupportedOperationException();
    }
}
