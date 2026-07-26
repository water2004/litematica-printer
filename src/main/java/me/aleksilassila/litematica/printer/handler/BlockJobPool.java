package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界、按事务签名分组的异步作业池。
 *
 * <p>生产者先在线程私有数据中完成整轮结果合并，再把不可增长的批次发布到并发队列。
 * 消费者只接管批次中的桶并跨 tick 消费至空，不会把桶放回生产者一侧。
 * 因此双方从不同时读写同一个桶，也不需要让客户端主线程等待搜索线程持锁。</p>
 */
public final class BlockJobPool {
    public static final int CAPACITY = 10_000;

    private final ConcurrentLinkedQueue<PublishedBatch> publishedBatches =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<BlockPos, Long> queuedPositions =
            new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger bucketCount = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();

    /*
     * 以下两个字段只由客户端主线程访问。生产者永远看不到已经发布出去的桶对象。
     */
    @Nullable
    private PublishedBatch activeBatch;
    @Nullable
    private BucketSelection consumerSelection;

    public long generation() {
        return generation.get();
    }

    /**
     * 原子发布一轮搜索结果。返回实际进入作业池的坐标数。
     *
     * <p>结果先在本地按事务键分桶；只有生成号仍有效且坐标成功占用容量时才进入批次。
     * 发布后的桶只属于消费者，不会再被生产者追加。</p>
     */
    public int publish(List<Job> jobs, long expectedGeneration) {
        if (jobs == null || jobs.isEmpty() || generation.get() != expectedGeneration) {
            return 0;
        }

        Map<TransactionKey, ArrayDeque<BlockPos>> localBuckets = new LinkedHashMap<>();
        List<BlockPos> reserved = new ArrayList<>(Math.min(jobs.size(), CAPACITY));

        for (Job job : jobs) {
            if (job == null || job.pos() == null || job.key() == null) continue;
            if (generation.get() != expectedGeneration) break;

            BlockPos pos = job.pos().immutable();
            if (queuedPositions.putIfAbsent(pos, expectedGeneration) != null) continue;
            if (!reserveCapacity()) {
                queuedPositions.remove(pos, expectedGeneration);
                break;
            }

            // clear() 可能恰好发生在容量占用之后；使用带值 remove 避免删掉新一代作业。
            if (generation.get() != expectedGeneration) {
                if (queuedPositions.remove(pos, expectedGeneration)) {
                    size.updateAndGet(value -> Math.max(0, value - 1));
                }
                break;
            }

            localBuckets.computeIfAbsent(job.key(), ignored -> new ArrayDeque<>())
                    .addLast(pos);
            reserved.add(pos);
        }

        if (localBuckets.isEmpty()) return 0;

        List<BucketSelection> buckets = new ArrayList<>(localBuckets.size());
        for (Map.Entry<TransactionKey, ArrayDeque<BlockPos>> entry : localBuckets.entrySet()) {
            buckets.add(new BucketSelection(
                    entry.getKey(), entry.getValue(), expectedGeneration));
        }

        PublishedBatch batch =
                new PublishedBatch(expectedGeneration, new ArrayDeque<>(buckets));
        synchronized (lifecycleLock) {
            if (generation.get() != expectedGeneration) {
                rollbackReservations(expectedGeneration, reserved);
                return 0;
            }
            publishedBatches.offer(batch);
            bucketCount.addAndGet(buckets.size());
        }
        return reserved.size();
    }

    private void rollbackReservations(long expectedGeneration, List<BlockPos> positions) {
        int removed = 0;
        for (BlockPos pos : positions) {
            if (queuedPositions.remove(pos, expectedGeneration)) removed++;
        }
        if (removed > 0) {
            int decrement = removed;
            size.updateAndGet(value -> Math.max(0, value - decrement));
        }
    }

    private void decrementBucketCount(int count) {
        if (count <= 0) return;
        bucketCount.updateAndGet(value -> Math.max(0, value - count));
    }

    private boolean reserveCapacity() {
        while (true) {
            int current = size.get();
            if (current >= CAPACITY) return false;
            if (size.compareAndSet(current, current + 1)) return true;
        }
    }

    /**
     * 获取消费者当前快照桶；当前桶未空时跨 tick 保持不变。
     */
    @Nullable
    public BucketSelection currentBucket() {
        if (consumerSelection != null) return consumerSelection;

        long currentGeneration = generation.get();
        while (true) {
            if (activeBatch == null || activeBatch.buckets().isEmpty()) {
                activeBatch = publishedBatches.poll();
                if (activeBatch == null) return null;
                if (activeBatch.generation() != currentGeneration) {
                    discardStaleBatch(activeBatch);
                    activeBatch = null;
                    continue;
                }
            }

            BucketSelection selection = activeBatch.buckets().pollFirst();
            if (selection == null) {
                activeBatch = null;
                continue;
            }
            if (selection.bucket().isEmpty()) {
                decrementBucketCount(1);
                continue;
            }
            consumerSelection = selection;
            return selection;
        }
    }

    /**
     * 从消费者快照桶取出一个坐标。取出即消费，动作结果不会使它返回桶中。
     */
    @Nullable
    public BlockPos pollFromBucket(TransactionKey key, ArrayDeque<BlockPos> bucket) {
        BucketSelection selection = consumerSelection;
        if (selection == null
                || !selection.key().equals(key)
                || selection.bucket() != bucket) {
            return null;
        }

        BlockPos pos = bucket.pollFirst();
        if (pos != null
                && queuedPositions.remove(pos, selection.generation())) {
            size.updateAndGet(value -> Math.max(0, value - 1));
        }
        if (bucket.isEmpty()) {
            consumerSelection = null;
            decrementBucketCount(1);
        }
        return pos;
    }

    private void discardStaleBatch(PublishedBatch batch) {
        List<BlockPos> stale = new ArrayList<>();
        for (BucketSelection selection : batch.buckets()) {
            stale.addAll(selection.bucket());
        }
        rollbackReservations(batch.generation(), stale);
        decrementBucketCount(batch.buckets().size());
    }

    public boolean isFull() {
        return size.get() >= CAPACITY;
    }

    public int size() {
        return size.get();
    }

    public int bucketCount() {
        return bucketCount.get();
    }

    /**
     * 进入新一代并丢弃所有已发布、待发布和正在消费的旧作业。
     */
    public void clear() {
        synchronized (lifecycleLock) {
            generation.incrementAndGet();
            publishedBatches.clear();
            queuedPositions.clear();
            size.set(0);
            bucketCount.set(0);
            activeBatch = null;
            consumerSelection = null;
        }
    }

    public boolean contains(BlockPos pos) {
        return pos != null && queuedPositions.containsKey(pos);
    }

    /**
     * 调试快照；调用方位于客户端主线程，发布队列的迭代为弱一致读取。
     */
    public Iterator<ArrayDeque<BlockPos>> bucketIterator() {
        List<ArrayDeque<BlockPos>> snapshot = new ArrayList<>();
        if (consumerSelection != null) snapshot.add(consumerSelection.bucket());
        if (activeBatch != null) {
            for (BucketSelection selection : activeBatch.buckets()) {
                snapshot.add(selection.bucket());
            }
        }
        for (PublishedBatch batch : publishedBatches) {
            for (BucketSelection selection : batch.buckets()) {
                snapshot.add(selection.bucket());
            }
        }
        return snapshot.iterator();
    }

    public Iterator<BlockPos> flatIterator() {
        Deque<BlockPos> all = new ArrayDeque<>(size());
        Iterator<ArrayDeque<BlockPos>> buckets = bucketIterator();
        while (buckets.hasNext()) all.addAll(buckets.next());
        return all.iterator();
    }

    public record Job(BlockPos pos, TransactionKey key) {
    }

    public record BucketSelection(
            TransactionKey key,
            ArrayDeque<BlockPos> bucket,
            long generation) {
    }

    private record PublishedBatch(
            long generation,
            ArrayDeque<BucketSelection> buckets) {
    }
}
