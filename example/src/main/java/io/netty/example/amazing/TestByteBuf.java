package io.netty.example.amazing;

import com.sun.jndi.ldap.pool.PooledConnectionFactory;
import io.netty.buffer.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 测试ByteBuf相关
 *
 * @author lipengfei
 * @create 2020-07-07 21:55
 **/
public class TestByteBuf {
    private static final Logger logger = LoggerFactory.getLogger(TestByteBuf.class);

    public static void main(String[] args) throws Exception {

        System.out.println(Integer.toBinaryString(-8192));
        // 分配池化直接内存
        ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.buffer(1024);


        System.out.println("-----------------------");
        int defaultMaxOrder = PooledByteBufAllocator.defaultMaxOrder();
        System.out.println("PooledByteBufAllocator.defaultMaxOrder=" + defaultMaxOrder);

        System.out.println("-----------------------");
        ByteBuf byteBuf1 = Unpooled.directBuffer(6);
//        ByteBuf byteBuf1 = Unpooled.buffer(6);
        byteBuf1.writeBytes("abcdef".getBytes());

        if (byteBuf1.isDirect()) {
            // 直接内存
            byte[] bytes = new byte[byteBuf1.readableBytes()];
            byteBuf1.getBytes(byteBuf1.readerIndex(), bytes);

            // byteBuf1转String的2种方式
            String str2 = byteBuf1.toString(CharsetUtil.UTF_8);
            String str1 = new String(bytes, CharsetUtil.UTF_8);

            System.out.println("直接内存：对象属性为：" + byteBuf1.toString());
            System.out.println("直接内存：byteBuf中的字符串为：" + str1);
            System.out.println("直接内存：byteBuf中的字符串为：" + str2);
            System.out.println("直接内存：byteBuf中的byte[]为：" + Arrays.toString(bytes));
            System.out.println("直接内存：当前引用计数为：" + byteBuf1.refCnt());

        }
        System.out.println("---------------");
        if (byteBuf1.hasArray()) {
            // 堆内存
            byte[] bytes = byteBuf1.array();
            // byteBuf1转String的2种方式
            String str2 = byteBuf1.toString(CharsetUtil.UTF_8);
            String str1 = new String(bytes, CharsetUtil.UTF_8);

            System.out.println("堆内存：对象属性为：" + byteBuf1.toString());
            System.out.println("堆内存：byteBuf中的字符串为：" + str1);
            System.out.println("堆内存：byteBuf中的字符串为：" + str2);
            System.out.println("堆内存：byteBuf中的byte[]为：" + Arrays.toString(bytes));
            System.out.println("堆内存：当前引用计数为：" + byteBuf1.refCnt());
        }

        System.out.println("--------测试PoolArena#normalizeCapacity-------");
        int normalizeCapacity = calcNormalizeCapacity(1026);
        System.out.println("NormalizeCapacity后的容量为：" + normalizeCapacity);
    }


    /**
     * 测试下述方法：io.netty.buffer.PoolArena#normalizeCapacity(int)
     *
     * @param reqCapacity 请求容量
     */
    public static int calcNormalizeCapacity(int reqCapacity) {
        int normalizedCapacity = reqCapacity;
        normalizedCapacity--;
        normalizedCapacity |= normalizedCapacity >>> 1;
        normalizedCapacity |= normalizedCapacity >>> 2;
        normalizedCapacity |= normalizedCapacity >>> 4;
        normalizedCapacity |= normalizedCapacity >>> 8;
        normalizedCapacity |= normalizedCapacity >>> 16;
        normalizedCapacity++;
        return normalizedCapacity;
    }


}
