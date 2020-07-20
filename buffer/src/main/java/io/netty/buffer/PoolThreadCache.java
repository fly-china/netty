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


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 充当分配的线程缓存。该实现是在jemalloc和使用jemalloc的可伸缩内存分配技术之后进行模块化的
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 * <p>
 * 1、在释放已分配的内存块时，不放回到 Chunk 中，而是缓存到 ThreadCache 中。
 * 2、在分配内存块时，优先从 tcache 获取。无法获取到，再从 Chunk 中分配
 * 通过这样的方式，尽可能的避免多线程的同步和竞争。
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    final PoolArena<byte[]> heapArena;  // 对应heapArena对象
    final PoolArena<ByteBuffer> directArena;  // 对应directArena对象

    // Hold the caches for the different size classes, which are tiny, small and normal.
    // 不同类型内存块的缓存数组
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches; // 默认大小：32；cache[0] = 16B、cache[1] = 32B........、cache[31] = 496B
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches; // 默认大小：4； cache[0] = 512B、cache[1] = 1KB、cache[2] = 2KB、cache[2] = 4KB。
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;// 默认大小：3； cache[0] = 8KB、cache[1] = 16KB、cache[2] = 32KB。64Kb及以上，不走缓存
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    /**
     * 用于计算请求分配的 normal 类型的内存块，在 normalDirectCaches或normalHeapCaches 数组中的位置
     * 默认为 log2(pageSize) = log2(8192) = 13
     */
    private final int numShiftsNormalDirect;
    private final int numShiftsNormalHeap;
    private final AtomicBoolean freed = new AtomicBoolean(); // 是否被释放。默认：false。当本ThreadLocal对象被销毁或回收时，会修改为true

    private int allocations;// 当前已分配次数（注意：次数，并非节点数）
    /**
     * allocations属性到达该阀值时，（所有不同类型内存块的缓存数组中）没有被分配出去的缓存空间都要被释放，调用trim()方法释放缓存，默认：8192次
     */
    private final int freeSweepAllocationThreshold;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {
        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            tinySubPageDirectCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     * 创建 Subpage内存块缓存数组
     * tiny  类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_TINY_CACHE_SIZE  = 512 , numCaches = PoolArena.numTinySubpagePools = 512 >>> 4 = 32
     * small 类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_SMALL_CACHE_SIZE = 256 , numCaches = PoolArena.numSmallSubpagePools = 4
     *
     * numCaches与PoolArena中的numTinySubpagePools或numSmallSubpagePools大小保持一致，相同大小的内存，能对应相同的数组下标。
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * 创建 Normal 内存块缓存数组
     * normal 类型，默认 cacheSize = PooledByteBufAllocator.DEFAULT_NORMAL_CACHE_SIZE = 64
     *  maxCachedBufferCapacity = PooledByteBufAllocator.DEFAULT_MAX_CACHED_BUFFER_CAPACITY = 32*1024
     */
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            // 默认情况：chunkSize=16MB，maxCachedBufferCapacity=32KB
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            // log2(max / area.pageSize) + 1 = 3
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            // 数组大小为3；刚好是 cache[0] = 8KB、cache[1] = 16KB、cache[2] = 32KB
            // 如果申请的 Normal 内存块大小为 64KB ，超过了maxCachedBufferCapacity大小，所以无法被缓存
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    /**
     * 试着从缓存中分配一个 Tiny 类型缓冲区。如果成功返回true，否则返回false
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * 试着从缓存中分配一个 Small 类型缓冲区。如果成功返回true，否则返回false
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        boolean allocated = cache.allocate(buf, reqCapacity);
        // 增加 allocations 计数。若到达阀值( freeSweepAllocationThreshold )，重置计数，并调用trim方法，整理缓存
        if (++allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * 将PoolChunk内存块，加入对应内存类型的 MemoryRegionCache 的队列中，进行缓存
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        if (cache == null) {
            return false;
        }
        return cache.add(chunk, nioBuffer, handle);
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
            case Normal:
                return cacheForNormal(area, normCapacity);
            case Small:
                return cacheForSmall(area, normCapacity);
            case Tiny:
                return cacheForTiny(area, normCapacity);
            default:
                throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    // 对象销毁时，清空缓存
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free();
        }
    }

    /**
     * Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free() {
        // 要确保此方法只会被调用一次。或者在finalize时，或者在ThreadLocal被销毁时
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            // 晴空缓存
            int numFreed = free(tinySubPageDirectCaches) +
                    free(smallSubPageDirectCaches) +
                    free(normalDirectCaches) +
                    free(tinySubPageHeapCaches) +
                    free(smallSubPageHeapCaches) +
                    free(normalHeapCaches);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c : caches) {
            numFreed += free(c);
        }
        return numFreed;
    }

    // 整理缓存，释放使用频度较少的内存块缓存
    private static int free(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return 0;
        }
        return cache.free();
    }

    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c : caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    /**
     * 获取Tiny内存快，normCapacity容量对应所在的 MemoryRegionCache 对象
     * cache[0] = 16B、cache[1] = 32B........、cache[31] = 496B
     */
    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        // 获得数组下标
        int idx = PoolArena.tinyIdx(normCapacity);
        if (area.isDirect()) {
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    /**
     * 获取Small内存快，normCapacity容量对应所在的 MemoryRegionCache 对象
     * cache[0] = 512B、 cache[1] = 1KB、 cache[2] = 2KB、cache[2] = 4KB
     */
    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        int idx = PoolArena.smallIdx(normCapacity);
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    /**
     * 获取Normal内存快，normCapacity容量对应所在的 MemoryRegionCache 对象
     * cache[0] = 8K、 cache[1] = 16K、 cache[2] = 32K、
     */
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            // numShiftsNormalDirect = 13。
            // idx为：normCapacity / 2^13后再取2的对数。（2^13为normal内存块的最小值：8KB）
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            // 初始化 Subpage 内存块到 PooledByteBuf 对象中
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            // 初始化 Page 内存块到 PooledByteBuf 对象中
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        private final int size;// tinySize=512，smallSize=256，normalSize=64。
        private final Queue<Entry<T>> queue; // 队列的容量 = size
        private final SizeClass sizeClass;
        private int allocations;

        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * 如果缓存队列queue还没有满，则假如队列中
         * Add to cache if not already full.
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle) {
            Entry<T> entry = newEntry(chunk, nioBuffer, handle);
            boolean queued = queue.offer(entry);
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry 回收
                entry.recycle();
            }

            return queued;
        }

        /**
         * 从缓存队列中取出一个缓存内存快节点，方便使用
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity);
            entry.recycle();

            // allocations变量虽然不是线程安全的，但是只有ThreadLocal调用，不会有安全问题
            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            ++allocations;
            return true;
        }

        /**
         * 清除队列中全部元素
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free() {
            return free(Integer.MAX_VALUE);
        }

        /**
         * 清除队列中指定数量的元素
         */
        private int free(int max) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll();
                // 获取并移除首元素
                if (entry != null) {
                    freeEntry(entry);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * 当分配操作达到一定阈值（Netty默认8192）时，没有被分配出去的缓存空间都要被释放，以防止内存泄漏
         * 就是说，期望一个 MemoryRegionCache 频繁进行回收-分配，这样 allocations > size ，将不会释放队列中的任何一个节点表示的内存空间；
         * 如果长时间没有分配，则应该释放这一部分空间，防止内存占据过多。如：Tiny缓存512个节点、Small缓存256个节点、Noraml缓存64个节点
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        public final void trim() {
            // size 代表队列中总数量
            // allocations 表示已经分配出去的ByteBuf个数
            int free = size - allocations;
            allocations = 0;

            // 在一定阈值内还没被分配出去的空间将被释放
            // We not even allocated all the number that are
            if (free > 0) {
                free(free);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        // 释放缓存的内存块回 Chunk 中
        private void freeEntry(Entry entry) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            // recycle now so PoolChunk can be GC'ed. 回收 Entry 对象，以便GC
            entry.recycle();

            // 释放缓存的内存块回 Chunk 中
            chunk.arena.freeChunk(chunk, handle, sizeClass, nioBuffer);
        }

        static final class Entry<T> {
            final Handle<Entry<?>> recyclerHandle;
            PoolChunk<T> chunk;
            ByteBuffer nioBuffer;
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };
    }
}
