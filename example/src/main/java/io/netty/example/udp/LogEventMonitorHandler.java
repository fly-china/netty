package io.netty.example.udp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * udp监听者的处理器
 *
 * @author lipengfei
 * @create 2020-02-04 19:56
 **/
public class LogEventMonitorHandler extends SimpleChannelInboundHandler<LogEvent> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, LogEvent logEvent) throws Exception {

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(logEvent.getReceivedTime()).append("]");
        sb.append("[").append(logEvent.getSource().toString()).append("]");
        sb.append("[").append(logEvent.getFileName()).append("]");
        sb.append("[").append(logEvent.getMsg().trim()).append("]");

        System.out.println(sb.toString());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
