package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.job.JobPool;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
    protected final JobPool<BlockPos, TransactionKey> jobPool =
            new JobPool<>(BlockPos::immutable);
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
    @Nullable
    private TransactionKey waitingKey = null;

    private volatile GuiBlockInfo currentGuiInfo = null;
    private volatile GuiBlockInfo currentJobGuiInfo = null;
    private volatile int queuedJobCount = 0;
    private volatile long producerScannedPositions = 0L;
    private volatile long producerTotalPositions = 0L;
    private volatile boolean searchPrepared = false;
    private long moduleGeneration = 0L;

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

    /**
     * 主线程只捕获搜索描述所需的轻量状态，不执行任何范围扫描。
     * 即使业务动作因容器、延迟检测或换手而暂停，这一步仍可独立推进。
     */
    public final void prepareAsyncSearch() {
        searchPrepared = false;
        if (!isConfigAllowed()) {
            pendingHighlights.clear();
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            return;
        }

        if (usesAsyncSearch() && queuedSchedulerLevel != level) {
            queuedSchedulerLevel = level;
            resetScheduler();
            iteratorManager.markNeedsRebuild();
        }

        if (box == null) return;
        if (iteratorManager.tryBuildBox(player, selectionType != null ? selectionType.getOptionListValue() : null)) {
            box.set(iteratorManager.getBox());
            // 玩家移动只替换下一轮扫描描述；旧快照结果可安全发布，
            // 消费者会按最新范围与世界状态惰性丢弃。
            if (scanState != ScanState.WAITING) scanState = ScanState.COLLECT;
        }

        preprocess();
        searchPrepared = true;
    }

    /**
     * 客户端主线程的纯消费者阶段。
     */
    public void tick() {
        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ModuleManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
                return;
            }
            lastTickTime = currentTickTime;
        }

        if (!isConfigAllowed() || !searchPrepared) return;
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
     * 消费者调度：搜索生产由独立线程持续推进，这里只消费已发布的有限快照桶。
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
                TransactionKey key = waitingKey;
                waitingPos = null;
                waitingKey = null;
                scanState = ScanState.PROCESS;

                boolean executed = false;
                if (key != null && prepareMatchingPooledJob(pos, key)) {
                    executed = executeSingleWaiting(pos, key);
                }
                updateCurrentJobInfo(pos, executed);

                if (scanState != ScanState.WAITING) {
                    int remainingExecs = maxExecs > 0
                            ? Math.max(0, maxExecs - (executed ? 1 : 0))
                            : -1;
                    if (remainingExecs != 0
                            && !skipIteration.get()
                            && !timeLimitExceeded.get()
                            && !ActionManager.INSTANCE.needWaitModifyLook) {
                        // 换手确认后主手材料已就绪，立即继续消费同一快照桶，
                        // 避免只放一个方块便切换到其他材料。
                        consumePooledJobs(remainingExecs);
                    }
                }
            } else {
                scanState = ScanState.PROCESS;
                consumePooledJobs(maxExecs);
            }
            if (scanState != ScanState.WAITING) scanState = ScanState.COLLECT;
        } finally {
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeLimitExceeded.set(false);
        }
    }

    /**
     * 消费者从共享目录摘取一个有限快照桶，跨 tick 连续消费直到为空。
     * 生产者不会再向这个桶写入；桶内坐标在消费前逐个校验，失效的直接剔除。
     */
    private void consumePooledJobs(int maxExecs) {
        int execCount = 0;

        while (!timeLimitExceeded.get()
                && !skipIteration.get()
                && !ActionManager.INSTANCE.needWaitModifyLook) {
            JobPool.BucketSelection<BlockPos, TransactionKey> selection =
                    jobPool.currentBucket();
            ArrayDeque<BlockPos> bucket = null;
            TransactionKey bucketKey = null;
            if (selection != null) {
                bucketKey = selection.key();
                bucket = selection.bucket();
            }
            queuedJobCount = jobPool.size();
            if (bucket == null) {
                currentJobGuiInfo = null;
                return;
            }

            int allowedExecutions = maxExecs > 0
                    ? Math.max(1, maxExecs - execCount)
                    : Integer.MAX_VALUE;
            int executed = executeJobTransaction(bucketKey, bucket, allowedExecutions, skipIteration);
            execCount += executed;
            queuedJobCount = jobPool.size();

            // 快照桶不再放回共享目录。阻塞换手、动作限额或时间片结束时
            // 留到下一 tick 继续；桶被取空后 pollFromBucket 会自动释放。
            if (scanState == ScanState.WAITING) return;

            if (maxExecs > 0 && execCount >= maxExecs) return;
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
        return executeBucketDefault(key, bucket, maxExecs, skipIteration);
    }

    /**
     * 默认桶执行：逐个校验并执行，失效的剔除，中断时保留剩余。
     */
    protected final int executeBucketDefault(TransactionKey key, ArrayDeque<BlockPos> bucket,
                                             int maxExecs,
                                             AtomicReference<Boolean> skipIteration) {
        int executed = 0;
        while (executed < maxExecs && !bucket.isEmpty()
                && !shouldStopJobTransaction(skipIteration)) {
            // 取出即消费；后续无论已完成、跳过、失败还是成功都不放回。
            // 世界仍不正确时，持续扫描的生产者会重新生成该坐标的作业。
            BlockPos pos = jobPool.pollFromBucket(key, bucket);
            if (pos == null) break;
            if (!prepareMatchingPooledJob(pos, key)) {
                reportPooledJob(pos, false);
                continue;
            }
            executePreparedPooledJob(pos, key);
            executed++;
        }
        return executed;
    }

    protected final boolean preparePooledJob(BlockPos pos) {
        return isQueuedPositionValid(pos) && needsQueuedWork(pos);
    }

    /**
     * 现场重建结果必须仍与搜索时的桶签名一致；不一致的作业只消费、不改写。
     */
    protected final boolean prepareMatchingPooledJob(
            BlockPos pos, TransactionKey expectedKey) {
        return preparePooledJob(pos)
                && expectedKey.equals(getPreparedTransactionKey(pos));
    }

    protected final void executePreparedPooledJob(
            BlockPos pos, TransactionKey expectedKey) {
        executeAndReturn(pos);
        if (scanState == ScanState.WAITING) {
            waitingKey = expectedKey;
        }
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
    private boolean executeSingleWaiting(BlockPos pos, TransactionKey expectedKey) {
        executePreparedPooledJob(pos, expectedKey);
        return true;
    }

    private boolean isQueuedPositionValid(@Nullable BlockPos pos) {
        if (pos == null) return false;
        if (!PlayerUtils.canInteracted(pos)) return false;
        if (!LitematicaUtils.isPositionWithinRange(pos)) return false;
        return !needsAreaCheck() || isPosInWorkspace(pos);
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
        moduleGeneration++;
        scanState = ScanState.COLLECT;
        waitingPos = null;
        waitingKey = null;
        jobPool.clear();
        queuedJobCount = 0;
        currentJobGuiInfo = null;
        producerScannedPositions = 0L;
        producerTotalPositions = 0L;
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
        return jobPool.capacity();
    }

    public long getProducerScannedPositions() {
        return producerScannedPositions;
    }

    public long getProducerTotalPositions() {
        return producerTotalPositions;
    }

    @Nullable
    public GuiBlockInfo getCurrentJobGuiInfo() {
        return currentJobGuiInfo;
    }

    /**
     * 主线程创建不可变扫描描述；不读取范围内的任何方块。
     */
    @Nullable
    public final AsyncSearchCoordinator.SearchRequest captureSearchRequest() {
        if (!searchPrepared || !usesAsyncSearch() || box == null || box.get() == null
                || level == null || player == null || !canSearch()) {
            return null;
        }

        PrinterBox currentBox = box.get();
        Object context = captureSearchContext();
        WorldSchematic schematic = includeSchematicSnapshot()
                ? SchematicWorldHandler.getSchematicWorld()
                : null;
        if (workspaceFilter() == AsyncSearchCoordinator.WorkspaceFilter.SCHEMATIC
                && schematic == null) {
            return null;
        }

        RadiusShapeType shape =
                Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType value
                        ? value : RadiusShapeType.SPHERE;
        AsyncSearchCoordinator.SearchBounds bounds = new AsyncSearchCoordinator.SearchBounds(
                currentBox.minX, currentBox.minY, currentBox.minZ,
                currentBox.maxX, currentBox.maxY, currentBox.maxZ,
                currentBox.iterationMode,
                currentBox.xIncrement, currentBox.yIncrement, currentBox.zIncrement);

        List<AsyncSearchCoordinator.SearchBounds> selectionBoxes = List.of();
        if (workspaceFilter() == AsyncSearchCoordinator.WorkspaceFilter.SELECTION) {
            selectionBoxes = LitematicaUtils.getSelectionBoxesSnapshot().stream()
                    .map(value -> new AsyncSearchCoordinator.SearchBounds(
                            value.minX, value.minY, value.minZ,
                            value.maxX, value.maxY, value.maxZ,
                            value.iterationMode,
                            value.xIncrement, value.yIncrement, value.zIncrement))
                    .toList();
            if (selectionBoxes.isEmpty()) return null;
        }

        return new AsyncSearchCoordinator.SearchRequest(
                this,
                level,
                schematic,
                bounds,
                workspaceFilter(),
                selectionBoxes,
                player.getEyePosition(),
                ConfigUtils.getEffectiveRange(),
                shape,
                includeSchematicSnapshot(),
                context,
                moduleGeneration,
                jobPool.generation());
    }

    /**
     * 搜索线程入口。默认实现只产生不可变坐标和事务键，不接触模块动作状态。
     */
    final AsyncSearchCoordinator.SearchTileResult searchSnapshotTile(
            Object searchContext,
            AsyncSearchCoordinator.SearchTileSnapshot snapshot) {
        return searchTile(searchContext, snapshot);
    }

    protected AsyncSearchCoordinator.SearchTileResult searchTile(
            Object searchContext,
            AsyncSearchCoordinator.SearchTileSnapshot snapshot) {
        List<JobPool.Job<BlockPos, TransactionKey>> jobs = new ArrayList<>();
        SearchCandidateInfo lastCandidate = null;
        for (AsyncSearchCoordinator.SearchBlockSnapshot block : snapshot.blocks()) {
            TransactionKey key = getSearchTransactionKey(block, searchContext);
            if (key == null) continue;
            jobs.add(new JobPool.Job<>(block.pos(), key));
            lastCandidate = new SearchCandidateInfo(
                    block.pos(), block.currentState(), block.requiredState());
        }
        return new AsyncSearchCoordinator.SearchTileResult(
                snapshot.ordinal(),
                snapshot.scannedPositions(),
                List.copyOf(jobs),
                lastCandidate);
    }

    /**
     * 调度线程发布一整轮结果。所有工作线程此时均已完成。
     */
    protected void publishSearchRound(
            AsyncSearchCoordinator.SearchRequest request,
            List<AsyncSearchCoordinator.SearchTileResult> results) {
        if (!isSearchRequestCurrent(request)) return;

        List<JobPool.Job<BlockPos, TransactionKey>> jobs = new ArrayList<>();
        SearchCandidateInfo lastCandidate = null;
        for (AsyncSearchCoordinator.SearchTileResult result : results) {
            jobs.addAll(result.jobs());
            if (result.payload() instanceof SearchCandidateInfo block) {
                lastCandidate = block;
            }
        }
        jobPool.publish(jobs, request.poolGeneration());
        queuedJobCount = jobPool.size();

        if (lastCandidate != null) {
            BlockState required = lastCandidate.requiredState() != null
                    ? lastCandidate.requiredState() : lastCandidate.currentState();
            currentGuiInfo = new GuiBlockInfo(
                    lastCandidate.pos(),
                    lastCandidate.currentState(),
                    required,
                    true,
                    false,
                    true);
        }
    }

    protected void searchRoundStarted(
            AsyncSearchCoordinator.SearchRequest request, long totalPositions) {
        if (!isSearchRequestCurrent(request)) return;
        producerScannedPositions = 0L;
        producerTotalPositions = totalPositions;
        if (scanState != ScanState.WAITING) scanState = ScanState.COLLECT;
    }

    protected void searchRoundProgress(
            AsyncSearchCoordinator.SearchRequest request,
            long scannedPositions,
            long totalPositions) {
        if (!isSearchRequestCurrent(request)) return;
        producerScannedPositions = Math.min(scannedPositions, totalPositions);
        producerTotalPositions = totalPositions;
    }

    protected final boolean isSearchRequestCurrent(
            AsyncSearchCoordinator.SearchRequest request) {
        return request.owner() == this
                && request.level() == queuedSchedulerLevel
                && request.moduleGeneration() == moduleGeneration;
    }

    /**
     * 搜索配置只在主线程捕获一次，工作线程必须把它视为不可变对象。
     */
    protected Object captureSearchContext() {
        return EmptySearchContext.INSTANCE;
    }

    /**
     * 纯搜索判定：只允许读取小块快照与 captureSearchContext() 返回的不可变配置。
     */
    @Nullable
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block,
            Object searchContext) {
        return null;
    }

    /**
     * 消费前按实时世界重建的事务键。与搜索快照键不一致时，作业直接丢弃。
     */
    protected TransactionKey getPreparedTransactionKey(BlockPos pos) {
        return TransactionKey.HOMOGENEOUS;
    }

    protected boolean includeSchematicSnapshot() {
        return needSchematic;
    }

    protected AsyncSearchCoordinator.WorkspaceFilter workspaceFilter() {
        return needSchematic
                ? AsyncSearchCoordinator.WorkspaceFilter.SCHEMATIC
                : AsyncSearchCoordinator.WorkspaceFilter.SELECTION;
    }

    protected boolean canSearch() {
        return canIterate() && !jobPool.isFull();
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

    protected boolean usesAsyncSearch() {
        return usesJobPool();
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

    private enum EmptySearchContext {
        INSTANCE
    }

    /**
     * HUD 只需保留候选位置和状态，不能让结果 payload 间接持有整个小块快照。
     */
    private record SearchCandidateInfo(
            BlockPos pos,
            BlockState currentState,
            @Nullable BlockState requiredState) {
    }
}
