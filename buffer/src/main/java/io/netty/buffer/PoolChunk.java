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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 从PoolChunk分配PageRun/PoolSubpage的算法描述
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 * <p>
 * Notation: The following terms术语 are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated chunk 可分配的最小内存单位
 * > chunk - a chunk is a collection of pages  chunk就是一系列的page
 * > in this code chunkSize = 2^{maxOrder} * pageSize  DEFAULT_MAX_ORDER; // 满二叉树的高度，默认为 11
 * <p>
 * 首先，我们分配一个字节数组size = chunkSize。
 * 每当一个大小ByteBuf的需要分配空间，从字节数组中寻找到第一个 拥有足够空间来满足申请容量 的位置，并返回一个请求的大小(长)处理编码这个offset信息
 * (这个内存段标记为保留，所以总是有且只能由一个ByteBuf使用)
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 * <p>
 * 为了简单起见，所有大小都按照PoolArena#normalizeCapacity方法进行规范化。
 * 这可以保证:当申请空间>=分页的内存段时，normalizedCapacity为大于它的最近一个2的幂次
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 * <p>
 * 从字节数组中寻找到第一个 拥有足够空间来满足申请容量 的偏移量，我们构造了一个完整的平衡二叉树并将其存储在数组中(像堆一样)—memoryMap
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 * <p>
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 * 树的结构如下(括号中提到的每个节点的大小)：
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 * <p>
 * depth=maxOrder is the last level and the leafs consist of pages
 * depth=maxOrder（默认为：11）是最后一层，叶子节点由page（默认：pageSize=8KB）组成
 * chunkSize默认为：8KB * 2^11=16MB
 * <p>
 * 有了这棵可用的树，搜索chunkArray方式如下: 为了分配大小为chunkSize/2^k的内存段，我们在高度k处搜索第一未被使用个节点(从左开始)
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 * <p>
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation  在memoryMap中使用符号对树进行编码
 * - x代表：以id为根的子树中，第一个可供分配的节点位于深度x处(从depth=0开始计算)。即在 >=depth_of_id 且 < x的区间内，没有空闲节点
 * memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 * is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 * <p>
 * - 在分配和释放节点时，我们更新存储在memoryMap中的值，以便维护该属性
 * As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 * <p>
 * 在开始时，我们通过在每个节点上存储一个节点的深度来构造memoryMap数组，如：memoryMap[id] = depth_of_id
 * Initialization -
 * In the beginning we construct the memoryMap array by storing the depth of a node at each node
 * i.e., memoryMap[id] = depth_of_id
 * <p>
 * Observations（观察结果）:
 * -------------
 * - memoryMap[id] = depth_of_id 说明：还未分配，可使用
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * - 大于时，说明：至少分配了它的一个子节点，因此我们不能分配它。但仍然可以根据其可用性分配它的一些子节点（比如下次有小点儿的内存申请）
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 * some of its children can still be allocated based on their availability
 * - memoryMap[id] = maxOrder + 1时，说明：节点被完全分配，因此不能分配它的任何子节点，因此它被标记为不可用
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 * is thus marked as unusable
 * <p>
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 * <p>
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 * <p>
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 * note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 * <p>
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 * <p>
 * - 以id为根的子树中，第一个可供分配的节点位于深度x处(从depth=0开始计算)。即在 >=depth_of_id 且 < x的区间内，没有空闲节点
 * memoryMap[id]= depth_of_id  is defined above
 * - 指示可自由分配的第一个节点位于深度x处(从根开始)
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * Chunk 是 Netty 对操作系统进行内存申请的单位，后续所有的内存分配都是在 Chunk 里面进行操作
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;// 32-1=31

    final PoolArena<T> arena; // 所属 Arena 对象
    final T memory;     // 内存空间
    final boolean unpooled; // 是否池化
    final int offset;
    private final byte[] memoryMap; // 分配信息满二叉树，index 为节点编号,默认长度为：4096
    private final byte[] depthMap;  // 高度信息满二叉树，index 为节点编号,默认长度为：4096
    private final PoolSubpage<T>[] subpages; // PoolSubpage 数组, 默认长度为：2048 (2048个叶子节点，每个节点的 Page 可都作为 Subpage 分配)。
    /**
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     */
    private final int subpageOverflowMask; // 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
    private final int pageSize; // Page 大小，默认 8KB = 8192B
    private final int pageShifts; // 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192
    private final int maxOrder; // 默认为 11。满二叉树的高度（从0开始，所以树高12）。。
    private final int chunkSize; // Chunk 内存块占用大小。默认为 16M = 8KB * 2^11
    private final int log2ChunkSize; // log2 {@link #chunkSize} 的结果。默认为 log2( 16M ) = 24 。
    private final int maxSubpageAllocs; // 可分配 {@link #subpages} 的数量，即数组大小。默认为 1 << maxOrder = 1 << 11 = 2048
    /**
     * Used to mark memory as unusable
     */
    private final byte unusable; // 标记节点不可用。 默认：11+1=12

    // 用作从内存中创建的字节缓冲区的缓存，这些仅是复制品的，所以只是内存本身的一个容器。
    // 这些通常是Pooled池化ByteBuf中的操作所需要的，因此可能会产生额外的GC，通过缓存副本可以大大减少GC。
    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // 如果PoolChunk是非池化的，这个值可能为空，因为池化ByteBuffer实例在这里没有任何意义。
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    private int freeBytes; // 剩余可用字节数

    PoolChunkList<T> parent; // 所属 PoolChunkList 对象
    PoolChunk<T> prev;  // 上一个 Chunk 对象
    PoolChunk<T> next;  // 下一个 Chunk 对象

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false; // 池化
        this.arena = arena;
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1); // ～（2^13-1）= -8192; 低13位都是0，第14位是1。为什么不直接用 -pageSize
        freeBytes = chunkSize;

        // maxOrder默认为11
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;  // 1<<11 = 2^11 = 2048

        // 满二叉树的高度（从0开始，要+1）为：12。最底层叶子结点2^11=2048,整棵树共有节点数：2^12-1=4095.数组下标为0的位置，不存数据
        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1]; // new byte[2048*2];
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex++;
            }
        }

        // 初始化 subpages pool
        subpages = newSubpageArray(maxSubpageAllocs);
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /**
     * 创建非池化的chunk，huge内存块使用，用完就回收释放
     * Creates a special chunk that is not pooled.
     */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;// 非池化
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }


    /**
     * 分配内存空间
     * subpageOverflowMask = ~(pageSize - 1); // ~(2^13-1) = 低13位都是0，高位全是1；
     * subpageOverflowMask = 11111111111111111110000000000000
     * normCapacity & subpageOverflowMask != 0时，说明14-31位存在1，即：normCapacity >= 2^13
     * <p>
     * 上述算法，相当于 ( length < pageSize ) 使用位运算的计算优化
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize(8192)
            // 大于等于8KB时，为Normal，分配 Page 内存块
            handle = allocateRun(normCapacity);
        } else {
            // 小于8KB时，为Tiny/Small，分配 Subpage 内存块
            handle = allocateSubpage(normCapacity);
        }

        if (handle < 0) {
            return false;
        }
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        //  初始化 Normal Page / Subpage 内存块到 PooledByteBuf 对象中。
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * 分配时使用的更新方法。（将已分配过二叉树节点，设置标记）
     * 只有在分配了一个继任者（子节点）并且它的所有前任（父节点）都需要更新它们的状态时才会触发
     * 以id为根的子树有空闲空间的最小深度
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1); // 偶数^1 = 偶数+1； 奇数^1 = 奇数-1；相当于找到二叉树中，某节点的兄弟节点
            byte val = val1 < val2 ? val1 : val2; // 两个兄弟节点在memoryMap中的值，谁小取谁
            setValue(parentId, val); // memoryMap[id] = val
            id = parentId;
        }
    }

    /**
     * 释放内存时，使用的更新方法
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        // 获得当前节点的子节点的层级
        int logChild = depth(id) + 1;
        while (id > 1) {
            // 获得父节点的编号
            int parentId = id >>> 1;
            // 获得子节点的值
            byte val1 = value(id);
            // 获得另外一个子节点的值
            byte val2 = value(id ^ 1);
            // 在第一次迭代中等于log，然后在我们向上遍历时从logChild减少1
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            // 两个子节点都可用，则直接设置父节点的层级
            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                // 两个子节点任一不可用，则取子节点较小值，并设置到父节点
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }


    /**
     * 当我们查询深度d的空闲节点时，在memoryMap中分配索引的算法
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth 树的高度
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        // 如：11111111111111111111100000000000  =-2048
        int initial = -(1 << d); // has last d bits = 0 and rest all = 1 最后的d位全是0，剩下的高位全是1
        byte val = value(id);
        // 如果根节点的值，大于 d ，说明，第 d 层没有符合的节点，也就是说 [0, d-1] 层也没有符合的节点。即，当前 Chunk 没有符合的节点。
        if (val > d) { // unusable
            return -1;
        }

        // 获得第 d 层，匹配的节点。
        // id & initial 来保证，高度小于 d 会继续循环
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {
                id ^= 1;
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable 标记为不可用
        updateParentsAlloc(id); // 更新父节点中的标记状态
        return id;
    }

    /**
     * 分配 Page 内存块（大于8KB时使用，为Normal）
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        // 在树中的层级
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        // 获得节点
        int id = allocateNode(d);
        if (id < 0) {
            // 未获得到节点，直接返回
            return id;
        }
        // 减少剩余可用字节数
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * 分配 SubPage 内存块（小于8KB时使用，为Tiny/Small）
     * 创建或初始化一个池化的SubPage，任何池化的SubPage被创建或初始化时，都要加到拥有这个PoolChunk的PoolArena中的pool中
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // 获得对应内存规格的 Subpage 双向链表的 head 节点，并进行synchronize同步。
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        // 获得最底层的一个节点。Subpage 只能使用二叉树的最底层的节点。
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        // 加锁，分配过程会修改双向链表的结构，会存在多线程的情况。
        synchronized (head) {
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            //  全局 PoolSubpage 数组
            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;// 减去已用字节数

            // 获得节点对应的 subpages 数组的编号
            int subpageIdx = subpageIdx(id);
            // 获得节点对应的 subpages 数组的 PoolSubpage 对象
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                // 不存在，则进行创建 PoolSubpage 对象
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                /*
                 * 存在，则重新初始化 PoolSubpage 对象
                 * subpage初始化后分配了内存，但一段时间后该subpage分配的内存释放并从arena的双向链表中删除，此时subpage不为null，
                 * 当再次请求分配时，只需要调用init()将其加入到areana的双向链表中即可。
                 */
                subpage.init(head, normCapacity);
            }
            // 分配 PoolSubpage 内存块
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 初始化分配的内存块到 PooledByteBuf
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        // 获得 memoryMap 数组的编号( 下标 )
        int memoryMapIdx = memoryMapIdx(handle);
        // 获得 bitmap 数组的编号( 下标 )。注意，此时获得的还不是真正的 bitmapIdx 值，需要经过 `bitmapIdx & 0x3FFFFFFF` 运算。
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx == 0) {
            // 内存块为 Page
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());

        } else {
            // 内存块为 SubPage
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    // 初始化 SubPage 内存块到 PooledByteBuf 中
    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        // 获得 memoryMap 数组的编号(下标)
        int memoryMapIdx = memoryMapIdx(handle);

        /**
         * 获得 SubPage 对象。
         * subpages 共2048个元素，分别代表是叶子节点中哪个 Page 节点形成的 Subpage。
         * 所以得到对应 SubPage 的头节点
         */
        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        // 初始化 SubPage 内存块到 PooledByteBuf 中
        // runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset用来计算 SubPage 内存块在 memory 中的开始位置
        buf.init(
                this, nioBuffer, handle,
                runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // 表示树中的节点“id”支持的大小(以byte为单位)
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }


    /**
     * 去掉高位bit，获取得到的是offset偏移量
     * 默认：树高11（从0开始），最下层叶子结点的序号是从2048->4095
     * 那么在此场景下，memoryMapIdx ^ maxSubpageAllocs 就等同于 memoryMapIdx-maxSubpageAllocs
     */
    private int subpageIdx(int memoryMapIdx) {
        // 默认：maxSubpageAllocs = 2^12
        // 1000 0000 0000   =2048
        // 1000 0000 0001   =2049
        // ------ ^操作 ------
        // 0000 0000 0001   =1 = 2049-2048
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
