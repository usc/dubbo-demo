package com.alibaba.dubbo.remoting.transport.netty5;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.TimeUnit;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

/**
 * @author Shunli
 */
public class Netty5Client extends AbstractClient {
    private static final Logger logger = LoggerFactory.getLogger(Netty5Client.class);

    private EventLoopGroup workerGroup;
    private Bootstrap bootstrap;
    private volatile Channel channel; // volatile, please copy reference to use

    public Netty5Client(final URL url, final ChannelHandler handler) throws RemotingException {
        super(url, wrapChannelHandler(url, handler));
    }

    @Override
    protected void doOpen() throws Throwable {
        final Netty5Handler nettyHandler = new Netty5Handler(getUrl(), this);

        workerGroup = new NioEventLoopGroup(Constants.DEFAULT_IO_THREADS, new NamedThreadFactory("Netty5ClientBoss", true));

        bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws Exception {
                        Netty5CodecAdapter adapter = new Netty5CodecAdapter(getCodec(), getUrl(), Netty5Client.this);

                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", adapter.getDecoder());
                        pipeline.addLast("encoder", adapter.getEncoder());
                        pipeline.addLast("handler", nettyHandler);
                    }
                });

    }
    @Override
    protected void doConnect() throws Throwable {
        long start = System.currentTimeMillis();
        ChannelFuture future = bootstrap.connect(getConnectAddress()).sync();
        try {
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);
            if (ret && future.isSuccess()) {
                Channel newChannel = future.channel();
                try {
                    // close old channel
                    Channel oldChannel = Netty5Client.this.channel; // copy
                                                                    // reference
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            Netty5Channel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    if (Netty5Client.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            newChannel.close();
                        } finally {
                            Netty5Client.this.channel = null;
                            Netty5Channel.removeChannelIfDisconnected(newChannel);
                        }
                    } else {
                        Netty5Client.this.channel = newChannel;
                    }
                }
            } else if (future.cause() != null) {
                throw new RemotingException(this, "failed to connect to server " + getRemoteAddress() + ", error message is:" + future.cause().getMessage(), future.cause());
            } else {
                throw new RemotingException(this, "failed to connect to server " + getRemoteAddress() + " client-side timeout " + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client " + NetUtils.getLocalHost());
            }
        } finally {
            if (!isConnected()) {
                future.cancel(true);
            }
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            Netty5Channel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        if (workerGroup != null)
            workerGroup.shutdownGracefully();
    }

    @Override
    protected com.alibaba.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null || !c.isActive())
            return null;
        return Netty5Channel.getOrAddChannel(c, getUrl(), this);
    }

}
