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
    @Getter
    private ScanState scanState = ScanState.COLLECT;

    private Iterator<BlockPos> processIter = null;

    @Nullable
    private BlockPos waitingPos = null;

    private volatile GuiBlockInfo currentGuiInfo = null;

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
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            return;
        }

        if (box == null) return;
        if (iteratorManager.tryBuildBox(player, selectionType != null ? selectionType.getOptionListValue() : null)) {
            box.set(iteratorManager.getBox());
            scanState = ScanState.COLLECT;
            scanPlan.reset();
            processIter = null;
            waitingPos = null;
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

        switch (scanState) {
            case COLLECT -> collectPhase(maxExecs);
            case PROCESS -> processPhase(maxExecs);
            case WAITING -> waitingPhase(maxExecs);
        }
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

    private boolean needsWork(BlockPos pos) {
        return !isOnCooldown(pos) && canProcessPos(pos) && !isCorrectBlock(pos);
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

                currentGuiInfo = new GuiBlockInfo(pos,
                        level.getBlockState(pos), LitematicaUtils.getBlockState(pos),
                        PlayerUtils.canInteracted(pos), executed,
                        isPosInWorkspace(pos) && PlayerUtils.canInteracted(pos));
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
        iteratorManager.reset();
    }

    @Nullable
    public GuiBlockInfo getGuiInfo() {
        return currentGuiInfo;
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