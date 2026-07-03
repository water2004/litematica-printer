package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.LayerRange;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.OperationQueue;
import me.aleksilassila.litematica.printer.printer.QueuedOperation;
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
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

public abstract class Module extends ConfigUtils {
    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> boxRef;

    @Getter
    private final String id;

    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;

    @Getter
    @Nullable
    private final ConfigOptionList selectionType;

    // 跳过迭代标志
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);

    // GUI信息队列（用于渲染）
    private final Queue<GuiBlockInfo> guiQueue = new ConcurrentLinkedQueue<>();

    // 迭代状态缓存（性能优化关键）
    private Iterator<BlockPos> cachedIterator = null;
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
    protected boolean didWorkThisTick = false;
    private int expandRange = -1;
    @Nullable
    private PrinterBox lastBox;
    @Nullable
    private BlockPos lastPos;
    private int lastLayerMin = Integer.MIN_VALUE;
    private int lastLayerMax = Integer.MIN_VALUE;
    private int lastLayerSingle = Integer.MIN_VALUE;
    private int lastLayerAbove = Integer.MIN_VALUE;
    private int lastLayerBelow = Integer.MIN_VALUE;
    @Nullable
    private Direction.Axis lastLayerAxis = null;
    @Nullable
    private LayerMode lastLayerMode = null;
    private long lastTickTime = -1L;
    @Getter
    private int renderIndex = 0;
    @Getter
    private ScanState scanState = ScanState.FULL;
    private int idleTicks = 0;
    private int guiCacheTicks;

    protected Module(String id, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.boxRef = useBox ? new AtomicReference<>() : null;
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
        if (guiCacheTicks > 0) {
            guiCacheTicks--;
        } else {
            guiQueue.clear();
            renderIndex = 0;
        }

        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ModuleManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
                return;
            }
            lastTickTime = currentTickTime;
        }

        // 基础检查
        if (!isPrinterEnable()) {
            lastPos = null;
            pendingHighlights.clear();
            return;
        }

        if (!isConfigAllowed()) {
            lastPos = null;
            pendingHighlights.clear();
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            lastPos = null;
            return;
        }

        updateBox();
        // 填充和排流体需要提前把方块列表字符串转换成对象
        preprocess();

        // Phase 1: 消费 BlockUpdate 脏坐标 → 生成修复操作
        OperationQueue.INSTANCE.processDirty();

        // --- Scan State Gate: 惰性 / 部分 / 全量 模式选择 ---
        if (scanState == ScanState.LAZY) {
            // shouldProcessQueue()=false 的 handler（如 GUI）不依赖队列判空
            boolean noQueueWork = !shouldProcessQueue() || OperationQueue.INSTANCE.isEmpty();
            if (noQueueWork && !RegionTracker.INSTANCE.hasDirtyRegions()) {
                return;  // 无待处理操作，跳过整个迭代阶段
            }
            // BlockUpdate 唤醒了我们 → 按脏区域数量决定重扫粒度
            int dirtyRegions = RegionTracker.INSTANCE.getDirtyCount();
            int fullThreshold = Configs.Core.LAZY_DIRTY_WAKE_THRESHOLD.getIntegerValue();
            if (dirtyRegions > 0 && dirtyRegions < fullThreshold) {
                scanState = ScanState.PARTIAL;
                cachedIterator = RegionTracker.INSTANCE.createDirtyRegionIterator();
            } else {
                scanState = ScanState.FULL;
                // FULL 全量扫描会覆盖整个 box，清除脏标记避免下个 tick 重复唤醒
                RegionTracker.INSTANCE.clearAllDirty();
            }
        }

        if (scanState == ScanState.PARTIAL && cachedIterator == null) {
            cachedIterator = RegionTracker.INSTANCE.createDirtyRegionIterator();
        }

        // Phase 2: 重置状态，然后执行（队列优先 + 迭代扫描）
        skipIteration.set(false);
        didWorkThisTick = false;

        int maxExecs = getMaxExecutions();
        int execCount = 0;

        // 队列消费：依次 poll 所有操作，当前 handler 能处理的就执行，不能处理的放回队尾
        // 这保证了 repair op 总能到达正确的 handler，不会被 GUI 等 handler 误消费
        // 注意：队列操作也计入 execCount，确保不会超过每 tick 最大执行次数
        // 注意：队列路径必须与 iterateBlocks() 使用相同的范围检查，
        // 否则 repair op 会绕过 isSchematicBlock / renderLayer / selectionType 过滤。
        if (shouldProcessQueue()) {
            int ops = OperationQueue.INSTANCE.size();
            for (int i = 0; i < ops && !skipIteration.get(); i++) {
                QueuedOperation op = OperationQueue.INSTANCE.poll();
                if (op == null) break;
                if (!isOnCooldown(op.getPos()) && canProcessPos(op.getPos())) {
                    // 范围检查（与 iterateBlocks 中的 needRangeCheck 一致）
                    if (needsRangeCheck()) {
                        if (isSchematicHandler()
                                ? !SchematicSnapshot.INSTANCE.contains(op.getPos())
                                : !LitematicaUtils.isWithinSelection1ModeRange(op.getPos())) {
                            // 位置不在有效范围/子区域中 — 直接丢弃操作，不放回队尾
                            continue;
                        }
                        if (selectionType != null && !PlayerUtils.isPositionInSelectionRange(player, op.getPos(), selectionType)) {
                            continue;
                        }
                    }
                    executeIteration(op.getPos(), skipIteration);
                    didWorkThisTick = true;
                    if (maxExecs > 0 && ++execCount >= maxExecs) {
                        break;
                    }
                } else {
                    OperationQueue.INSTANCE.addLast(op);
                }
            }
        }

        if (!skipIteration.get()) {
            int remaining = maxExecs > 0 ? Math.max(0, maxExecs - execCount) : 0;
            if (remaining > 0 || maxExecs <= 0) {
                if (!iterateBlocks(remaining)) {
                    if (scanState != ScanState.PARTIAL) lastPos = null;
                }
            }
        }

        // --- 空闲跟踪：无工作 → 计数，稳定后进入惰性 ---
        // shouldProcessQueue()=false 的 handler 不依赖队列判空（如 GUI），直接走空闲计数
        if (!didWorkThisTick && (!shouldProcessQueue() || OperationQueue.INSTANCE.isEmpty())) {
            idleTicks++;
            int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
            if (lazyThreshold > 0 && idleTicks >= lazyThreshold) {
                scanState = ScanState.LAZY;
                idleTicks = 0;
                RegionTracker.INSTANCE.clearAllDirty();
            }
        } else {
            idleTicks = 0;
        }

        // PARTIAL 扫描完成后直接进入 LAZY：脏区域已在扫描中被 DirtyRegionIterator 逐个 markClean
        // 任何新的方块变化都会通过 BlockUpdate 重新唤醒 LAZY
        if (scanState == ScanState.PARTIAL && cachedIterator == null) {
            scanState = ScanState.LAZY;
            idleTicks = 0;
            RegionTracker.INSTANCE.clearAllDirty();
        }
    }

    /**
     * 更新迭代区域
     */
    private void updateBox() {
        if (boxRef == null) return;

        BlockPos eyePos = new BlockPos(new Vec3i((int) Math.round(player.getX()), (int) Math.round(player.getEyeY()), (int) Math.round(player.getZ())));
        PrinterBox box = boxRef.get();

        double effectiveRange = ConfigUtils.getEffectiveRange();
        int currentRange = (int) Math.ceil(effectiveRange);

        // 依据渲染层限制，优化迭代效率
        LayerRange layerRange = DataManager.getRenderLayerRange();
        LayerMode layerMode = layerRange.getLayerMode();
        Direction.Axis layerAxis = layerRange.getAxis();
        int layerMin = layerRange.getLayerMin();
        int layerMax = layerRange.getLayerMax();
        int layerSingle = layerRange.getLayerSingle();
        int layerAbove = layerRange.getLayerAbove();
        int layerBelow = layerRange.getLayerBelow();

        boolean needRebuild = box == null
                || !box.equals(lastBox)
                || lastPos == null
                || !lastPos.closerThan(eyePos, effectiveRange * 0.4)
                || expandRange != currentRange
                || layerMin != lastLayerMin
                || layerMax != lastLayerMax
                || layerSingle != lastLayerSingle
                || layerAbove != lastLayerAbove
                || layerBelow != lastLayerBelow
                || layerAxis != lastLayerAxis
                || layerMode != lastLayerMode;

        if (needRebuild) {
            lastPos = eyePos;
            expandRange = currentRange;
            lastLayerMin = layerMin;
            lastLayerMax = layerMax;
            lastLayerSingle = layerSingle;
            lastLayerAbove = layerAbove;
            lastLayerBelow = layerBelow;
            lastLayerAxis = layerAxis;
            lastLayerMode = layerMode;

            int minX = (int) Math.floor(player.getX() - effectiveRange);
            int maxX = (int) Math.ceil(player.getX() + effectiveRange);
            int minY = (int) Math.floor(player.getEyeY() - effectiveRange);
            int maxY = (int) Math.ceil(player.getEyeY() + effectiveRange);
            int minZ = (int) Math.floor(player.getZ() - effectiveRange);
            int maxZ = (int) Math.ceil(player.getZ() + effectiveRange);

            if (selectionType != null
                    && selectionType.getOptionListValue() == SelectionType.LITEMATICA_RENDER_LAYER
                    && layerMode != LayerMode.ALL) {
                switch (layerMode) {
                    case SINGLE_LAYER -> {
                        switch (layerAxis) {
                            case Y -> { minY = layerSingle; maxY = layerSingle; }
                            case X -> { minX = layerSingle; maxX = layerSingle; }
                            case Z -> { minZ = layerSingle; maxZ = layerSingle; }
                        }
                    }
                    case LAYER_RANGE -> {
                        switch (layerAxis) {
                            case Y -> { minY = Math.max(minY, layerMin); maxY = Math.min(maxY, layerMax); }
                            case X -> { minX = Math.max(minX, layerMin); maxX = Math.min(maxX, layerMax); }
                            case Z -> { minZ = Math.max(minZ, layerMin); maxZ = Math.min(maxZ, layerMax); }
                        }
                    }
                    case ALL_BELOW -> {
                        switch (layerAxis) {
                            case Y -> maxY = Math.min(maxY, layerBelow);
                            case X -> maxX = Math.min(maxX, layerBelow);
                            case Z -> maxZ = Math.min(maxZ, layerBelow);
                        }
                    }
                    case ALL_ABOVE -> {
                        switch (layerAxis) {
                            case Y -> minY = Math.max(minY, layerAbove);
                            case X -> minX = Math.max(minX, layerAbove);
                            case Z -> minZ = Math.max(minZ, layerAbove);
                        }
                    }
                }
            }

            // 裁剪到选区边界：SELECTION / BELOW_PLAYER / ABOVE_PLAYER 都需要先限定在选区内
            if (selectionType != null
                    && selectionType.getOptionListValue() instanceof SelectionType st) {
                if (st == SelectionType.LITEMATICA_SELECTION
                        || st == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER
                        || st == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    PrinterBox selBounds = LitematicaUtils.getSelectionBounds();
                    if (selBounds != null) {
                        minX = Math.max(minX, selBounds.minX);
                        maxX = Math.min(maxX, selBounds.maxX);
                        minY = Math.max(minY, selBounds.minY);
                        maxY = Math.min(maxY, selBounds.maxY);
                        minZ = Math.max(minZ, selBounds.minZ);
                        maxZ = Math.min(maxZ, selBounds.maxZ);
                    }
                }
                // 在选区基础上进一步限制玩家上下
                if (st == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
                    maxY = Math.min(maxY, (int) Math.floor(player.getY()));
                } else if (st == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    minY = Math.max(minY, (int) Math.ceil(player.getY()));
                }
            }

            box = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
            lastBox = box;
            boxRef.set(box);

            box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
            box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
            box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
            box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();

            cachedIterator = null;
            RegionTracker.INSTANCE.rebuild(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            scanState = ScanState.FULL;
            // 仅在玩家移动或配置变更时重置空闲计数；扫描周期自然完成（lastPos == null）不清零，
            // 否则空闲计数永远达不到惰性阈值
            if (box != null) { // 非首次初始化
            boolean playerMoved = lastPos != null && !lastPos.closerThan(eyePos, effectiveRange * 0.4);
            if (playerMoved || expandRange != currentRange) {
                    idleTicks = 0;
                }
            } else {
                idleTicks = 0;
            }
        }
    }

    private boolean iterateBlocks(int maxExecs) {
        if (boxRef == null || !canExecute()) return false;

        PrinterBox box = boxRef.get();
        if (box == null || !canIterate()) return false;

        if (cachedIterator == null) {
            cachedIterator = box.iterator();
            // 清理已超出渐隐时长的过期条目，保留仍在渐隐中的条目不影响显示
            long expireCutoff = System.currentTimeMillis() - Configs.Highlight.HIGHLIGHT_FADE_DURATION.getIntegerValue() * 100L;
            pendingHighlights.removeIf(ph -> ph.time() < expireCutoff);
        }

        int execCount = 0;

        Vec3 eyePos = player.getEyePosition();
        double effectiveRange = ConfigUtils.getEffectiveRange();
        RadiusShapeType shapeType = Configs.Core.ITERATOR_SHAPE.getOptionListValue() instanceof RadiusShapeType s ? s : null;

        long startTime = getIterationTimeLimit() > 0 ? System.nanoTime() : 0;
        long timeLimitNanos = getIterationTimeLimit() * 1_000_000L;
        int checkInterval = 10;
        int iterCount = 0;

        skipIteration.set(false);
        guiQueue.clear();
        renderIndex = 0;

        while (cachedIterator.hasNext()) {
            if (getIterationTimeLimit() > 0 && ++iterCount % checkInterval == 0) {
                if (System.nanoTime() - startTime >= timeLimitNanos) {
                    stopIteration(true);
                    return true;
                }
            }

            if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                stopIteration(true);
                return true;
            }

            BlockPos pos = cachedIterator.next();
            if (pos == null) continue;

            if (shapeType != null) {
                if (!PlayerUtils.canInteracted(pos, eyePos, effectiveRange, shapeType)) continue;
            } else if (!PlayerUtils.canInteracted(pos)) continue;

            if (needsRangeCheck()) {
                if (isSchematicHandler() ? !SchematicSnapshot.INSTANCE.contains(pos)
                        : !LitematicaUtils.isWithinSelection1ModeRange(pos)) {
                    continue;
                }
            }

            if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
                GuiBlockInfo gui = isSchematicHandler()
                        ? new GuiBlockInfo(level, SchematicWorldHandler.getSchematicWorld(), pos)
                        : new GuiBlockInfo(level, null, pos);
                gui.interacted = true;
                gui.posInSelectionRange = true;
                gui.execute = canProcessPos(pos) && !isOnCooldown(pos);
                addGuiInfo(gui);
            }

            if (!isOnCooldown(pos) && canProcessPos(pos)) {
                executeIteration(pos, skipIteration);
                didWorkThisTick = true;

                if (skipIteration.get() || (maxExecs > 0 && ++execCount >= maxExecs)) {
                    stopIteration(true);
                    return true;
                }
            }
        }

        cachedIterator = null;
        stopIteration(false);
        return false;
    }

    protected void stopIteration(boolean interrupt) {
    }

    protected boolean isSchematicHandler() {
        return false;
    }

    private void addGuiInfo(GuiBlockInfo info) {
        if (info != null) {
            guiQueue.add(info);
            guiCacheTicks = 20;
        }
    }

    @Nullable
    public GuiBlockInfo nextGuiInfo() {
        if (guiQueue.isEmpty()) return null;

        GuiBlockInfo[] arr = guiQueue.toArray(new GuiBlockInfo[0]);
        if (renderIndex >= arr.length) {
            renderIndex = 0;
            return arr[arr.length - 1];
        }
        return arr[renderIndex++];
    }

    public int getGuiQueueSize() {
        return guiQueue.size();
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

    protected boolean shouldProcessQueue() {
        return true;
    }

    public boolean canProcessPos(BlockPos pos) {
        return true;
    }

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

    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return Direction.orderedByNearest(player)[0].getOpposite();
    }

    protected boolean needsRangeCheck() {
        return true;
    }

    public record PendingHighlight(BlockPos pos, long time, HighlightType type) {
        public PendingHighlight(BlockPos pos, long time) {
            this(pos, time, HighlightType.PLACE);
        }
    }
}