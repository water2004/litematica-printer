package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.printer.*;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    protected final BlockJobPool jobPool = new BlockJobPool();
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

        if (usesJobPool() && queuedSchedulerLevel != level) {
            queuedSchedulerLevel = level;
            resetScheduler();
            iteratorManager.markNeedsRebuild();
        }

        if (box == null) return;
        if (iteratorManager.tryBuildBox(player, selectionType != null ? selectionType.getOptionListValue() : null)) {
            box.set(iteratorManager.getBox());
            if (usesJobPool()) {
                // 玩家移动只重置生产者游标；既有作业由消费者按当前范围惰性丢弃。
                if (scanState != ScanState.WAITING) scanState = ScanState.COLLECT;
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

        if (usesJobPool()) executePooledPhase(maxExecs);
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

    /**
     * 持续作业调度：先消费已有作业，再用剩余时间继续扫描生产。
     * 消费者以最早作业为锚点向后选择执行兼容作业，不再使用严格 FIFO。
     */
    private void executePooledPhase(int maxExecs) {
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
                    executed = executeSingleWaiting(pos);
                }
                updateCurrentJobInfo(pos, executed);

                // 等待位置恢复独占当前 tick，避免突破每 tick 动作上限。
                return;
            }

            scanState = ScanState.PROCESS;
            consumePooledJobs(maxExecs);

            if (scanState == ScanState.WAITING
                    || skipIteration.get()
                    || ActionManager.INSTANCE.needWaitModifyLook
                    || timeLimitExceeded.get()) {
                return;
            }

            scanState = ScanState.COLLECT;
            producePooledJobs();
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
        }
    }

    /**
     * 按桶消费：取最早的同类事务桶，整桶连续执行直至预算耗尽或被中断。
     * 桶内坐标在消费前逐个校验有效性，失效的直接剔除；中断时剩余坐标留在桶里下 tick 继续。
     */
    private void consumePooledJobs(int maxExecs) {
        int execCount = 0;

        while (!timeLimitExceeded.get()
                && !skipIteration.get()
                && !ActionManager.INSTANCE.needWaitModifyLook) {
            ArrayDeque<BlockPos> bucket = null;
            TransactionKey bucketKey = null;
            Map.Entry<TransactionKey, ArrayDeque<BlockPos>> entry = jobPool.peekFirstBucket();
            if (entry != null) {
                bucketKey = entry.getKey();
                bucket = entry.getValue();
            }
            queuedJobCount = jobPool.size();
            if (bucket == null) {
                currentJobGuiInfo = null;
                return;
            }

            int remainingExecs = maxExecs > 0
                    ? Math.max(1, maxExecs - execCount)
                    : Integer.MAX_VALUE;
            int executed = executeJobTransaction(bucketKey, bucket, remainingExecs, skipIteration);
            execCount += executed;
            queuedJobCount = jobPool.size();

            if (maxExecs > 0 && execCount >= maxExecs) return;
            if (scanState == ScanState.WAITING) return;
        }
    }

    /**
     * 基类默认事务执行：遍历桶内坐标逐个执行。
     * Fill/FluidRemoval/Bedrock 等同质模块直接使用此实现；
     * Print 覆写以按桶的 {@link TransactionKey.Category} 分流到专门路径。
     */
    protected int executeJobTransaction(TransactionKey key, ArrayDeque<BlockPos> bucket,
                                        int maxExecs,
                                        AtomicReference<Boolean> skipIteration) {
        return executeBucketDefault(bucket, maxExecs, skipIteration);
    }

    /**
     * 默认桶执行：逐个校验并执行，失效的剔除，中断时保留剩余。
     */
    protected final int executeBucketDefault(ArrayDeque<BlockPos> bucket, int maxExecs,
                                             AtomicReference<Boolean> skipIteration) {
        int executed = 0;
        while (executed < maxExecs && !bucket.isEmpty()
                && !shouldStopJobTransaction(skipIteration)) {
            BlockPos pos = bucket.peek();
            if (!preparePooledJob(pos)) {
                jobPool.pollFromBucket(bucket);
                reportPooledJob(pos, false);
                continue;
            }
            jobPool.pollFromBucket(bucket);
            executePreparedPooledJob(pos);
            executed++;
        }
        return executed;
    }

    protected final boolean preparePooledJob(BlockPos pos) {
        return isQueuedPositionValid(pos) && needsQueuedWork(pos);
    }

    protected final void executePreparedPooledJob(BlockPos pos) {
        executeAndReturn(pos);
        updateCurrentJobInfo(pos, true);
    }

    protected final boolean shouldStopJobTransaction(
            AtomicReference<Boolean> skipIteration) {
        return timeLimitExceeded.get()
                || skipIteration.get()
                || ActionManager.INSTANCE.needWaitModifyLook
                || scanState == ScanState.WAITING;
    }

    protected final void reportPooledJob(BlockPos pos, boolean executed) {
        updateCurrentJobInfo(pos, executed);
    }

    /**
     * 等待恢复后单点执行：该坐标已在前一轮触发 skipIteration，
     * 恢复后独占一次执行机会，不参与桶批量。
     */
    private boolean executeSingleWaiting(BlockPos pos) {
        executePreparedPooledJob(pos);
        return true;
    }

    private void producePooledJobs() {
        try {
            while (!jobPool.isFull() && !timeLimitExceeded.get()) {
                BlockPos pos = iteratorManager.next();
                if (pos == null) {
                    // 扫描持续循环，但每 tick 最多跨越一次循环边界，避免空区域高速空转。
                    iteratorManager.reset();
                    return;
                }

                if (needsAreaCheck() && !isPosInWorkspace(pos)) continue;
                if (isOnCooldown(pos) || isCorrectBlock(pos)) continue;

                // 入队即算事务签名，把 canProcessPos 的重计算前移到生产侧以支持分组。
                // canProcessPos 会设置子类的 action/ctx 成员，供 getTransactionKey 使用。
                if (!canProcessPos(pos)) continue;

                TransactionKey key = getTransactionKey(pos);
                jobPool.offer(pos, key);

                updateGuiInfo(pos, false);
            }
        } finally {
            queuedJobCount = jobPool.size();
        }
    }

    /**
     * 事务签名。基类返回 {@link TransactionKey#HOMOGENEOUS}（全同质，单桶），
     * Print 覆写为基于 action 类别与主物品的精确分组。
     * 调用时 canProcessPos 已为该坐标设置好子类的 action/ctx 成员。
     */
    protected TransactionKey getTransactionKey(BlockPos pos) {
        return TransactionKey.HOMOGENEOUS;
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

    private boolean needsQueuedWork(BlockPos pos) {
        return !isCorrectBlock(pos) && !isOnCooldown(pos) && canProcessPos(pos);
    }

    private boolean executeAndReturn(BlockPos pos) {
        executeIteration(pos, skipIteration);
        return true;
    }

    private boolean isPosInWorkspace(BlockPos pos) {
        return needSchematic
                ? LitematicaUtils.isSchematicBlock(pos)
                : LitematicaUtils.inSelection(pos);
    }

    public void resetScanState() {
        resetScheduler();
    }

    private void resetScheduler() {
        scanState = ScanState.COLLECT;
        waitingPos = null;
        jobPool.clear();
        queuedJobCount = 0;
        currentJobGuiInfo = null;
        iteratorManager.reset();
    }

    @Nullable
    public GuiBlockInfo getGuiInfo() {
        return currentGuiInfo;
    }

    public boolean hasJobPoolScheduler() {
        return usesJobPool();
    }

    public int getQueuedJobCount() {
        return queuedJobCount;
    }

    public int getJobPoolCapacity() {
        return BlockJobPool.CAPACITY;
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

    protected boolean usesJobPool() {
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
