package io.netty.example.amazing;

import io.netty.buffer.*;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.PlatformDependent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 测试ByteBuf相关
 * 池化连接池：ByteBufAllocator.DEFAULT 或 PooledByteBufAllocator.DEFAULT
 * 非池化连接池：UnpooledByteBufAllocator.DEFAULT 或 UnPooled
 *
 * @author lipengfei
 **/
public class TestPooledAllocator {
    private static final Logger logger = LoggerFactory.getLogger(TestPooledAllocator.class);
    private static final int tinyMem = 496;          // [16B, 496B]
    private static final int smallMem = 4 * 1024;    // [512B, 4MB]
    private static final int normalMem = 8 * 1024;   // [8KB, 16MB]
    private static final int hugeMem = 17 * 1024 * 1024; // (16MB, more)

    public static void main(String[] args) throws Exception {

        PooledByteBufAllocator pooledAllocator2 = (PooledByteBufAllocator) ByteBufAllocator.DEFAULT;
        PooledByteBufAllocator pooledAllocator = PooledByteBufAllocator.DEFAULT;
        System.out.println("pooledAllocator==pooledAllocator2等于：" + (pooledAllocator == pooledAllocator2));
        System.out.println("-----------------------");

        // 测试分配Normal内存块：先分配8KB,再分配16KB，最后再分配8KB
        //        testAllocateNormal(pooledAllocator);

        // 测试分配SubPage内存块：先分配16B,再分配32B，最后再分配16B
        //        testAllocateSubPage(pooledAllocator, true);
        testAllocateSubPage(pooledAllocator, false);

        // 测试分配Huge内存块
        //        ByteBuf byteBuf = pooledAllocator.buffer(hugeMem);


        //        testAllocateByThreadCache(pooledAllocator);
    }

    /**
     * 测试分配SubPage内存块：先分配16B,再分配32B，最后再分配16B
     */
    public static void testAllocateSubPage(PooledByteBufAllocator pooledAllocator, boolean release) {
        ByteBuf byteBuf = testAllocate(pooledAllocator, 160);
        if (release) {
            // 释放内存
            byteBuf.release();
        }
        testAllocate(pooledAllocator, 32);
        testAllocate(pooledAllocator, 16);
    }

    /**
     * 测试分配Normal内存块：先分配8KB,再分配16KB，最后再分配8KB
     */
    public static void testAllocateNormal(PooledByteBufAllocator pooledAllocator) {
        testAllocate(pooledAllocator, normalMem);
        testAllocate(pooledAllocator, normalMem * 2);
        testAllocate(pooledAllocator, normalMem);
    }

    /**
     * 测试分配Huge内存块
     */
    public static void testAllocateNHuge(PooledByteBufAllocator pooledAllocator) {
        testAllocate(pooledAllocator, hugeMem);
    }


    /**
     * 测试PoolThreadCache的分配
     */
    public static void testAllocateByThreadCache(PooledByteBufAllocator pooledAllocator) {
        // 分配池化直接内存（可以分别试试tiny、small、normal的内存分配）
        ByteBuf byteBuf = pooledAllocator.buffer(1023 * 32);
        System.out.println("首次分配的内存地址为：" + byteBuf.toString() + "---memoryAddress=" + byteBuf.memoryAddress());
        /**
         * TODO:调用release后，如果refCnt=0，则调用PoolArena#free释放内存。
         *  1、如果ByteBuf为UnPool类型（此处由pool分配器分配出的Unpool内存块必定是：Huge内存），则直接销毁
         *  2、如果ByteBuf为Pool类型，则会尝试调用PoolThreadCache#add方法，将内存块添加到PoolThreadCache中内存数组，方便下次使用
         *      如果PoolThreadCache#add失败（大于32KB的Small内存或内存数组已满），则释放指定位置的 Page / Subpage 内存块回 Chunk 中
         */
        byteBuf.release();
        // TODO:release后会放回SubPagePool，再次分配从SubPagePool中获取,优先从PoolThreadCache中获取。会调用方法：PoolThreadCache#allocateSmall
        ByteBuf byteBuf2 = pooledAllocator.buffer(1011 * 32);// 只要是（512，1024]区间，都会重用
        System.out.println("再次分配的内存地址为：" + byteBuf2.toString() + "---memoryAddress=" + byteBuf2.memoryAddress());

        System.out.println("-----------------------");
    }

    /**
     * 公用分配方法
     */
    public static ByteBuf testAllocate(PooledByteBufAllocator pooledAllocator, int allocSize) {
        // 分配池化直接内存（可以分别试试tiny、small、normal的内存分配）
        ByteBuf byteBuf = pooledAllocator.buffer(allocSize);
        System.out.println("分配的内存地址为：" + byteBuf.toString() + "---memoryAddress=" + byteBuf.memoryAddress());
        return byteBuf;
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
