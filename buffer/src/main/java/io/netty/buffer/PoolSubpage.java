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

final class PoolSubpage<T> implements PoolSubpageMetric {


    /**
     * Subpage 分配信息数组，数组长度默认为：8
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。(1个long占用8字节，即64位)
     * 默认最小的Subpage=16B，pageSize=8KB,那么：8KB / 16B = 512个，单个Page可能最多分成512个SubPage
     * 一个long可以表示64位，那么长度为8的long数组，会有：8 * 64 = 512位。即满足Subpage个数最多的情况
     */
    /**
     * Subpage 分配信息数组，数组长度默认为：8
     * <p>
     * 每个 long 的 bits 位代表一个 Subpage 是否分配。(1个long占用8字节，即64位)
     * 因为 PoolSubpage 可能会超过 64 个( long 的 bits 位数 )，所以使用数组。
     * 例如：Page 默认大小为 8KB ，Subpage 默认最小为 16 B ，所以一个 Page 最多可包含 8 * 1024 / 16 = 512 个 Subpage 。
     * 因此，bitmap 数组大小固定为 512 / 64 = 8 。
     * 另外，bitmap 的数组大小固定为8，但是使用 {@link #bitmapLength} 来标记真正使用的数组大小。或者说，bitmap 数组，默认按照 Subpage 的大小为 16B 来初始化。
     * 为什么是这样的设定呢？因为 PoolSubpage 可重用，通过 {@link #init(PoolSubpage, int)} 进行重新初始化。
     */
    private final long[] bitmap;
    private int bitmapLength;   // bitmap 数组的真正使用的数组大小。
    final PoolChunk<T> chunk; // 所属 PoolChunk 对象
    private final int memoryMapIdx; //  分配信息满二叉树中的节点编号
    private final int runOffset;    //  在 Chunk 中，偏移字节量
    private final int pageSize;     // Page 大小，PoolSubpageMetric#pageSize

    PoolSubpage<T> prev;    // 双向链表，前一个 PoolSubpage 对象
    PoolSubpage<T> next;    // 双向链表，后一个 PoolSubpage 对象

    boolean doNotDestroy;   // 是否未销毁
    int elemSize;           // 每个 Subpage 的占用内存大小，PoolSubpageMetric#elementSize
    private int maxNumElems;    // Subpage的数量总合，PoolSubpageMetric#maxNumElements
    private int nextAvail;      // 下一个可分配 Subpage 的数组位置
    private int numAvail;       // 剩余可用 Subpage 的数量，PoolSubpageMetric#numAvailable

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * PoolChunk#allocateSubpage(int)中调用
     * 默认：pageSize=8K=2^13
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        // 16代表最小的subPage为16B； 64代表一个long占用8字节=64位
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64 = pageSize / 2^10
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            // 首次初始化，最大Subpage数量=当前可用数量
            maxNumElems = numAvail = pageSize / elemSize;
            // 下一个可分配位置，从0开始
            nextAvail = 0;
            // 计算 bitmapLength 的大小（maxNumElems/64）
            bitmapLength = maxNumElems >>> 6;
            // 如果不是64的整数倍，再+1
            if ((maxNumElems & 63) != 0) {
                bitmapLength++;
            }
            // 初始化bitmap数组，
            for (int i = 0; i < bitmapLength; i++) {
                bitmap[i] = 0;
            }
        }
        // 将本对象节点，插入到head节点之后
        addToPool(head);
    }

    /**
     * 返回subpage分配的位图索引。
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        // 可用数量为 0 ，或者已销毁，返回 -1 ，即不可分配。
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        // 获得下一个可用的 Subpage 在 bitmap 中的总体位置
        final int bitmapIdx = getNextAvail();
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置
        int q = bitmapIdx >>> 6;
        // 获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        // 修改 Subpage 在 bitmap 中不可分配。
        bitmap[q] |= 1L << r;

        // 剩余可用 Subpage 的数量
        if (--numAvail == 0) {
            // 若减完后为0，代表无可用 Subpage 内存块。从双向链表中移除
            removeFromPool();
        }

        // 计算 handle（bitmap index of the subpage allocation）
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * 将本对象节点，插入到head节点之后，head.next之前
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    /**
     * 将本节点，从双向链表中移除
     */
    private void removeFromPool() {
        assert prev != null && next != null;
        //  前后节点，互相指向
        prev.next = next;
        next.prev = prev;
        // 当前节点置空
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        // nextAvail 大于 0 ，意味着已经“缓存”好下一个可用的位置，直接返回即可。
        if (nextAvail >= 0) {
            // 置为-1，下次重新寻找
            this.nextAvail = -1;
            return nextAvail;
        }

        // 寻找下一个 nextAvail
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        // 循环 bitmap
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            // 只有bit的每一位全为1，~bits才为0，代表：都已分配完
            if (~bits != 0) {
                // 在这64个 bit 中寻找可用 nextAvail
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    /**
     * @param i 代表在bitmap数组中，第i个数组元素上
     * @param bits 当前数组元素long的64bit上，需要寻找出nextAvail
     * @return 下一个nextAvail位置，所在bit位置数值
     */
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        // 计算基础值，表示在 bitmap 的数组下标。相当于i*64
        final int baseVal = i << 6; // 左移6位，6位最大表示0-63，后面用来计算：baseVal | j 等同于：baseVal + j

        for (int j = 0; j < 64; j++) {
            // 计算当前 bit 是否未分配
            if ((bits & 1) == 0) {// 未分配
                // bitmap 中最后一个元素，可能并未满 64 位，通过 baseVal | j < maxNumElems 来保证不超过上限。
                int val = baseVal | j; // 由于baseVal是左移6位得到的值，低6位为0，(baseVal | j) = baseVal+j
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
