package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.concurrent.AsyncRoundCoordinator;
import me.aleksilassila.litematica.printer.core.job.JobPool;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全模块共用的异步搜索管线。
 *
 * <p>客户端主线程只提交不可变的扫描描述。唯一调度线程直接把范围切成固定大小的
 * 小快照块，并且最多只保持 {@code maxThreads} 个搜索任务正在执行；不会在工作池
 * 忙碌时叠加下一轮，也不会为填满线程数而重复提交小块。</p>
 */
public final class AsyncSearchCoordinator {
    public static final AsyncSearchCoordinator INSTANCE = new AsyncSearchCoordinator();

    /** 第一层只负责确定稳定的遍历分区，不读取大块世界数据。 */
    static final int SCHEDULER_BLOCK_EDGE = 32;
    /** 世界与投影从一开始就按这个小块尺寸读取并交给搜索线程。 */
    static final int SNAPSHOT_TILE_EDGE = 8;
    /** 特殊方块动作读取相邻状态所需的固定快照边界。 */
    static final int SNAPSHOT_HALO = 2;

    private final AsyncRoundCoordinator rounds =
            new AsyncRoundCoordinator("Printer-Search");

    private AsyncSearchCoordinator() {
    }

    /**
     * 仅在上一轮完全结束时接受新一轮。返回 false 表示工作池仍忙，本次没有排队。
     */
    public boolean tryStartRound(List<SearchRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        List<SearchRequest> immutableRequests = List.copyOf(requests);
        int configuredThreads = Math.max(
                1, Configs.Core.SEARCH_THREADS.getIntegerValue());
        return rounds.tryStartRound(
                configuredThreads,
                new SearchRound(immutableRequests));
    }

    public boolean isBusy() {
        return rounds.isBusy();
    }

    private static SearchTileSnapshot captureSmallSnapshot(
            SearchRequest request, TileSpec spec) {
        Map<BlockPos, BlockState> currentStates = new HashMap<>();
        Map<BlockPos, BlockState> requiredStates = new HashMap<>();

        int minY = Math.max(request.level().getMinY(), spec.minY() - SNAPSHOT_HALO);
        int maxY = Math.min(request.level().getMaxY(), spec.maxY() + SNAPSHOT_HALO);
        for (int x = spec.minX() - SNAPSHOT_HALO; x <= spec.maxX() + SNAPSHOT_HALO; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = spec.minZ() - SNAPSHOT_HALO; z <= spec.maxZ() + SNAPSHOT_HALO; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    currentStates.put(pos, request.level().getBlockState(pos));
                    if (request.includeSchematic() && request.schematic() != null) {
                        requiredStates.put(pos, request.schematic().getBlockState(pos));
                    }
                }
            }
        }

        SnapshotBlockView currentView = new SnapshotBlockView(
                currentStates,
                Blocks.VOID_AIR.defaultBlockState(),
                request.level().getMinY(),
                request.level().getHeight());
        SnapshotBlockView requiredView = new SnapshotBlockView(
                requiredStates,
                Blocks.AIR.defaultBlockState(),
                request.level().getMinY(),
                request.level().getHeight());
        List<SearchBlockSnapshot> blocks = new ArrayList<>(
                Math.toIntExact(Math.min(spec.volume(), Integer.MAX_VALUE)));

        forEachPosition(spec, request.bounds(), pos -> {
            if (!PlayerUtils.canInteracted(
                    pos, request.eyePos(), request.range(), request.shape())) {
                return;
            }
            if (!LitematicaUtils.isPositionWithinRange(pos)) return;
            if (!isInsideWorkspace(request, pos)) return;

            BlockState current = currentView.getBlockState(pos);
            BlockState required = request.includeSchematic()
                    ? requiredView.getBlockState(pos) : null;
            blocks.add(new SearchBlockSnapshot(
                    pos.immutable(), current, required, currentView, requiredView));
        });

        return new SearchTileSnapshot(
                spec.ordinal(),
                spec.volume(),
                List.copyOf(blocks),
                currentView,
                requiredView);
    }

    private static SearchTileSnapshot emptySnapshot(
            SearchRequest request, TileSpec spec) {
        SnapshotBlockView current = new SnapshotBlockView(
                Map.of(),
                Blocks.VOID_AIR.defaultBlockState(),
                request.level().getMinY(),
                request.level().getHeight());
        SnapshotBlockView required = new SnapshotBlockView(
                Map.of(),
                Blocks.AIR.defaultBlockState(),
                request.level().getMinY(),
                request.level().getHeight());
        return new SearchTileSnapshot(
                spec.ordinal(), spec.volume(), List.of(), current, required);
    }

    private static boolean isInsideWorkspace(SearchRequest request, BlockPos pos) {
        return switch (request.workspaceFilter()) {
            case NONE -> true;
            case SCHEMATIC -> LitematicaUtils.isSchematicBlock(pos);
            case SELECTION -> {
                boolean inside = false;
                for (SearchBounds box : request.selectionBoxes()) {
                    if (box.contains(pos)) {
                        inside = true;
                        break;
                    }
                }
                yield inside;
            }
        };
    }

    /**
     * 先按固定 32 边长建立调度分区，再直接细分为固定 8 边长的小快照。
     * 调度分区本身不读取世界，因而不存在中间大快照。
     */
    private static List<TileSpec> createTileSpecs(SearchBounds bounds) {
        List<TileSpec> tiles = new ArrayList<>();
        int ordinal = 0;

        List<Range> outerX = ranges(
                bounds.minX(), bounds.maxX(), SCHEDULER_BLOCK_EDGE, bounds.xIncrement());
        List<Range> outerY = ranges(
                bounds.minY(), bounds.maxY(), SCHEDULER_BLOCK_EDGE, bounds.yIncrement());
        List<Range> outerZ = ranges(
                bounds.minZ(), bounds.maxZ(), SCHEDULER_BLOCK_EDGE, bounds.zIncrement());

        for (AxisTriple outer : orderedTriples(
                outerX, outerY, outerZ, bounds.iterationOrder())) {
            List<Range> smallX = ranges(
                    outer.x().min(), outer.x().max(), SNAPSHOT_TILE_EDGE, bounds.xIncrement());
            List<Range> smallY = ranges(
                    outer.y().min(), outer.y().max(), SNAPSHOT_TILE_EDGE, bounds.yIncrement());
            List<Range> smallZ = ranges(
                    outer.z().min(), outer.z().max(), SNAPSHOT_TILE_EDGE, bounds.zIncrement());
            for (AxisTriple small : orderedTriples(
                    smallX, smallY, smallZ, bounds.iterationOrder())) {
                tiles.add(new TileSpec(
                        ordinal++,
                        small.x().min(), small.y().min(), small.z().min(),
                        small.x().max(), small.y().max(), small.z().max()));
            }
        }
        return tiles;
    }

    private static List<Range> ranges(
            int min, int max, int edge, boolean increment) {
        List<Range> result = new ArrayList<>();
        for (int start = min; start <= max; start += edge) {
            result.add(new Range(start, Math.min(max, start + edge - 1)));
        }
        if (!increment) java.util.Collections.reverse(result);
        return result;
    }

    private static List<AxisTriple> orderedTriples(
            List<Range> xs,
            List<Range> ys,
            List<Range> zs,
            IterationOrderType order) {
        List<AxisTriple> result = new ArrayList<>(xs.size() * ys.size() * zs.size());
        switch (order) {
            case XYZ -> {
                for (Range z : zs) for (Range y : ys) for (Range x : xs)
                    result.add(new AxisTriple(x, y, z));
            }
            case XZY -> {
                for (Range y : ys) for (Range z : zs) for (Range x : xs)
                    result.add(new AxisTriple(x, y, z));
            }
            case YXZ -> {
                for (Range z : zs) for (Range x : xs) for (Range y : ys)
                    result.add(new AxisTriple(x, y, z));
            }
            case YZX -> {
                for (Range x : xs) for (Range z : zs) for (Range y : ys)
                    result.add(new AxisTriple(x, y, z));
            }
            case ZXY -> {
                for (Range y : ys) for (Range x : xs) for (Range z : zs)
                    result.add(new AxisTriple(x, y, z));
            }
            case ZYX -> {
                for (Range x : xs) for (Range y : ys) for (Range z : zs)
                    result.add(new AxisTriple(x, y, z));
            }
        }
        return result;
    }

    private static void forEachPosition(
            TileSpec spec, SearchBounds bounds, PositionConsumer consumer) {
        int xStart = bounds.xIncrement() ? spec.minX() : spec.maxX();
        int xEnd = bounds.xIncrement() ? spec.maxX() : spec.minX();
        int yStart = bounds.yIncrement() ? spec.minY() : spec.maxY();
        int yEnd = bounds.yIncrement() ? spec.maxY() : spec.minY();
        int zStart = bounds.zIncrement() ? spec.minZ() : spec.maxZ();
        int zEnd = bounds.zIncrement() ? spec.maxZ() : spec.minZ();
        int xStep = bounds.xIncrement() ? 1 : -1;
        int yStep = bounds.yIncrement() ? 1 : -1;
        int zStep = bounds.zIncrement() ? 1 : -1;

        switch (bounds.iterationOrder()) {
            case XYZ -> {
                for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                    for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                        for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
            case XZY -> {
                for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                    for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                        for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
            case YXZ -> {
                for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                    for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                        for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
            case YZX -> {
                for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                    for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                        for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
            case ZXY -> {
                for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                    for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                        for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
            case ZYX -> {
                for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                    for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                        for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                            consumer.accept(new BlockPos(x, y, z));
            }
        }
    }

    private static boolean continuing(int value, int end, int step) {
        return step > 0 ? value <= end : value >= end;
    }

    public record SearchRequest(
            Module owner,
            ClientLevel level,
            @Nullable WorldSchematic schematic,
            SearchBounds bounds,
            WorkspaceFilter workspaceFilter,
            List<SearchBounds> selectionBoxes,
            Vec3 eyePos,
            double range,
            RadiusShapeType shape,
            boolean includeSchematic,
            Object context,
            long moduleGeneration,
            long poolGeneration) {
    }

    public record SearchBounds(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            IterationOrderType iterationOrder,
            boolean xIncrement,
            boolean yIncrement,
            boolean zIncrement) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        public long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }
    }

    public record SearchBlockSnapshot(
            BlockPos pos,
            BlockState currentState,
            @Nullable BlockState requiredState,
            SnapshotBlockView currentView,
            SnapshotBlockView requiredView) {
    }

    public record SearchTileSnapshot(
            int ordinal,
            long scannedPositions,
            List<SearchBlockSnapshot> blocks,
            SnapshotBlockView currentView,
            SnapshotBlockView requiredView) {
    }

    /**
     * 搜索线程可见的只读方块视图。它只持有调度线程复制出的状态，不引用实时世界。
     */
    public static final class SnapshotBlockView implements SignalGetter {
        private final Map<BlockPos, BlockState> states;
        private final BlockState fallback;
        private final int minY;
        private final int height;

        SnapshotBlockView(
                Map<BlockPos, BlockState> states,
                BlockState fallback,
                int minY,
                int height) {
            this.states = Map.copyOf(states);
            this.fallback = fallback;
            this.minY = minY;
            this.height = height;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, fallback);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinY() {
            return minY;
        }
    }

    public record SearchTileResult(
            int ordinal,
            long scannedPositions,
            List<JobPool.Job<BlockPos, TransactionKey>> jobs,
            @Nullable Object payload) {
        public static SearchTileResult jobs(
                SearchTileSnapshot snapshot,
                List<JobPool.Job<BlockPos, TransactionKey>> jobs) {
            return new SearchTileResult(
                    snapshot.ordinal(), snapshot.scannedPositions(), List.copyOf(jobs), null);
        }
    }

    public enum WorkspaceFilter {
        NONE,
        SCHEMATIC,
        SELECTION
    }

    private record Range(int min, int max) {
    }

    private record AxisTriple(Range x, Range y, Range z) {
    }

    private record TileSpec(
            int ordinal,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }
    }

    private record WorkUnit(RequestWork work, TileSpec spec) {
    }

    private record CapturedSearch(
            Module owner,
            Object context,
            SearchTileSnapshot snapshot) {
    }

    private static final class RequestWork {
        private final SearchRequest request;
        private final List<SearchTileResult> results;
        private final ArrayDeque<TileSpec> pendingSpecs;
        private final AtomicLongCounter completedPositions = new AtomicLongCounter();

        private RequestWork(SearchRequest request, List<TileSpec> specs) {
            this.request = request;
            this.results = new ArrayList<>(specs.size());
            this.pendingSpecs = new ArrayDeque<>(specs);
        }

        SearchRequest request() {
            return request;
        }

        List<SearchTileResult> results() {
            return results;
        }

        ArrayDeque<TileSpec> pendingSpecs() {
            return pendingSpecs;
        }

        AtomicLongCounter completedPositions() {
            return completedPositions;
        }
    }

    /**
     * 只由调度线程更新，但封装成独立计数器使意图明确。
     */
    private static final class AtomicLongCounter {
        private long value;

        long addAndGet(long delta) {
            value += delta;
            return value;
        }
    }

    private static final class SearchRound implements
            AsyncRoundCoordinator.Round<WorkUnit, CapturedSearch, SearchTileResult> {
        private final List<SearchRequest> requests;
        private List<RequestWork> requestWork = List.of();

        private SearchRound(List<SearchRequest> requests) {
            this.requests = requests;
        }

        @Override
        public List<WorkUnit> prepare() {
            List<RequestWork> prepared = new ArrayList<>(requests.size());
            ArrayDeque<WorkUnit> pending = new ArrayDeque<>();

            for (SearchRequest request : requests) {
                List<TileSpec> specs = createTileSpecs(request.bounds());
                RequestWork work = new RequestWork(request, specs);
                prepared.add(work);
                request.owner().searchRoundStarted(request, request.bounds().volume());
            }
            requestWork = List.copyOf(prepared);

            // 不同模块的小块轮询交错，避免一个超大范围独占搜索线程。
            boolean added;
            do {
                added = false;
                for (RequestWork work : requestWork) {
                    TileSpec spec = work.pendingSpecs().pollFirst();
                    if (spec == null) continue;
                    pending.addLast(new WorkUnit(work, spec));
                    added = true;
                }
            } while (added);
            return List.copyOf(pending);
        }

        @Override
        public CapturedSearch capture(WorkUnit unit) {
            SearchRequest request = unit.work().request();
            return new CapturedSearch(
                    request.owner(),
                    request.context(),
                    captureSmallSnapshot(request, unit.spec()));
        }

        @Override
        public SearchTileResult search(CapturedSearch captured) {
            return captured.owner().searchSnapshotTile(
                    captured.context(), captured.snapshot());
        }

        @Override
        public void captureFailed(WorkUnit unit, Throwable throwable) {
            Reference.LOGGER.error(
                    "读取异步搜索小快照失败: {}", unit.spec(), throwable);
            SearchTileSnapshot snapshot = emptySnapshot(
                    unit.work().request(), unit.spec());
            completed(unit, emptyResult(snapshot));
        }

        @Override
        public SearchTileResult searchFailed(
                WorkUnit unit,
                CapturedSearch captured,
                Throwable throwable) {
            Reference.LOGGER.error(
                    "异步搜索小任务失败: {}", unit.spec(), throwable);
            return emptyResult(captured.snapshot());
        }

        private static SearchTileResult emptyResult(SearchTileSnapshot snapshot) {
            return new SearchTileResult(
                    snapshot.ordinal(),
                    snapshot.scannedPositions(),
                    List.of(),
                    null);
        }

        @Override
        public void completed(WorkUnit unit, SearchTileResult result) {
            RequestWork work = unit.work();
            work.results().add(result);
            long scanned = work.completedPositions().addAndGet(
                    result.scannedPositions());
            work.request().owner().searchRoundProgress(
                    work.request(), scanned, work.request().bounds().volume());
        }

        @Override
        public void finish() {
            for (RequestWork work : requestWork) {
                work.results().sort(Comparator.comparingInt(SearchTileResult::ordinal));
                work.request().owner().publishSearchRound(
                        work.request(), List.copyOf(work.results()));
            }
        }

        @Override
        public void aborted(Throwable throwable) {
            Reference.LOGGER.error("异步搜索轮次失败", throwable);
        }
    }

    @FunctionalInterface
    private interface PositionConsumer {
        void accept(BlockPos pos);
    }
}
