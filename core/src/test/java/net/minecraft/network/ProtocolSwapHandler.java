package net.minecraft.network;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.protocol.Packet;

// 对齐 Paper 1.21.8 的入站终止包步骤, 通过真实 Netty pipeline 验证注入入口.
public interface ProtocolSwapHandler {
    static void handleInboundTerminalPacket(ChannelHandlerContext context, Packet<?> packet) {
        if (packet.isTerminal()) {
            context.channel().config().setAutoRead(false);
            context.pipeline().addBefore(context.name(), "inbound_config", new UnconfiguredPipelineHandler.Inbound());
            context.pipeline().remove(context.name());
        }
    }
}
