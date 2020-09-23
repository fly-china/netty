package io.netty.example.amazing;

import com.sun.jndi.ldap.pool.PooledConnectionFactory;
import io.netty.buffer.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static io.netty.buffer.Unpooled.buffer;

/**
 * 测试ByteBuf相关
 *
 * @author lipengfei
 * @create 2020-07-07 21:55
 **/
public class TestByteBuf {
    private static final Logger logger = LoggerFactory.getLogger(TestByteBuf.class);

    public static void main(String[] args) throws Exception {

        PooledByteBufAllocator pooledByteBufAllocator = PooledByteBufAllocator.DEFAULT;
        // 分配池化直接内存（可以分别试试tiny、small、normal的内存分配）
        ByteBuf byteBuf = pooledByteBufAllocator.buffer(1023 * 32);
        System.out.println("首次分配的内存地址为：" + byteBuf.toString() + "---memoryAddress=" + byteBuf.memoryAddress());
        /**
         * TODO:调用release后，如果refCnt=0，则调用PoolArena#free释放内存。
         *  1、如果ByteBuf为UnPool类型（此处由pool分配器分配出的Unpool内存块必定是：Huge内存），则直接销毁
         *  2、如果ByteBuf为Pool类型，则会尝试调用PoolThreadCache#add方法，将内存块添加到PoolThreadCache中内存数组，方便下次使用
         *      如果PoolThreadCache#add失败（大于32KB的Small内存或内存数组已满），则释放指定位置的 Page / Subpage 内存块回 Chunk 中
         */
        byteBuf.release();
        // TODO:release后会放回SubPagePool，再次分配从SubPagePool中获取,优先从PoolThreadCache中获取。会调用方法：PoolThreadCache#allocateSmall
        ByteBuf byteBuf2 = pooledByteBufAllocator.buffer(1011 * 32);// 只要是（512，1024]区间，都会重用
        System.out.println("再次分配的内存地址为：" + byteBuf2.toString() + "---memoryAddress=" + byteBuf2.memoryAddress());


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
     * 获取大于reqCapacity的最小2次幂的数
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
