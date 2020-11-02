package io.netty.example.amazing.logBroadcaster;

import java.net.InetSocketAddress;

/**
 * @author lipengfei
 * @create 2020-02-04 14:07
 **/
public class LogEvent {

    public static final byte FILE_SEPARATOR = (byte) '$';
    private final InetSocketAddress source;
    private final String fileName;
    private final String msg;
    private final long receivedTime;

    public LogEvent(String fileName, String msg) {
        this(null, fileName, msg, -1);
    }

    public LogEvent(InetSocketAddress source, String fileName, String msg, long receivedTime) {
        this.source = source;
        this.fileName = fileName;
        this.msg = msg;
        this.receivedTime = receivedTime;
    }

    public InetSocketAddress getSource() {
        return source;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMsg() {
        return msg;
    }

    public long getReceivedTime() {
        return receivedTime;
    }
}
