package com.alibaba.dubbo.remoting.transport.netty5;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.IOException;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffers;
import com.alibaba.dubbo.remoting.buffer.DynamicChannelBuffer;

/**
 * @author Shunli
 */
final class Netty5CodecAdapter {
    private final ChannelHandler encoder = new InternalEncoder();
    private final ChannelHandler decoder = new InternalDecoder();
    private final Codec2 codec;
    private final URL url;
    private final int bufferSize;
    private final com.alibaba.dubbo.remoting.ChannelHandler handler;

    public Netty5CodecAdapter(Codec2 codec, URL url, com.alibaba.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
        int b = url.getPositiveParameter(Constants.BUFFER_KEY, Constants.DEFAULT_BUFFER_SIZE);
        this.bufferSize = b >= Constants.MIN_BUFFER_SIZE && b <= Constants.MAX_BUFFER_SIZE ? b : Constants.DEFAULT_BUFFER_SIZE;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }

    @Sharable
    private class InternalEncoder extends MessageToByteEncoder<Object> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
            com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer = com.alibaba.dubbo.remoting.buffer.ChannelBuffers.dynamicBuffer(1024);
            Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
            try {
                codec.encode(channel, buffer, msg);
            } finally {
                Netty5Channel.removeChannelIfDisconnected(ctx.channel());
            }

            out.writeBytes(buffer.toByteBuffer());
        }
    }

    @Sharable
    private class InternalDecoder extends SimpleChannelInboundHandler<ByteBuf> {
        private ChannelBuffer buffer = ChannelBuffers.EMPTY_BUFFER;

        @Override
        protected void messageReceived(ChannelHandlerContext ctx, ByteBuf input) throws Exception {
            int readable = input.readableBytes();
            if (readable <= 0) {
                return;
            }

            byte[] bytes = new byte[readable];
            input.readBytes(bytes);
            // input.getBytes(input.readerIndex(), bytes);

            ChannelBuffer message;
            if (buffer.readable()) {
                if (buffer instanceof DynamicChannelBuffer) {
                    buffer.writeBytes(bytes);
                    message = buffer;
                } else {
                    int size = buffer.readableBytes() + input.readableBytes();
                    message = ChannelBuffers.dynamicBuffer(size > bufferSize ? size : bufferSize);
                    message.writeBytes(buffer, buffer.readableBytes());
                    message.writeBytes(bytes);
                }
            } else {
                message = ChannelBuffers.wrappedBuffer(bytes);
            }

            Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
            Object msg;
            int saveReaderIndex;
            try {
                // decode object.
                do {
                    saveReaderIndex = message.readerIndex();
                    try {
                        msg = codec.decode(channel, message);
                    } catch (IOException e) {
                        buffer = ChannelBuffers.EMPTY_BUFFER;
                        throw e;
                    }

                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        message.readerIndex(saveReaderIndex);
                        break;
                    } else {
                        if (saveReaderIndex == message.readerIndex()) {
                            buffer = ChannelBuffers.EMPTY_BUFFER;
                            throw new IOException("Decode without read data.");
                        }
                        if (msg != null) {
                            ctx.fireChannelRead(msg);
                        }
                    }
                } while (message.readable());
            } finally {
                if (message.readable()) {
                    message.discardReadBytes();
                    buffer = message;
                } else {
                    buffer = ChannelBuffers.EMPTY_BUFFER;
                }

                Netty5Channel.removeChannelIfDisconnected(ctx.channel());
            }
        }
    }
}
