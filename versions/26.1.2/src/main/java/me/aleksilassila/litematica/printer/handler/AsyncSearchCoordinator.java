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
import java.util.IdentityHashMap;
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
    /** 搜索任务保持细粒度；快照页与任务独立并由整轮共享。 */
    static final int SEARCH_TILE_EDGE = 8;
    /** 世界与投影按互不重叠的小页读取，每个位置在一轮内最多读取一次。 */
    static final int SNAPSHOT_PAGE_EDGE = 8;

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

    private static SearchTileSnapshot captureSmallSnapshot(WorkUnit unit) {
        SearchRequest request = unit.work().request();
        TileSpec spec = unit.spec();
        SnapshotBlockView currentView = unit.currentPages().capture(unit.pageRange());
        SnapshotBlockView requiredView = unit.requiredPages() == null
                ? emptyView(Blocks.AIR.defaultBlockState(), request.level())
                : unit.requiredPages().capture(unit.pageRange());
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
        SnapshotBlockView current = emptyView(
                Blocks.VOID_AIR.defaultBlockState(), request.level());
        SnapshotBlockView required = emptyView(
                Blocks.AIR.defaultBlockState(), request.level());
        return new SearchTileSnapshot(
                spec.ordinal(), spec.volume(), List.of(), current, required);
    }

    private static SnapshotBlockView emptyView(
            BlockState fallback, ClientLevel level) {
        return new SnapshotBlockView(
                new SnapshotPage[0], 0, 0, 0, 0, 0, 0,
                fallback, level.getMinY(), level.getHeight());
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
     * 先按固定 32 边长建立调度分区，再细分为固定 8 边长的搜索任务。
     * 快照页独立于搜索任务并由整轮共享，不会复制相邻任务的 halo。
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
                    outer.x().min(), outer.x().max(), SEARCH_TILE_EDGE, bounds.xIncrement());
            List<Range> smallY = ranges(
                    outer.y().min(), outer.y().max(), SEARCH_TILE_EDGE, bounds.yIncrement());
            List<Range> smallZ = ranges(
                    outer.z().min(), outer.z().max(), SEARCH_TILE_EDGE, bounds.zIncrement());
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
            int snapshotHalo,
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

    /** 搜索线程可见的只读分页视图。页对象不可变，同轮任务可以安全共享。 */
    public static final class SnapshotBlockView implements SignalGetter {
        private final SnapshotPage[] pages;
        private final int minPageX;
        private final int minPageY;
        private final int minPageZ;
        private final int pageCountX;
        private final int pageCountY;
        private final int pageCountZ;
        private final BlockState fallback;
        private final int worldMinY;
        private final int height;

        SnapshotBlockView(
                SnapshotPage[] pages,
                int minPageX,
                int minPageY,
                int minPageZ,
                int pageCountX,
                int pageCountY,
                int pageCountZ,
                BlockState fallback,
                int worldMinY,
                int height) {
            this.pages = pages;
            this.minPageX = minPageX;
            this.minPageY = minPageY;
            this.minPageZ = minPageZ;
            this.pageCountX = pageCountX;
            this.pageCountY = pageCountY;
            this.pageCountZ = pageCountZ;
            this.fallback = fallback;
            this.worldMinY = worldMinY;
            this.height = height;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int pageX = Math.floorDiv(pos.getX(), SNAPSHOT_PAGE_EDGE) - minPageX;
            int pageY = Math.floorDiv(pos.getY(), SNAPSHOT_PAGE_EDGE) - minPageY;
            int pageZ = Math.floorDiv(pos.getZ(), SNAPSHOT_PAGE_EDGE) - minPageZ;
            if (pageX < 0 || pageX >= pageCountX
                    || pageY < 0 || pageY >= pageCountY
                    || pageZ < 0 || pageZ >= pageCountZ) {
                return fallback;
            }
            SnapshotPage page = pages[
                    (pageX * pageCountY + pageY) * pageCountZ + pageZ];
            return page == null ? fallback : page.get(pos, fallback);
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
            return worldMinY;
        }
    }

    /** One immutable, non-overlapping page captured by the scheduler thread. */
    static final class SnapshotPage {
        private final BlockState[] states;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;

        SnapshotPage(
                BlockState[] states,
                int minX,
                int minY,
                int minZ,
                int sizeX,
                int sizeY,
                int sizeZ) {
            this.states = states;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        BlockState get(BlockPos pos, BlockState fallback) {
            int x = pos.getX() - minX;
            int y = pos.getY() - minY;
            int z = pos.getZ() - minZ;
            if (x < 0 || x >= sizeX
                    || y < 0 || y >= sizeY
                    || z < 0 || z >= sizeZ) {
                return fallback;
            }
            return states[(x * sizeY + y) * sizeZ + z];
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

    private record PageKey(int x, int y, int z) {
    }

    private record PageRange(
            int minPageX,
            int minPageY,
            int minPageZ,
            int maxPageX,
            int maxPageY,
            int maxPageZ) {
        static PageRange around(
                TileSpec spec, int halo, int worldMinY, int worldMaxY) {
            int safeHalo = Math.max(0, halo);
            int minY = Math.max(worldMinY, spec.minY() - safeHalo);
            int maxY = Math.min(worldMaxY, spec.maxY() + safeHalo);
            return new PageRange(
                    Math.floorDiv(spec.minX() - safeHalo, SNAPSHOT_PAGE_EDGE),
                    Math.floorDiv(minY, SNAPSHOT_PAGE_EDGE),
                    Math.floorDiv(spec.minZ() - safeHalo, SNAPSHOT_PAGE_EDGE),
                    Math.floorDiv(spec.maxX() + safeHalo, SNAPSHOT_PAGE_EDGE),
                    Math.floorDiv(maxY, SNAPSHOT_PAGE_EDGE),
                    Math.floorDiv(spec.maxZ() + safeHalo, SNAPSHOT_PAGE_EDGE));
        }

        int countX() {
            return maxPageX - minPageX + 1;
        }

        int countY() {
            return maxPageY - minPageY + 1;
        }

        int countZ() {
            return maxPageZ - minPageZ + 1;
        }
    }

    @FunctionalInterface
    private interface StateReader {
        BlockState get(BlockPos pos);
    }

    /**
     * Scheduler-owned page cache. Pages are captured lazily, shared by every
     * request using the same source, and released after their last dependent
     * search task finishes.
     */
    private static final class SnapshotPageCache {
        private final StateReader reader;
        private final BlockState fallback;
        private final int worldMinY;
        private final int height;
        private final Map<PageKey, SnapshotPage> pages = new HashMap<>();
        private final Map<PageKey, Integer> remainingUses = new HashMap<>();
        private int coverageMinX = Integer.MAX_VALUE;
        private int coverageMinY = Integer.MAX_VALUE;
        private int coverageMinZ = Integer.MAX_VALUE;
        private int coverageMaxX = Integer.MIN_VALUE;
        private int coverageMaxY = Integer.MIN_VALUE;
        private int coverageMaxZ = Integer.MIN_VALUE;

        private SnapshotPageCache(
                StateReader reader,
                BlockState fallback,
                int worldMinY,
                int height) {
            this.reader = reader;
            this.fallback = fallback;
            this.worldMinY = worldMinY;
            this.height = height;
        }

        void include(SearchBounds bounds, int halo) {
            int safeHalo = Math.max(0, halo);
            coverageMinX = Math.min(coverageMinX, bounds.minX() - safeHalo);
            coverageMinY = Math.min(coverageMinY,
                    Math.max(worldMinY, bounds.minY() - safeHalo));
            coverageMinZ = Math.min(coverageMinZ, bounds.minZ() - safeHalo);
            coverageMaxX = Math.max(coverageMaxX, bounds.maxX() + safeHalo);
            coverageMaxY = Math.max(coverageMaxY,
                    Math.min(worldMinY + height, bounds.maxY() + safeHalo));
            coverageMaxZ = Math.max(coverageMaxZ, bounds.maxZ() + safeHalo);
        }

        void retain(PageRange range) {
            forEachPage(range, key -> remainingUses.merge(key, 1, Integer::sum));
        }

        SnapshotBlockView capture(PageRange range) {
            SnapshotPage[] viewPages = new SnapshotPage[
                    Math.multiplyExact(Math.multiplyExact(
                            range.countX(), range.countY()), range.countZ())];
            int index = 0;
            for (int pageX = range.minPageX(); pageX <= range.maxPageX(); pageX++) {
                for (int pageY = range.minPageY(); pageY <= range.maxPageY(); pageY++) {
                    for (int pageZ = range.minPageZ(); pageZ <= range.maxPageZ(); pageZ++) {
                        PageKey key = new PageKey(pageX, pageY, pageZ);
                        SnapshotPage page = pages.get(key);
                        if (page == null) {
                            page = capturePage(key);
                            pages.put(key, page);
                        }
                        viewPages[index++] = page;
                    }
                }
            }
            return new SnapshotBlockView(
                    viewPages,
                    range.minPageX(), range.minPageY(), range.minPageZ(),
                    range.countX(), range.countY(), range.countZ(),
                    fallback, worldMinY, height);
        }

        void release(PageRange range) {
            forEachPage(range, key -> {
                Integer remaining = remainingUses.get(key);
                if (remaining == null) return;
                if (remaining <= 1) {
                    remainingUses.remove(key);
                    pages.remove(key);
                } else {
                    remainingUses.put(key, remaining - 1);
                }
            });
        }

        private SnapshotPage capturePage(PageKey key) {
            int pageMinX = key.x() * SNAPSHOT_PAGE_EDGE;
            int pageMinY = key.y() * SNAPSHOT_PAGE_EDGE;
            int pageMinZ = key.z() * SNAPSHOT_PAGE_EDGE;
            int minX = Math.max(pageMinX, coverageMinX);
            int minY = Math.max(pageMinY, coverageMinY);
            int minZ = Math.max(pageMinZ, coverageMinZ);
            int maxX = Math.min(pageMinX + SNAPSHOT_PAGE_EDGE - 1, coverageMaxX);
            int maxY = Math.min(pageMinY + SNAPSHOT_PAGE_EDGE - 1, coverageMaxY);
            int maxZ = Math.min(pageMinZ + SNAPSHOT_PAGE_EDGE - 1, coverageMaxZ);
            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            BlockState[] states = new BlockState[
                    Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)];
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            int index = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        states[index++] = reader.get(mutable.set(x, y, z));
                    }
                }
            }
            return new SnapshotPage(
                    states, minX, minY, minZ, sizeX, sizeY, sizeZ);
        }

        private static void forEachPage(
                PageRange range, java.util.function.Consumer<PageKey> consumer) {
            for (int pageX = range.minPageX(); pageX <= range.maxPageX(); pageX++) {
                for (int pageY = range.minPageY(); pageY <= range.maxPageY(); pageY++) {
                    for (int pageZ = range.minPageZ(); pageZ <= range.maxPageZ(); pageZ++) {
                        consumer.accept(new PageKey(pageX, pageY, pageZ));
                    }
                }
            }
        }
    }

    private record WorkUnit(
            RequestWork work,
            TileSpec spec,
            SnapshotPageCache currentPages,
            @Nullable SnapshotPageCache requiredPages,
            PageRange pageRange) {
        void releasePages() {
            currentPages.release(pageRange);
            if (requiredPages != null) requiredPages.release(pageRange);
        }
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
            Map<ClientLevel, SnapshotPageCache> currentCaches =
                    new IdentityHashMap<>();
            Map<WorldSchematic, SnapshotPageCache> requiredCaches =
                    new IdentityHashMap<>();

            for (SearchRequest request : requests) {
                SnapshotPageCache currentCache = currentCaches.computeIfAbsent(
                        request.level(), level -> new SnapshotPageCache(
                                level::getBlockState,
                                Blocks.VOID_AIR.defaultBlockState(),
                                level.getMinY(),
                                level.getHeight()));
                currentCache.include(request.bounds(), request.snapshotHalo());
                if (request.includeSchematic() && request.schematic() != null) {
                    SnapshotPageCache requiredCache = requiredCaches.computeIfAbsent(
                            request.schematic(), schematic -> new SnapshotPageCache(
                                    schematic::getBlockState,
                                    Blocks.AIR.defaultBlockState(),
                                    request.level().getMinY(),
                                    request.level().getHeight()));
                    requiredCache.include(request.bounds(), request.snapshotHalo());
                }
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
                    SearchRequest request = work.request();
                    SnapshotPageCache currentPages = currentCaches.get(request.level());
                    SnapshotPageCache requiredPages = request.includeSchematic()
                            && request.schematic() != null
                            ? requiredCaches.get(request.schematic()) : null;
                    PageRange pageRange = PageRange.around(
                            spec,
                            request.snapshotHalo(),
                            request.level().getMinY(),
                            request.level().getMaxY());
                    currentPages.retain(pageRange);
                    if (requiredPages != null) requiredPages.retain(pageRange);
                    pending.addLast(new WorkUnit(
                            work, spec, currentPages, requiredPages, pageRange));
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
                    captureSmallSnapshot(unit));
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
            unit.releasePages();
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
