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

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;
import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();// 是否支持 Unsafe 操作

    enum SizeClass {
        Tiny,       // 16B  - 496B  扩容时：16B递增
        Small,      // 512B - 4KB   扩容时：翻倍递增
        Normal      // 8KB  - 16MB  扩容时：翻倍递增
        // 还有一个隐藏的，Huge     // 32MB - 更大    扩容时：和申请容量有关
    }

    // Tiny类型内存区间：[16，512)，内存分配最小为16，每次增加16，直到512，共有32个不同的值
    static final int numTinySubpagePools = 512 >>> 4;// tinySubpagePools数组的大小，默认：32
    final int numSmallSubpagePools; // smallSubpagePools数组的大小，默认：pageShifts - 9 = 4

    final PooledByteBufAllocator parent;    // 所属 PooledByteBufAllocator 对象

    private final int maxOrder; // 满二叉树的高度（从0开始），默认：11
    final int pageSize; // Page大小，默认：8KB
    final int pageShifts; // 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192
    final int chunkSize;    // Chunk 内存块占用大小。8KB * 2048 = 16MB
    final int subpageOverflowMask; // 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。 -8192

    final int directMemoryCacheAlignment;   // 对齐基准，默认：0
    final int directMemoryCacheAlignmentMask; // 默认：-1。二进制：1111 1111 1111 1111 1111 1111 1111 1111
    private final PoolSubpage<T>[] tinySubpagePools; // tiny 类型的 PoolSubpage 数组，每个元素都是双向链表
    private final PoolSubpage<T>[] smallSubpagePools;// small 类型的 PoolSubpage 数组，每个元素都是双向链表

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // 分配和释放的计数
    // Metrics for allocations and deallocations
    private long allocationsNormal; // 分配 Normal 内存块的次数
    // We need to use the LongCounter here as this is not guarded via synchronized block. 不同步synchronized加锁，所以使用LongCounter保证可见性
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();// 分配 Tiny 内存块的次数
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();// 分配 Small 内存块的次数
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();// 分配 Huge 内存块的次数
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();// 正在使用中的 Huge 内存块的总共占用字节数

    private long deallocationsTiny; // 释放 Tiny 内存块的次数
    private long deallocationsSmall; // 释放 Small 内存块的次数
    private long deallocationsNormal; // 释放 Normal 内存块的次数

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter(); // 释放 Huge 内存块的次数

    // Number of thread caches backed by this arena. 该 PoolArena 被多少线程引用的计数器（被多少个线程在同时使用）
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
                        int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        /**
         * PoolChunkList 之间的双向链表，初始化
         * 正向：qInit -> q000 -> q025 -> q050 -> q075 -> q100 -> null
         * 反向： null <- q000 <- q025 <- q050 <- q075 <- q100
         *       qInit <- qInit
         */
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);
        // qInit前置节点为自己，且minUsage=Integer.MIN_VALUE，意味着一个初分配的chunk，在最开始的内存分配过程中(内存使用率<25%)，即使完全释放也不会被回收，会始终保留在内存中。
        // q000没有前置节点，当一个chunk进入到q000列表，如果其内存被完全释放，则不再保留在内存中，其分配的内存被完全回收

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    // 初始化节点，head节点指向自己本身
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * tiny型内存大小在tinySubpagePools数组的下标
     * 和numTinySubpagePools有关，每块16B，所以：除2^4
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    /**
     * 请求分配的内存大小在 smallSubpagePools 数组的下标
     * TODO:还不太懂算法为何如此
     * numSmallSubpagePools = pageShifts - 9 = 4
     */
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx++;
        }
        return tableIdx;
    }

    // capacity < pageSize（8KB）
    boolean isTinyOrSmall(int normCapacity) {
        // subpageOverflowMask=-8192;二进制为：11111111111111111110 0000 0000 0000
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512(2^9)
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * 分配内存
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity);
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                // 优先从 PoolThreadCache 缓存中，分配 tiny 内存块，并初始化到 PooledByteBuf 中。
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                // 优先从 PoolThreadCache 缓存中，分配 Small 内存块，并初始化到 PooledByteBuf 中。
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            // 获得 PoolSubpage 链表的头节点
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * 在head节点加锁，避免对双向链表的并发修改
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            // 从 PoolSubpage 链表中，分配 Subpage 内存块
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 分配 Subpage 内存块。handle含义：bitmap index of the subpage allocation
                    long handle = s.allocate();
                    assert handle >= 0;
                    // 初始化 Subpage 内存块到 PooledByteBuf 对象中
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    // 增加 allocationsTiny 或 allocationsSmall 计数
                    incTinySmallAllocation(tiny);
                    return;
                }
            }
            // 在 PoolSubpage 链表中，分配不到 Subpage 内存块，所以申请 Normal Page 内存块。实际上，只占用其中一块 Subpage 内存块
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            incTinySmallAllocation(tiny);
            return;
        }
        // 大于8Kb、小于等于16MB时，分配Normal型内存
        if (normCapacity <= chunkSize) {
            // 优先从 PoolThreadCache 缓存中，分配 Normal 内存块，并初始化到 PooledByteBuf 中。
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // 大于16MB时，分配Huge型内存
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    /**
     * Method must be called inside synchronized(this) { ... } block 该方法必须在synchronized同步块内执行
     * q000是用来保存内存利用率在1%-50%的chunk，那么这里为什么不包括0%的chunk？
     * 直接弄清楚这些，才好理解为什么不从q000开始分配。q000中的chunk，当内存利用率为0时，就从链表中删除，直接释放物理内存，避免越来越多的chunk导致内存被占满。
     * <p>
     * 想象一个场景，当应用在实际运行过程中，碰到访问高峰，这时需要分配的内存是平时的好几倍，当然也需要创建好几倍的chunk，
     * 如果先从q0000开始，这些在高峰期创建的chunk被回收的概率会大大降低，延缓了内存的回收进度，造成内存使用的浪费。？？？？
     * <p>
     * 1、q050保存的是内存利用率50%~100%的chunk，这应该是个折中的选择！这样大部分情况下，chunk的利用率都会保持在一个较高水平，提高整个应用的内存利用率
     * 2、qinit的chunk利用率低，但不会被回收
     * 3、q075由于内存利用率太高，导致内存分配的成功率大大降低，因此放到最后；
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 按照优先级，从多个 ChunkList 中，分配 Normal Page 内存块。如果有一分配成功，返回
        if (q050.allocate(buf, reqCapacity, normCapacity)
                || q025.allocate(buf, reqCapacity, normCapacity)
                || q000.allocate(buf, reqCapacity, normCapacity)
                || qInit.allocate(buf, reqCapacity, normCapacity)
                || q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // 第一次进行内存分配时，chunkList没有chunk可以分配内存。newChunk新建一个chunk进行内存分配，并添加到qInit列表中
        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 申请对应的 Normal Page 内存块。实际上，如果申请分配的内存类型为 tiny 或 small 类型，实际申请的是 Subpage 内存块
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    /**
     * 申请 Huge 内存块
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        // 新建 Chunk 内存块，它是 unpooled 的
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize()); // 增加Huge内存块的使用量
        // 初始化 Huge 内存块到 PooledByteBuf 对象中
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();    // 增加 Huge 内存块分配的次数
    }

    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            // Huge 内存块的分配使用unpooled
            int size = chunk.chunkSize();
            // 直接销毁 Chunk 内存块，因为占用空间较大。
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            // TODO:添加内存块到 PoolThreadCache 缓存指定的 MemoryRegionCache 的队列中，方便下次使用
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            // 释放指定位置的 Page / Subpage 内存块回 Chunk 中
            freeChunk(chunk, handle, sizeClass, nioBuffer);
        }
    }

    /**
     * 根据请求内存大小，判断内存类型
     */
    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    // 释放指定位置的 Page / Subpage 内存块回 Chunk 中
    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
                case Normal:
                    ++deallocationsNormal;
                    break;
                case Small:
                    ++deallocationsSmall;
                    break;
                case Tiny:
                    ++deallocationsTiny;
                    break;
                default:
                    throw new Error();
            }
            // 释放指定位置的内存块
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        // destroyChunk=true <=> free()失败; 意味着 Chunk 中不存在在使用的 Page / Subpage 内存块。也就是说，内存使用率为 0 ，所以销毁 Chunk
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    /**
     * 获得请求分配的 Subpage 类型的内存的链表的头节点
     *
     * @param elemSize 申请容量的normalizeCapacity大小
     */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    /**
     * 根据请求的大小，计算标准化的内存大小。保证分配的内存块统一
     */
    int normalizeCapacity(int reqCapacity) {
        checkPositiveOrZero(reqCapacity, "reqCapacity"); // 校验非负数

        // 大于16MB（2^24），为Huge内存类型，直接使用 reqCapacity ，无需进行标准化。
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        // small、normal的标准化内存容量
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled
            // 计算出大于reqCapacity的最小的2次幂的容量.类似于HashMap中的tableSizeFor方法
            int normalizedCapacity = reqCapacity;
            normalizedCapacity--;
            normalizedCapacity |= normalizedCapacity >>> 1;
            normalizedCapacity |= normalizedCapacity >>> 2;
            normalizedCapacity |= normalizedCapacity >>> 4;
            normalizedCapacity |= normalizedCapacity >>> 8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Tiny类型，补齐成 16B 的倍数。
        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {// 已是16的倍数
            return reqCapacity;
        }

        // 等价于：低4位置为0，再加16。相当于找最近的16的倍数
        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        // 补齐 directMemoryCacheAlignment ，并减去 delta
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    /**
     * 因为要扩容或缩容，所以重新分配合适的内存块给 PooledByteBuf 对象
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        // 容量大小没有变化，直接返回
        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        // 分配新的内存块给 PooledByteBuf 对象
        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            // 扩容。将老的内存块的数据，复制到新的内存块中
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            // 缩容
            if (readerIndex < newCapacity) {
                // 有部分数据未读取完
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                // 只复制未读取完的，且在newCapacity范围内的部分数据
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }
        // 重新设置读写Index
        buf.setIndex(readerIndex, writerIndex);

        // 释放老的内存块
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m : chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);

    protected abstract PoolChunk<T> newUnpooledChunk(int capacity); //  申请 Huge 内存块

    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);

    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);

    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
                .append("Chunk(s) at 0~25%:")
                .append(StringUtil.NEWLINE)
                .append(qInit)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 0~50%:")
                .append(StringUtil.NEWLINE)
                .append(q000)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 25~75%:")
                .append(StringUtil.NEWLINE)
                .append(q025)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 50~100%:")
                .append(StringUtil.NEWLINE)
                .append(q050)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 75~100%:")
                .append(StringUtil.NEWLINE)
                .append(q075)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 100%:")
                .append(StringUtil.NEWLINE)
                .append(q100)
                .append(StringUtil.NEWLINE)
                .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
                .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    /**
     * PoolArena 对象被 GC 回收时，清理其管理的内存。实际上，主要是为了清理对外内存
     */
    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList : chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                  int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                    int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test 标记为包私有，仅用于单元测试
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                                                 int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
