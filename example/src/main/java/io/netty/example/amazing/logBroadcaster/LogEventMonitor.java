package io.netty.example.amazing.logBroadcaster;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.net.InetSocketAddress;

/**
 * udp的监控者
 *
 * @author lipengfei
 * @create 2020-02-04 17:15
 **/
public class LogEventMonitor {

    private final static int LISTEN_PORT = 9999;

    public static void main(String[] args) {
        Bootstrap bootstrap = new Bootstrap();
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(new LogEventDecoder());
                            ch.pipeline().addLast(new LogEventMonitorHandler());
                        }
                    })
                    .localAddress(new InetSocketAddress(LISTEN_PORT));
            Channel channel = bootstrap.bind().syncUninterruptibly().channel();
            System.out.println("LogEventMonitor启动了...监听端口：" + LISTEN_PORT);
            channel.closeFuture().sync();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            group.shutdownGracefully();
        }

    }
}
