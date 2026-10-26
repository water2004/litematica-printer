package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Reporting state only: never owns jobs, waits, world reads or scheduling decisions. */
final class ModuleDiagnostics {
    private volatile GuiBlockInfo currentGuiInfo;
    private volatile GuiBlockInfo currentJobGuiInfo;
    private volatile int queuedJobCount;
    private volatile long lastJobActivityTick = Long.MIN_VALUE;
    private volatile long scannedPositions;
    private volatile long totalPositions;

    void reset() {
        queuedJobCount = 0;
        currentJobGuiInfo = null;
        lastJobActivityTick = Long.MIN_VALUE;
        reportProgress(0L, 0L);
    }

    void clearCurrentJob() {
        currentJobGuiInfo = null;
    }

    void reportJob(@Nullable GuiBlockInfo info, long tick) {
        currentJobGuiInfo = info;
        if (info != null) lastJobActivityTick = tick;
    }

    void setQueuedJobCount(int count) {
        queuedJobCount = count;
    }

    void publishCandidate(@Nullable SearchCandidateInfo candidate) {
        if (candidate == null) return;
        BlockState required = candidate.requiredState() != null
                ? candidate.requiredState() : candidate.currentState();
        currentGuiInfo = new GuiBlockInfo(candidate.pos(), candidate.currentState(),
                required, true, false, true);
    }

    void reportProgress(long scanned, long total) {
        scannedPositions = Math.min(scanned, total);
        totalPositions = total;
    }

    @Nullable GuiBlockInfo getGuiInfo() { return currentGuiInfo; }
    @Nullable GuiBlockInfo getCurrentJobGuiInfo() { return currentJobGuiInfo; }
    int getQueuedJobCount() { return queuedJobCount; }
    boolean wasJobActiveOnTick(long tick) { return lastJobActivityTick == tick; }
    long getScannedPositions() { return scannedPositions; }
    long getTotalPositions() { return totalPositions; }

    /** Do not retain a whole tile snapshot just to show one candidate in the HUD. */
    record SearchCandidateInfo(BlockPos pos, BlockState currentState,
                               @Nullable BlockState requiredState) {}
}
