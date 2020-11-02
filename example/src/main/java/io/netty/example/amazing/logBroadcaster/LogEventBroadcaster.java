package io.netty.example.amazing.logBroadcaster;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;

/**
 * 引导服务器
 *
 * @author lipengfei
 * @create 2020-02-04 15:32
 **/
public class LogEventBroadcaster {

    private final EventLoopGroup group;
    private final Bootstrap bootstrap;
    private final File file;

    public LogEventBroadcaster(InetSocketAddress address, File file) {
        group = new NioEventLoopGroup(2 * Runtime.getRuntime().availableProcessors());
        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true) //   Socket参数，设置广播模式
                .handler(new LogEventEncoder(address));
        this.file = file;
    }

    public static void main(String[] args) {
        int port = 9999;
        File logfile = new File("C:\\Users\\Administrator\\Desktop\\logfile.txt");
        // 255.255.255.255为当前子网的广播地址
        InetSocketAddress address = new InetSocketAddress("255.255.255.255",port);
        LogEventBroadcaster broadcaster = new LogEventBroadcaster(address, logfile);

        try{
            broadcaster.run();
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            broadcaster.stop();
        }
    }

    public void run() throws Exception {
        // 启动引导程序
        ChannelFuture channelFuture = bootstrap.bind(0).sync();
        System.out.println("bootstrap启动成功.....");
        Channel channel = channelFuture.channel();
        long pointer = 0;
        for (; ; ) {
            long length = file.length();
            if(length < pointer){
                // file was reset
                pointer = length;
            }else if (length > pointer){
                // 文件中追加了新内容
                RandomAccessFile accessFile = new RandomAccessFile(file, "r");
                accessFile.seek(pointer); // 设置当前的文件指针，确保旧的文件内容没再次发送
                String line;
                while ((line = accessFile.readLine()) != null){
                    // 每行日志内容被组装成logevent对象后，写入至Channel，被发送出去
                    LogEvent logEvent = new LogEvent(null, file.getAbsolutePath(), line, -1);
                    channel.writeAndFlush(logEvent);
                }
                pointer = accessFile.getFilePointer(); // 记录最新读取的文件offset
                accessFile.close();
            }

            try{
                Thread.sleep(1000); // 休眠1秒再执行
            }catch (Exception e){
                Thread.interrupted();
                break;
            }
        }

    }

    public void stop() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

}
