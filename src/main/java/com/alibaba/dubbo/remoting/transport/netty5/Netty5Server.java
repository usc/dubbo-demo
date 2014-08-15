package com.alibaba.dubbo.remoting.transport.netty5;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

/**
 * @author Shunli
 */
public class Netty5Server extends AbstractServer implements Server {
    private static final Logger logger = LoggerFactory.getLogger(Netty5Server.class);
    private Map<String, Channel> channels;
    private ServerBootstrap bootstrap;
    private io.netty.channel.Channel channel;

    public Netty5Server(URL url, ChannelHandler handler) throws RemotingException {
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    @Override
    protected void doOpen() throws Throwable {
        final URL url = getUrl();
        final Netty5Handler nettyHandler = new Netty5Handler(url, this);
        channels = nettyHandler.getChannels();

        int workerThreads = url.getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS);
        EventLoopGroup bossGroup = new NioEventLoopGroup(Constants.DEFAULT_IO_THREADS, new NamedThreadFactory("Netty5ServerBoss", true));
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerThreads, new NamedThreadFactory("Netty5ServerWorker", true));
        // TODO-Shunli: shutdown when closed

        bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        Netty5CodecAdapter adapter = new Netty5CodecAdapter(getCodec(), url, Netty5Server.this);

                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", adapter.getDecoder());
                        pipeline.addLast("encoder", adapter.getEncoder());
                        pipeline.addLast("handler", nettyHandler);
                    }
                });

        // bind
        ChannelFuture sync = bootstrap.bind(getBindAddress()).sync();
        channel = sync.channel();
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (com.alibaba.dubbo.remoting.Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }

        // TODO-Shunli: TBC
        // try {
        // if (bootstrap != null) {
        // bootstrap.releaseExternalResources();
        // }
        // } catch (Throwable e) {
        // logger.warn(e.getMessage(), e);
        // }

        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean isBound() {
        return channel.isActive();
    }

}
