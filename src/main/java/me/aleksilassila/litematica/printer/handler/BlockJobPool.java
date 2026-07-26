package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界、按事务签名分组的方块作业池。
 *
 * <p>每个桶按 {@link TransactionKey} 聚合同类事务的坐标，组间按首次入队顺序 FIFO，
 * 组内按坐标扫描顺序排列。消费时整桶取出连续执行，减少物品切换与重复判定。</p>
 *
 * <p>该作业池只在客户端主线程访问。</p>
 */
public final class BlockJobPool {
    public static final int CAPACITY = 10_000;

    private final LinkedHashMap<TransactionKey, ArrayDeque<BlockPos>> buckets = new LinkedHashMap<>();
    private int size = 0;

    public boolean offer(BlockPos pos, TransactionKey key) {
        if (pos == null || isFull()) return false;
        BlockPos immutable = pos.immutable();
        ArrayDeque<BlockPos> bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        if (bucket.add(immutable)) {
            size++;
            return true;
        }
        return false;
    }

    /**
     * 取最早的桶（首次入队顺序），不移除。空桶在 poll/peek 时被惰性清理。
     */
    @Nullable
    public Map.Entry<TransactionKey, ArrayDeque<BlockPos>> peekFirstBucket() {
        Iterator<Map.Entry<TransactionKey, ArrayDeque<BlockPos>>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TransactionKey, ArrayDeque<BlockPos>> entry = it.next();
            ArrayDeque<BlockPos> bucket = entry.getValue();
            if (bucket.isEmpty()) {
                it.remove();
                continue;
            }
            return entry;
        }
        return null;
    }

    /**
     * 从指定桶取出并移除队首坐标，同步维护总计数与空桶清理。
     * 调用方不应再直接操作返回的 bucket。
     */
    @Nullable
    public BlockPos pollFromBucket(ArrayDeque<BlockPos> bucket) {
        BlockPos pos = bucket.poll();
        if (pos != null) {
            size--;
            if (bucket.isEmpty()) {
                buckets.values().remove(bucket);
            }
        }
        return pos;
    }

    public boolean isFull() {
        return size >= CAPACITY;
    }

    public int size() {
        return size;
    }

    public int bucketCount() {
        return buckets.size();
    }

    public void clear() {
        buckets.clear();
        size = 0;
    }

    /**
     * 提供给生产侧判重：该坐标是否已存在于任意桶中。
     * 仅在入队前的粗筛需要时使用，O(桶数)。
     */
    public boolean contains(BlockPos pos) {
        for (ArrayDeque<BlockPos> bucket : buckets.values()) {
            if (bucket.contains(pos)) return true;
        }
        return false;
    }

    /**
     * 调试/统计：按桶迭代。返回的迭代器不保证桶非空。
     */
    public Iterator<ArrayDeque<BlockPos>> bucketIterator() {
        return buckets.values().iterator();
    }

    /**
     * 调试/统计：所有坐标的扁平迭代。O(n) 拷贝。
     */
    public Iterator<BlockPos> flatIterator() {
        Deque<BlockPos> all = new ArrayDeque<>(size);
        for (ArrayDeque<BlockPos> bucket : buckets.values()) {
            all.addAll(bucket);
        }
        return all.iterator();
    }
}
