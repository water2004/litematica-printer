package me.aleksilassila.litematica.printer.core.concurrent;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单调度线程、多工作线程的通用异步轮次执行器。
 *
 * <p>调度线程负责建立任务、捕获任务输入和接收完成结果。工作线程只处理已经捕获的
 * 不可变输入。任一时刻最多提交 {@code maxWorkers} 个任务；上一轮完成前拒绝新轮次。</p>
 */
public final class AsyncRoundCoordinator {
    private final String threadPrefix;
    private final ExecutorService scheduler;
    private final AtomicBoolean busy = new AtomicBoolean();

    private ExecutorService workers;
    private int workerCount;

    public AsyncRoundCoordinator(String threadPrefix) {
        this.threadPrefix = threadPrefix;
        this.scheduler = Executors.newSingleThreadExecutor(
                namedDaemonFactory(threadPrefix + "-Scheduler"));
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** 上一轮仍忙时直接返回 false，不排队。 */
    public <W, S, R> boolean tryStartRound(int maxWorkers, Round<W, S, R> round) {
        if (round == null || !busy.compareAndSet(false, true)) return false;
        int effectiveWorkers = Math.max(1, maxWorkers);
        scheduler.execute(() -> runRound(effectiveWorkers, round));
        return true;
    }

    private <W, S, R> void runRound(int maxWorkers, Round<W, S, R> round) {
        try {
            ensureWorkerPool(maxWorkers);
            ExecutorService currentWorkers = workers;
            if (currentWorkers == null) return;

            List<W> prepared = round.prepare();
            ArrayDeque<W> pending = new ArrayDeque<>(prepared == null ? List.of() : prepared);
            CompletionService<Completed<W, R>> completions =
                    new ExecutorCompletionService<>(currentWorkers);
            int inFlight = 0;

            while (!pending.isEmpty() || inFlight > 0) {
                while (inFlight < maxWorkers && !pending.isEmpty()) {
                    W work = pending.removeFirst();
                    S snapshot;
                    try {
                        snapshot = round.capture(work);
                    } catch (Throwable throwable) {
                        round.captureFailed(work, throwable);
                        continue;
                    }

                    S submittedSnapshot = snapshot;
                    completions.submit(() -> {
                        try {
                            return new Completed<>(work, round.search(submittedSnapshot));
                        } catch (Throwable throwable) {
                            return new Completed<>(
                                    work,
                                    round.searchFailed(work, submittedSnapshot, throwable));
                        }
                    });
                    inFlight++;
                }

                if (inFlight == 0) break;
                Future<Completed<W, R>> future = completions.take();
                Completed<W, R> completed = future.get();
                inFlight--;
                round.completed(completed.work(), completed.result());
            }

            round.finish();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            round.aborted(exception);
        } catch (Throwable throwable) {
            round.aborted(throwable);
        } finally {
            busy.set(false);
        }
    }

    private void ensureWorkerPool(int desiredCount) {
        if (workers != null && workerCount == desiredCount) return;
        if (workers != null) workers.shutdown();
        workerCount = desiredCount;
        workers = Executors.newFixedThreadPool(
                desiredCount,
                namedDaemonFactory(threadPrefix + "-Worker"));
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record Completed<W, R>(W work, R result) {
    }

    public interface Round<W, S, R> {
        /** 在调度线程建立本轮唯一任务列表。 */
        List<W> prepare();

        /** 在调度线程捕获单个任务输入。 */
        S capture(W work);

        /** 在工作线程中只处理已捕获输入。 */
        R search(S snapshot);

        /** 捕获失败发生在调度线程。实现应记录失败并推进自己的进度。 */
        void captureFailed(W work, Throwable throwable);

        /** 搜索失败发生在工作线程。返回用于完成回调的降级结果。 */
        R searchFailed(W work, S snapshot, Throwable throwable);

        /** 在调度线程接收一个完成结果。 */
        void completed(W work, R result);

        /** 所有任务完成后在调度线程合并发布。 */
        void finish();

        /** 整轮无法继续时的最终回调。 */
        void aborted(Throwable throwable);
    }
}
