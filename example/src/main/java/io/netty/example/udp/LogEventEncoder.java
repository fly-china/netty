package io.netty.example.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 自定义编码器，将LogEvent对象转化为数据流
 *
 * @author lipengfei
 * @create 2020-02-04 15:17
 **/
public class LogEventEncoder extends MessageToMessageEncoder<LogEvent> {

    private final InetSocketAddress remote;

    public LogEventEncoder(InetSocketAddress address) {
        this.remote = address;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, LogEvent logEvent, List<Object> out) throws Exception {
        byte[] fileNameBytes = logEvent.getFileName().getBytes(CharsetUtil.UTF_8);
        byte[] logMsgBytes = logEvent.getMsg().getBytes(CharsetUtil.UTF_8);

        ByteBuf byteBuf = ctx.alloc().buffer(fileNameBytes.length + logMsgBytes.length + 1);

        // 将文件名和日志内容按照  文件名:日志内容  的格式，输出
        byteBuf.writeBytes(fileNameBytes);

        byteBuf.writeByte(LogEvent.FILE_SEPARATOR);
        byteBuf.writeBytes(logMsgBytes);
        ;

        out.add(new DatagramPacket(byteBuf, remote));
    }
}
