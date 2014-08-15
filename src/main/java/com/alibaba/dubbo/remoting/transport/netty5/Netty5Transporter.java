package com.alibaba.dubbo.remoting.transport.netty5;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.Transporter;

/**
 * @author Shunli
 */
public class Netty5Transporter implements Transporter {
    public static final String NAME = "netty5";

    @Override
    public Server bind(URL url, ChannelHandler handler) throws RemotingException {
        return new Netty5Server(url, handler);
    }

    @Override
    public Client connect(URL url, ChannelHandler handler) throws RemotingException {
        return new Netty5Client(url, handler);
    }

}
