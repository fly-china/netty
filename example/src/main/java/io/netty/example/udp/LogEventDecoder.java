package io.netty.example.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.CharsetUtil;

import java.util.List;

/**
 * 自定义解码器
 *
 * @author lipengfei
 * @create 2020-02-04 17:18
 **/
public class LogEventDecoder extends MessageToMessageDecoder<DatagramPacket> {
    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        ByteBuf byteBuf = msg.content();
        // 查找分隔符所在的偏移量
        int index = byteBuf.indexOf(0, byteBuf.readableBytes(), LogEvent.FILE_SEPARATOR);
        String fileName = byteBuf.slice(0, index).toString(CharsetUtil.UTF_8);
        String logMsg = byteBuf.slice(index + 1, byteBuf.readableBytes()).toString(CharsetUtil.UTF_8);
        byteBuf.retain(); // netty4中这里应该加这个

        LogEvent logEvent = new LogEvent(msg.sender(), fileName, logMsg, System.currentTimeMillis());
        out.add(logEvent);

    }
}
