package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public abstract class Module extends ConfigUtils {
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Printer-TimeoutGuard");
                t.setDaemon(true);
                return t;
            });
    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> box;
    protected final IteratorManager iteratorManager = new IteratorManager();
    protected final ScanPlan scanPlan = new ScanPlan();
    protected final BlockJobQueue jobQueue = new BlockJobQueue();
    @Getter
    private final String id;
    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;
    @Getter
    @Nullable
    private final ConfigOptionList selectionType;
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);
    private final AtomicBoolean timeLimitExceeded = new AtomicBoolean(false);
    @Getter
    private final Queue<PendingHighlight> pendingHighlights = new ConcurrentLinkedQueue<>();
    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;
    protected boolean needSchematic = false;
    private long lastTickTime = -1L;
    @Nullable
    private ClientLevel queuedSchedulerLevel = null;
    @Getter
    private ScanState scanState = ScanState.COLLECT;

    private Iterator<BlockPos> processIter = null;

    @Nullable
    private BlockPos waitingPos = null;

    private volatile GuiBlockInfo currentGuiInfo = null;
    private volatile GuiBlockInfo currentJobGuiInfo = null;
    private volatile int queuedJobCount = 0;

    protected Module(String id, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.box = useBox ? new AtomicReference<>() : null;
        updateVariables();
    }

    protected void updateVariables() {
        mc = Minecraft.getInstance();
        level = mc.level;
        player = mc.player;
        connection = mc.getConnection();
        gameMode = mc.gameMode;
        gameType = gameMode == null ? null : gameMode.getPlayerMode();
        hitResult = mc.hitResult;
        blockHitResult = (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
                ? (BlockHitResult) hitResult : null;
    }

    public void tick() {
        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ModuleManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
                return;
            }
            lastTickTime = currentTickTime;
        }

        if (!isConfigAllowed()) {
            pendingHighlights.clear();
            // 开关关闭只暂停调度。保留生产者游标、消费者队列和等待作业，
            // 重新开启后从原状态继续；跨维度时仍会在下方单独重置。
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            return;
        }

        if (usesJobQueue() && queuedSchedulerLevel != level) {
            queuedSchedulerLevel = level;
            resetQueuedScheduler();
            iteratorManager.markNeedsRebuild();
        }

        if (box == null) return;
        if (iteratorManager.tryBuildBox(player, selectionType != null ? selectionType.getOptionListValue() : null)) {
            box.set(iteratorManager.getBox());
            if (usesJobQueue()) {
                // 玩家移动只重置生产者游标；既有作业由消费者按当前范围惰性丢弃。
                if (scanState != ScanState.WAITING) scanState = ScanState.COLLECT;
            } else {
                scanState = ScanState.COLLECT;
                scanPlan.reset();
                processIter = null;
                waitingPos = null;
            }
            iteratorManager.reset();
        }

        preprocess();

        skipIteration.set(false);
        int remainingExecs = Math.max(getMaxExecutions(), 0);
        executeScanPhase(remainingExecs);
    }

    private void executeScanPhase(int maxExecs) {
        if (box == null || !canExecute() || !canIterate()) return;

        long cutoff = System.currentTimeMillis() - Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
        pendingHighlights.removeIf(ph -> ph.time() < cutoff);

        // 远离工作区时提前退出，避免空跑卡顿
        if (needsAreaCheck() && !isPlayerRangeInWorkArea()) return;

        if (usesJobQueue()) {
            executeQueuedPhase(maxExecs);
            return;
        }

        switch (scanState) {
            case COLLECT -> collectPhase(maxExecs);
            case PROCESS -> processPhase(maxExecs);
            case WAITING -> waitingPhase(maxExecs);
        }
    }

    /**
     * 粗筛：玩家可达范围是否与工作区有交集。
     * 投影模式 isSchematicBlock 已够快，无需提前退出；
     * 选区模式用选区边界盒做 O(1) 排空判断。
     */
    private boolean isPlayerRangeInWorkArea() {
        if (needSchematic) return true;
        if (player == null) return false;
        PrinterBox selBounds = LitematicaUtils.getSelectionBounds();
        if (selBounds == null) return false;
        double r = ConfigUtils.getEffectiveRange();
        double px = player.getX(), py = player.getEyeY(), pz = player.getZ();
        return Math.floor(px - r) <= selBounds.maxX && Math.ceil(px + r) >= selBounds.minX
            && Math.floor(py - r) <= selBounds.maxY && Math.ceil(py + r) >= selBounds.minY
            && Math.floor(pz - r) <= selBounds.maxZ && Math.ceil(pz + r) >= selBounds.minZ;
    }

    protected void enterWaiting(@Nullable BlockPos pos) {
        scanState = ScanState.WAITING;
        waitingPos = pos;
    }

    private boolean waitingPhase(int maxExecs) {
        // 恢复：优先处理等待位置，然后继续 PROCESS
        BlockPos pos = waitingPos;
        waitingPos = null;
        scanState = ScanState.PROCESS;
        if (processIter == null) processIter = scanPlan.createFlatIterator();

        if (pos != null && needsWork(pos)) {
            executeAndReturn(pos);
        }
        return true;
    }

    /**
     * 持续作业调度：先消费已有作业，再用剩余时间继续扫描生产。
     * 消费者在判断前已经将坐标移出队列，因此无法处理的坐标不会锁住队首。
     */
    private void executeQueuedPhase(int maxExecs) {
        int timeLimitMs = getIterationTimeLimit();

        skipIteration.set(false);
        timeLimitExceeded.set(false);

        ScheduledFuture<?> timeoutTask = null;
        if (timeLimitMs > 0) {
            timeoutTask = TIMEOUT_SCHEDULER.schedule(
                    () -> timeLimitExceeded.set(true),
                    timeLimitMs, TimeUnit.MILLISECONDS);
        }

        try {
            if (scanState == ScanState.WAITING) {
                BlockPos pos = waitingPos;
                waitingPos = null;
                scanState = ScanState.PROCESS;

                boolean executed = false;
                if (isQueuedPositionValid(pos) && needsQueuedWork(pos)) {
                    executeAndReturn(pos);
                    executed = true;
                }
                updateCurrentJobInfo(pos, executed);

                // 保持旧 waitingPhase 语义：等待位置恢复独占当前 tick，避免突破每 tick 动作上限。
                return;
            }

            scanState = ScanState.PROCESS;
            consumeQueuedJobs(maxExecs);

            if (scanState == ScanState.WAITING
                    || skipIteration.get()
                    || ActionManager.INSTANCE.needWaitModifyLook
                    || timeLimitExceeded.get()) {
                return;
            }

            scanState = ScanState.COLLECT;
            produceQueuedJobs();
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
        }
    }

    private void consumeQueuedJobs(int maxExecs) {
        int execCount = 0;

        while (!timeLimitExceeded.get()
                && !skipIteration.get()
                && !ActionManager.INSTANCE.needWaitModifyLook) {
            BlockPos pos = jobQueue.poll();
            queuedJobCount = jobQueue.size();
            if (pos == null) {
                currentJobGuiInfo = null;
                return;
            }

            boolean executed = false;
            if (isQueuedPositionValid(pos) && needsQueuedWork(pos)) {
                executeAndReturn(pos);
                executed = true;
            }

            updateCurrentJobInfo(pos, executed);

            if (executed && maxExecs > 0 && ++execCount >= maxExecs) return;
            if (scanState == ScanState.WAITING) return;
        }
    }

    private void produceQueuedJobs() {
        try {
            while (!jobQueue.isFull() && !timeLimitExceeded.get()) {
                BlockPos pos = iteratorManager.next();
                if (pos == null) {
                    // 扫描持续循环，但每 tick 最多跨越一次循环边界，避免空区域高速空转。
                    iteratorManager.reset();
                    return;
                }

                if (needsAreaCheck() && !isPosInWorkspace(pos)) continue;

                if (!isOnCooldown(pos) && !isCorrectBlock(pos)) {
                    jobQueue.offer(pos);
                }

                updateGuiInfo(pos, false);
            }
        } finally {
            queuedJobCount = jobQueue.size();
        }
    }

    private boolean isQueuedPositionValid(@Nullable BlockPos pos) {
        if (pos == null) return false;
        if (!PlayerUtils.canInteracted(pos)) return false;
        return !needsAreaCheck() || isPosInWorkspace(pos);
    }

    private void updateGuiInfo(BlockPos pos, boolean executed) {
        currentGuiInfo = createGuiInfo(pos, executed);
    }

    private void updateCurrentJobInfo(@Nullable BlockPos pos, boolean executed) {
        currentJobGuiInfo = pos == null ? null : createGuiInfo(pos, executed);
    }

    private GuiBlockInfo createGuiInfo(BlockPos pos, boolean executed) {
        boolean interacted = PlayerUtils.canInteracted(pos);
        return new GuiBlockInfo(pos,
                level.getBlockState(pos), LitematicaUtils.getBlockState(pos),
                interacted, executed,
                isPosInWorkspace(pos) && interacted);
    }

    private boolean needsWork(BlockPos pos) {
        return !isOnCooldown(pos) && canProcessPos(pos) && !isCorrectBlock(pos);
    }

    private boolean needsQueuedWork(BlockPos pos) {
        return !isCorrectBlock(pos) && !isOnCooldown(pos) && canProcessPos(pos);
    }

    private boolean collectPhase(int maxExecs) {
        return iteratePhase(0,
                iteratorManager::next,
                pos -> needsWork(pos) && collectAndReturn(pos),
                () -> {
                    scanPlan.completeCollection();
                    scanState = ScanState.PROCESS;
                    processIter = null;
                });
    }

    private boolean processPhase(int maxExecs) {
        if (processIter == null) processIter = scanPlan.createFlatIterator();
        return iteratePhase(maxExecs,
                () -> processIter.hasNext() ? processIter.next() : null,
                pos -> needsWork(pos) && executeAndReturn(pos),
                () -> {
                    processIter = null;
                    scanState = ScanState.COLLECT;
                    scanPlan.reset();
                    iteratorManager.reset();
                });
    }

    private boolean collectAndReturn(BlockPos pos) {
        scanPlan.collect(pos, getRequiredItems(pos));
        return true;
    }

    private boolean executeAndReturn(BlockPos pos) {
        executeIteration(pos, skipIteration);
        return true;
    }

    private boolean iteratePhase(int maxExecs, Supplier<@Nullable BlockPos> nextPos,
                                  java.util.function.Predicate<BlockPos> onPosition, Runnable onComplete) {
        int execCount = 0;
        int timeLimitMs = getIterationTimeLimit();

        skipIteration.set(false);
        timeLimitExceeded.set(false);

        ScheduledFuture<?> timeoutTask = null;
        if (timeLimitMs > 0) {
            timeoutTask = TIMEOUT_SCHEDULER.schedule(
                    () -> timeLimitExceeded.set(true),
                    timeLimitMs, TimeUnit.MILLISECONDS);
        }

        try {
            while (true) {
                if (timeLimitExceeded.get()) return true;
                if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) return true;

                BlockPos pos = nextPos.get();
                if (pos == null) { onComplete.run(); return false; }

                if (needsAreaCheck() && !isPosInWorkspace(pos)) continue;

                boolean executed = onPosition.test(pos);
                if (executed) {
                    if (maxExecs > 0 && ++execCount >= maxExecs) return true;
                }

                updateGuiInfo(pos, executed);
            }
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
        }
    }

    private boolean isPosInWorkspace(BlockPos pos) {
        return needSchematic
                ? LitematicaUtils.isSchematicBlock(pos)
                : LitematicaUtils.inSelection(pos);
    }

    @Nullable
    protected Item[] getRequiredItems(BlockPos pos) {
        return null;
    }

    public void resetScanState() {
        scanState = ScanState.COLLECT;
        scanPlan.reset();
        processIter = null;
        waitingPos = null;
        jobQueue.clear();
        queuedJobCount = 0;
        currentJobGuiInfo = null;
        iteratorManager.reset();
    }

    private void resetQueuedScheduler() {
        scanState = ScanState.COLLECT;
        waitingPos = null;
        jobQueue.clear();
        queuedJobCount = 0;
        currentJobGuiInfo = null;
        iteratorManager.reset();
    }

    @Nullable
    public GuiBlockInfo getGuiInfo() {
        return currentGuiInfo;
    }

    public boolean hasQueuedScheduler() {
        return usesJobQueue();
    }

    public int getQueuedJobCount() {
        return queuedJobCount;
    }

    public int getJobQueueCapacity() {
        return BlockJobQueue.CAPACITY;
    }

    public long getProducerScannedPositions() {
        return iteratorManager.getScannedPositions();
    }

    public long getProducerTotalPositions() {
        return iteratorManager.getTotalPositions();
    }

    @Nullable
    public GuiBlockInfo getCurrentJobGuiInfo() {
        return currentJobGuiInfo;
    }

    private boolean isConfigAllowed() {
        if (!ConfigUtils.isPrinterEnable()) return false;
        return enableConfig == null || enableConfig.getBooleanValue();
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxExecutions() {
        return -1;
    }

    protected int getIterationTimeLimit() {
        return Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    protected boolean usesJobQueue() {
        return false;
    }

    public abstract boolean canProcessPos(BlockPos pos);

    public abstract boolean isCorrectBlock(BlockPos pos);

    protected void addHighlight(BlockPos pos, HighlightType type) {
        BlockPos immutable = pos.immutable();
        pendingHighlights.removeIf(ph -> ph.pos().equals(immutable));
        pendingHighlights.add(new PendingHighlight(immutable, System.currentTimeMillis(), type));
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    public boolean isOnCooldown(@Nullable BlockPos pos) {
        if (level == null || pos == null) return true;
        return BlockPosCooldownManager.INSTANCE.isOnCooldown(level, id, pos);
    }

    public void setCooldown(@Nullable BlockPos pos, int ticks) {
        if (level == null || pos == null || ticks < 1) return;
        BlockPosCooldownManager.INSTANCE.setCooldown(level, id, pos, ticks);
    }

    protected Direction getPlayerPlacementDirection() {
        return Direction.orderedByNearest(player)[0].getOpposite();
    }

    protected boolean needsAreaCheck() {
        return true;
    }

    public record PendingHighlight(BlockPos pos, long time, HighlightType type) {
    }
}
