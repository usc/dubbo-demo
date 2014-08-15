package com.alibaba.dubbo.remoting.transport.netty5;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;

/**
 * @author Shunli
 */
@Sharable
public class Netty5Handler extends ChannelHandlerAdapter {
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>(); // <ip:port, channel>
    private final URL url;
    private final ChannelHandler handler;

    public Netty5Handler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            if (channel != null) {
                channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
            }
            handler.connected(channel);
        } finally {
            Netty5Channel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            if (channel != null) {
                channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
            }
            handler.disconnected(channel);
        } finally {
            Netty5Channel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.received(channel, msg);
        } finally {
            Netty5Channel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.sent(channel, msg);
        } finally {
            Netty5Channel.removeChannelIfDisconnected(ctx.channel());

        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Netty5Channel channel = Netty5Channel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.caught(channel, cause);
        } finally {
            Netty5Channel.removeChannelIfDisconnected(ctx.channel());
        }
    }

}
