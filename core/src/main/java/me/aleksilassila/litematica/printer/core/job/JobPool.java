package me.aleksilassila.litematica.printer.core.job;

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
import java.util.function.UnaryOperator;

/**
 * 有界、按事务签名分组的异步作业池。
 *
 * <p>该类型只管理生产者与消费者之间的原子所有权转移，不依赖任何 Minecraft 类型。
 * 生产者发布后不再访问桶；消费者接管有限快照桶并消费至空，作业取出后永不放回。</p>
 */
public final class JobPool<P, K> {
    public static final int DEFAULT_CAPACITY = 10_000;

    private final int capacity;
    private final UnaryOperator<P> canonicalizer;
    private final ConcurrentLinkedQueue<PublishedBatch<P, K>> publishedBatches =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<P, Long> queuedPositions =
            new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger bucketCount = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();

    /* 以下两个字段只由单一消费者线程访问。 */
    private PublishedBatch<P, K> activeBatch;
    private BucketSelection<P, K> consumerSelection;

    public JobPool(UnaryOperator<P> canonicalizer) {
        this(DEFAULT_CAPACITY, canonicalizer);
    }

    public JobPool(int capacity, UnaryOperator<P> canonicalizer) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.canonicalizer = canonicalizer;
    }

    public int capacity() {
        return capacity;
    }

    public long generation() {
        return generation.get();
    }

    /** 原子发布一轮搜索结果，返回实际进入池中的作业数。 */
    public int publish(List<Job<P, K>> jobs, long expectedGeneration) {
        if (jobs == null || jobs.isEmpty() || generation.get() != expectedGeneration) {
            return 0;
        }

        Map<K, ArrayDeque<P>> localBuckets = new LinkedHashMap<>();
        List<P> reserved = new ArrayList<>(Math.min(jobs.size(), capacity));

        for (Job<P, K> job : jobs) {
            if (job == null || job.position() == null || job.key() == null) continue;
            if (generation.get() != expectedGeneration) break;

            P position = canonicalizer.apply(job.position());
            if (position == null) continue;
            if (queuedPositions.putIfAbsent(position, expectedGeneration) != null) continue;
            if (!reserveCapacity()) {
                queuedPositions.remove(position, expectedGeneration);
                break;
            }

            if (generation.get() != expectedGeneration) {
                if (queuedPositions.remove(position, expectedGeneration)) {
                    size.updateAndGet(value -> Math.max(0, value - 1));
                }
                break;
            }

            localBuckets.computeIfAbsent(job.key(), ignored -> new ArrayDeque<>())
                    .addLast(position);
            reserved.add(position);
        }

        if (localBuckets.isEmpty()) return 0;

        List<BucketSelection<P, K>> buckets = new ArrayList<>(localBuckets.size());
        for (Map.Entry<K, ArrayDeque<P>> entry : localBuckets.entrySet()) {
            buckets.add(new BucketSelection<>(
                    entry.getKey(), entry.getValue(), expectedGeneration));
        }

        PublishedBatch<P, K> batch =
                new PublishedBatch<>(expectedGeneration, new ArrayDeque<>(buckets));
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

    private void rollbackReservations(long expectedGeneration, List<P> positions) {
        int removed = 0;
        for (P position : positions) {
            if (queuedPositions.remove(position, expectedGeneration)) removed++;
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
            if (current >= capacity) return false;
            if (size.compareAndSet(current, current + 1)) return true;
        }
    }

    /** 获取当前消费者桶；非空桶会跨调用保持，直到被消费完。 */
    public BucketSelection<P, K> currentBucket() {
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

            BucketSelection<P, K> selection = activeBatch.buckets().pollFirst();
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

    /** 从当前消费者桶取出一个作业。取出即消费。 */
    public P pollFromBucket(K key, ArrayDeque<P> bucket) {
        BucketSelection<P, K> selection = consumerSelection;
        if (selection == null
                || !selection.key().equals(key)
                || selection.bucket() != bucket) {
            return null;
        }

        P position = bucket.pollFirst();
        if (position != null
                && queuedPositions.remove(position, selection.generation())) {
            size.updateAndGet(value -> Math.max(0, value - 1));
        }
        if (bucket.isEmpty()) {
            consumerSelection = null;
            decrementBucketCount(1);
        }
        return position;
    }

    private void discardStaleBatch(PublishedBatch<P, K> batch) {
        List<P> stale = new ArrayList<>();
        for (BucketSelection<P, K> selection : batch.buckets()) {
            stale.addAll(selection.bucket());
        }
        rollbackReservations(batch.generation(), stale);
        decrementBucketCount(batch.buckets().size());
    }

    public boolean isFull() {
        return size.get() >= capacity;
    }

    public int size() {
        return size.get();
    }

    public int bucketCount() {
        return bucketCount.get();
    }

    /** 进入新一代并丢弃所有已发布和正在消费的旧作业。 */
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

    public boolean contains(P position) {
        return position != null && queuedPositions.containsKey(position);
    }

    /** 弱一致调试快照。 */
    public Iterator<ArrayDeque<P>> bucketIterator() {
        List<ArrayDeque<P>> snapshot = new ArrayList<>();
        if (consumerSelection != null) snapshot.add(consumerSelection.bucket());
        if (activeBatch != null) {
            for (BucketSelection<P, K> selection : activeBatch.buckets()) {
                snapshot.add(selection.bucket());
            }
        }
        for (PublishedBatch<P, K> batch : publishedBatches) {
            for (BucketSelection<P, K> selection : batch.buckets()) {
                snapshot.add(selection.bucket());
            }
        }
        return snapshot.iterator();
    }

    public Iterator<P> flatIterator() {
        Deque<P> all = new ArrayDeque<>(size());
        Iterator<ArrayDeque<P>> buckets = bucketIterator();
        while (buckets.hasNext()) all.addAll(buckets.next());
        return all.iterator();
    }

    public record Job<P, K>(P position, K key) {
    }

    public record BucketSelection<P, K>(
            K key,
            ArrayDeque<P> bucket,
            long generation) {
    }

    private record PublishedBatch<P, K>(
            long generation,
            ArrayDeque<BucketSelection<P, K>> buckets) {
    }
}
