/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.echo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * Sends one message when a connection is open and echoes back any received
 * data to the server.  Simply put, the echo client initiates the ping-pong
 * traffic between the echo client and server by sending the first message to
 * the server.
 */
public final class EchoClient {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final String HOST = System.getProperty("host", "127.0.0.1");
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));
    static final int SIZE = Integer.parseInt(System.getProperty("size", "256"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.git
        // 配置SSL，https、wss的使用
        final SslContext sslCtx;
        if (SSL) {
            sslCtx = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }

        // Configure the client.
        // 创建线程组
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // 创建Client端的启动引导程序
            Bootstrap b = new Bootstrap();
            b.group(group)  // 配置声明的线程组
             .channel(NioSocketChannel.class)// 指定配置Client端SocketChannel的类型
             .option(ChannelOption.TCP_NODELAY, true) // 配置Channel相关的option属性。本处为TCP的Nagle算法。启动TCP_NODELAY，无延时，小数据也会立即传输，就意味着禁用了Nagle算法
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc(), HOST, PORT));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     // 在pipeLine中添加客户端处理Handler
                     p.addLast(new EchoClientHandler());
                 }
             });

            // Start the client. 连接指定ip+端口，启动客户端，并同步等待成功
//            ChannelFuture f = b.connect(HOST, PORT).sync();
            ChannelFuture channelFuture = b.connect(HOST, PORT).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    System.out.println("连接完成");
                }
            });

            // Wait until the connection is closed.
            // 监听服务端关闭，并阻塞等待
            channelFuture.channel().closeFuture().sync();
        } finally {
            // Shut down the event loop to terminate all threads.
            group.shutdownGracefully();
        }
    }


    /**
     * Nagle算法就是为了尽可能发送大块数据，避免网络中充斥着许多小数据块。
     * Nagle算法的基本定义是任意时刻，最多只能有一个未被确认的小段,
     *      - 所谓“小段”，指的是小于MSS尺寸的数据块，
     *      - 所谓“未被确认”，是指一个数据块发送出去后，没有收到对方发送的ACK确认该数据已收到。
     */
}
