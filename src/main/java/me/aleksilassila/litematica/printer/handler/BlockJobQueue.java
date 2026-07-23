package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 有界、按坐标去重的 FIFO 方块作业队列。
 *
 * <p>该队列只在客户端主线程中由扫描生产者和执行消费者访问，
 * 因此不需要并发容器。{@link ArrayDeque} 本身使用循环数组实现。</p>
 */
public final class BlockJobQueue {
    public static final int CAPACITY = 10_000;

    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>(CAPACITY);
    private final Set<BlockPos> queuedPositions = new HashSet<>(CAPACITY);

    public boolean offer(BlockPos pos) {
        if (pos == null || isFull()) return false;

        BlockPos immutablePos = pos.immutable();
        if (!queuedPositions.add(immutablePos)) return false;

        queue.addLast(immutablePos);
        return true;
    }

    @Nullable
    public BlockPos poll() {
        BlockPos pos = queue.pollFirst();
        if (pos != null) queuedPositions.remove(pos);
        return pos;
    }

    public boolean isFull() {
        return queue.size() >= CAPACITY;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
        queuedPositions.clear();
    }
}
