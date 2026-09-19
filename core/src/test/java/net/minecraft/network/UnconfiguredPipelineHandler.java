package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.Packet;

// 保留 Paper 1.21.8 中等待入站协议配置的 handler 与任务契约.
public final class UnconfiguredPipelineHandler {
    public static class Inbound extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (!(message instanceof ByteBuf) && !(message instanceof Packet)) {
                context.fireChannelRead(message);
            } else {
                ReferenceCountUtil.release(message);
                throw new DecoderException("Pipeline has no inbound protocol configured, can't process packet " + message);
            }
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof InboundConfigurationTask task) {
                try {
                    task.run(context);
                } finally {
                    ReferenceCountUtil.release(message);
                }
                promise.setSuccess();
            } else {
                context.write(message, promise);
            }
        }
    }

    @FunctionalInterface
    public interface InboundConfigurationTask {
        void run(ChannelHandlerContext context);
    }
}
