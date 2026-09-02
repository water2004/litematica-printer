package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.concurrent.AsyncRoundCoordinator;
import me.aleksilassila.litematica.printer.core.job.JobPool;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全模块共用的异步搜索管线。
 *
 * <p>客户端主线程只提交不可变的扫描描述。唯一调度线程直接把范围切成固定大小的
 * 小快照块，并且最多只保持 {@code maxThreads} 个搜索任务正在执行；不会在工作池
 * 忙碌时叠加下一轮，也不会为填满线程数而重复提交小块。</p>
 */
public final class AsyncSearchCoordinator {
    public static final AsyncSearchCoordinator INSTANCE = new AsyncSearchCoordinator();

    private static final boolean PROFILE_SCAN = Boolean.getBoolean(
            "litematica-printer.gametest.scanPerformance")
            || Boolean.getBoolean(
                    "litematica-printer.gametest.fullPrintPerformance");
    private static final AtomicLong PROFILE_SEQUENCE = new AtomicLong();
    private static final ConcurrentLinkedQueue<RoundProfile> ROUND_PROFILES =
            new ConcurrentLinkedQueue<>();
    @Nullable
    private static volatile RoundProfile lastRoundProfile;

    /** 预编译形状掩码的叶块体积上限；小于旧的固定 8^3 调度块。 */
    static final int MASK_TILE_MAX_VOLUME = 128;
    /** 世界与投影按互不重叠的小页读取，每个位置在一轮内最多读取一次。 */
    static final int SNAPSHOT_PAGE_EDGE = 8;
    private static final Map<ShapeMaskKey, CompiledShapeMask> SHAPE_MASKS =
            new ConcurrentHashMap<>();

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

    public static void resetRoundProfileForTesting() {
        lastRoundProfile = null;
        ROUND_PROFILES.clear();
    }

    @Nullable
    public static RoundProfile getLastRoundProfileForTesting() {
        return lastRoundProfile;
    }

    public static List<RoundProfile> drainRoundProfilesForTesting() {
        List<RoundProfile> profiles = new ArrayList<>();
        RoundProfile profile;
        while ((profile = ROUND_PROFILES.poll()) != null) {
            profiles.add(profile);
        }
        return List.copyOf(profiles);
    }

    private static SnapshotCapture captureSmallSnapshot(WorkUnit unit) {
        SearchTileSnapshot cached = unit.shared().snapshot();
        if (cached != null) return new SnapshotCapture(cached, false);

        CaptureGroup group = unit.shared().group();
        SearchRequest request = group.representative();
        TileSpec spec = unit.shared().spec();
        ViewCapture currentCapture = unit.shared().currentPages()
                .capture(unit.shared().pageRange());
        ViewCapture requiredCapture = unit.shared().requiredPages() == null
                ? ViewCapture.empty(emptyView(
                        Blocks.AIR.defaultBlockState(), request.level()))
                : unit.shared().requiredPages().capture(unit.shared().pageRange());
        SnapshotBlockView currentView = currentCapture.view();
        SnapshotBlockView requiredView = requiredCapture.view();
        int capacity = Math.toIntExact(Math.min(spec.volume(), Integer.MAX_VALUE));
        long[] positions = new long[capacity];
        BlockState[] currentStates = new BlockState[capacity];
        BlockState[] requiredStates = unit.shared().requiredPages() != null
                ? new BlockState[capacity] : null;
        long[] targetMasks = new long[capacity];
        int[] acceptedCounts = new int[group.targets().size()];
        int[] size = {0};

        forEachPosition(spec, request.bounds(), pos -> {
            if (!unit.shared().shapeFull()
                    && !unit.shared().shapeMask().containsWorld(
                            pos.getX(), pos.getY(), pos.getZ(),
                            unit.shared().originX(),
                            unit.shared().originY(),
                            unit.shared().originZ())) return;

            long targetMask = 0L;
            for (int targetIndex = 0;
                    targetIndex < group.targets().size(); targetIndex++) {
                RequestWork target = group.targets().get(targetIndex);
                CoverageMode mode = unit.shared().mode(targetIndex);
                if (mode == CoverageMode.FULL
                        || (mode == CoverageMode.PARTIAL
                        && target.workspace().contains(pos))) {
                    targetMask |= target.targetBit();
                    acceptedCounts[targetIndex]++;
                }
            }
            if (targetMask == 0L) return;

            int index = size[0]++;
            positions[index] = pos.asLong();
            currentStates[index] = currentView.getBlockState(pos);
            targetMasks[index] = targetMask;
            if (requiredStates != null) {
                requiredStates[index] = requiredView.getBlockState(pos);
            }
        });

        SearchTileSnapshot snapshot = new SearchTileSnapshot(
                spec.ordinal(),
                spec.volume(),
                currentCapture.newlyCapturedPositions(),
                requiredCapture.newlyCapturedPositions(),
                positions,
                currentStates,
                requiredStates,
                targetMasks,
                acceptedCounts,
                size[0],
                currentView,
                requiredView);
        unit.shared().setSnapshot(snapshot);
        return new SnapshotCapture(snapshot, true);
    }

    private static SearchTileSnapshot emptySnapshot(
            WorkUnit unit) {
        SearchRequest request = unit.work().request();
        TileSpec spec = unit.shared().spec();
        SnapshotBlockView current = emptyView(
                Blocks.VOID_AIR.defaultBlockState(), request.level());
        SnapshotBlockView required = emptyView(
                Blocks.AIR.defaultBlockState(), request.level());
        return new SearchTileSnapshot(
                spec.ordinal(), spec.volume(), 0L, 0L,
                new long[0], new BlockState[0], null,
                new long[0], new int[unit.shared().group().targets().size()], 0,
                current, required);
    }

    private static SnapshotBlockView emptyView(
            BlockState fallback, ClientLevel level) {
        return new SnapshotBlockView(
                new SnapshotPage[0], 0, 0, 0, 0, 0, 0,
                fallback, level.getMinY(), level.getHeight());
    }

    private static CompiledShapeMask shapeMask(SearchRequest request) {
        ShapeMaskKey key = new ShapeMaskKey(
                request.shape(), Double.doubleToLongBits(request.range()));
        return SHAPE_MASKS.computeIfAbsent(
                key, ignored -> CompiledShapeMask.compile(
                        request.shape(), request.range()));
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
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        switch (bounds.iterationOrder()) {
            case XYZ -> {
                for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                    for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                        for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                            consumer.accept(mutable.set(x, y, z));
            }
            case XZY -> {
                for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                    for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                        for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                            consumer.accept(mutable.set(x, y, z));
            }
            case YXZ -> {
                for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                    for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                        for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                            consumer.accept(mutable.set(x, y, z));
            }
            case YZX -> {
                for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                    for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                        for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                            consumer.accept(mutable.set(x, y, z));
            }
            case ZXY -> {
                for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                    for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                        for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                            consumer.accept(mutable.set(x, y, z));
            }
            case ZYX -> {
                for (int x = xStart; continuing(x, xEnd, xStep); x += xStep)
                    for (int y = yStart; continuing(y, yEnd, yStep); y += yStep)
                        for (int z = zStart; continuing(z, zEnd, zStep); z += zStep)
                            consumer.accept(mutable.set(x, y, z));
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

        private boolean contains(TileSpec tile) {
            return tile.minX() >= minX && tile.maxX() <= maxX
                    && tile.minY() >= minY && tile.maxY() <= maxY
                    && tile.minZ() >= minZ && tile.maxZ() <= maxZ;
        }

        private boolean intersects(TileSpec tile) {
            return tile.maxX() >= minX && tile.minX() <= maxX
                    && tile.maxY() >= minY && tile.minY() <= maxY
                    && tile.maxZ() >= minZ && tile.minZ() <= maxZ;
        }

        public long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }
    }

    /** A single reusable cursor over one immutable tile snapshot. */
    public static final class SearchBlockSnapshot {
        private final SearchTileSnapshot tile;
        private final long targetBit;
        private int index = -1;
        @Nullable
        private BlockPos pos;

        private SearchBlockSnapshot(SearchTileSnapshot tile, long targetBit) {
            this.tile = tile;
            this.targetBit = targetBit;
        }

        public boolean advance() {
            while (++index < tile.blockCount) {
                if ((tile.targetMasks[index] & targetBit) == 0L) continue;
                pos = null;
                return true;
            }
            return false;
        }

        public BlockPos pos() {
            if (pos == null) pos = BlockPos.of(tile.positions[index]);
            return pos;
        }

        public BlockState currentState() {
            return tile.currentStates[index];
        }

        @Nullable
        public BlockState requiredState() {
            BlockState[] states = tile.requiredStates;
            return states == null ? null : states[index];
        }

        public SnapshotBlockView currentView() {
            return tile.currentView();
        }

        public SnapshotBlockView requiredView() {
            return tile.requiredView();
        }
    }

    public static final class SearchTileSnapshot {
        private final int ordinal;
        private final long scannedPositions;
        private final long capturedCurrentPositions;
        private final long capturedRequiredPositions;
        private final long[] positions;
        private final BlockState[] currentStates;
        @Nullable
        private final BlockState[] requiredStates;
        private final long[] targetMasks;
        private final int[] acceptedCounts;
        private final int blockCount;
        private final SnapshotBlockView currentView;
        private final SnapshotBlockView requiredView;

        private SearchTileSnapshot(
                int ordinal,
                long scannedPositions,
                long capturedCurrentPositions,
                long capturedRequiredPositions,
                long[] positions,
                BlockState[] currentStates,
                @Nullable BlockState[] requiredStates,
                long[] targetMasks,
                int[] acceptedCounts,
                int blockCount,
                SnapshotBlockView currentView,
                SnapshotBlockView requiredView) {
            this.ordinal = ordinal;
            this.scannedPositions = scannedPositions;
            this.capturedCurrentPositions = capturedCurrentPositions;
            this.capturedRequiredPositions = capturedRequiredPositions;
            this.positions = positions;
            this.currentStates = currentStates;
            this.requiredStates = requiredStates;
            this.targetMasks = targetMasks;
            this.acceptedCounts = acceptedCounts;
            this.blockCount = blockCount;
            this.currentView = currentView;
            this.requiredView = requiredView;
        }

        public int ordinal() {
            return ordinal;
        }

        public long scannedPositions() {
            return scannedPositions;
        }

        public long capturedCurrentPositions() {
            return capturedCurrentPositions;
        }

        public long capturedRequiredPositions() {
            return capturedRequiredPositions;
        }

        public int blockCount(long targetBit) {
            int index = Long.numberOfTrailingZeros(targetBit);
            return index < acceptedCounts.length ? acceptedCounts[index] : 0;
        }

        public SnapshotBlockView currentView() {
            return currentView;
        }

        public SnapshotBlockView requiredView() {
            return requiredView;
        }

        public SearchBlockSnapshot cursor(long targetBit) {
            return new SearchBlockSnapshot(this, targetBit);
        }
    }

    /**
     * 搜索线程可见的只读分页视图。页对象不可变，同轮任务可以安全共享。
     */
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

        long volume() {
            return (long) sizeX * sizeY * sizeZ;
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

    private enum CoverageMode {
        EMPTY,
        PARTIAL,
        FULL
    }

    private enum ShapeRelation {
        EMPTY,
        PARTIAL,
        FULL
    }

    private record ShapeMaskKey(RadiusShapeType shape, long rangeBits) {
    }

    private record RelativeTile(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            boolean shapeFull) {
        long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }

        @Nullable
        TileSpec translateAndClip(
                int ordinal,
                int originX,
                int originY,
                int originZ,
                SearchBounds bounds) {
            int worldMinX = Math.max(bounds.minX(), originX + minX);
            int worldMinY = Math.max(bounds.minY(), originY + minY);
            int worldMinZ = Math.max(bounds.minZ(), originZ + minZ);
            int worldMaxX = Math.min(bounds.maxX(), originX + maxX);
            int worldMaxY = Math.min(bounds.maxY(), originY + maxY);
            int worldMaxZ = Math.min(bounds.maxZ(), originZ + maxZ);
            if (worldMinX > worldMaxX
                    || worldMinY > worldMaxY
                    || worldMinZ > worldMaxZ) return null;
            return new TileSpec(
                    ordinal,
                    worldMinX, worldMinY, worldMinZ,
                    worldMaxX, worldMaxY, worldMaxZ);
        }
    }

    /**
     * Immutable, translation-independent spatial mask. It compiles a configured
     * shape into small boxes and marks boxes wholly inside the shape so the hot
     * loop only evaluates the integer predicate on the boundary.
     */
    private static final class CompiledShapeMask {
        private final RadiusShapeType shape;
        private final double range;
        private final List<RelativeTile> tiles;

        private CompiledShapeMask(
                RadiusShapeType shape,
                double range,
                List<RelativeTile> tiles) {
            this.shape = shape;
            this.range = range;
            this.tiles = List.copyOf(tiles);
        }

        static CompiledShapeMask compile(RadiusShapeType shape, double range) {
            int extent = Math.max(0, (int) Math.ceil(range));
            List<RelativeTile> tiles = new ArrayList<>();
            split(shape, range,
                    -extent, -extent, -extent,
                    extent, extent, extent,
                    tiles);
            return new CompiledShapeMask(shape, range, tiles);
        }

        private static void split(
                RadiusShapeType shape,
                double range,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ,
                List<RelativeTile> output) {
            ShapeRelation relation = relation(
                    shape, range, minX, minY, minZ, maxX, maxY, maxZ);
            if (relation == ShapeRelation.EMPTY) return;
            long volume = ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
            if (volume <= MASK_TILE_MAX_VOLUME) {
                output.add(new RelativeTile(
                        minX, minY, minZ, maxX, maxY, maxZ,
                        relation == ShapeRelation.FULL));
                return;
            }

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            if (sizeX >= sizeY && sizeX >= sizeZ) {
                int middle = minX + (sizeX >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        middle, maxY, maxZ, output);
                split(shape, range, middle + 1, minY, minZ,
                        maxX, maxY, maxZ, output);
            } else if (sizeY >= sizeZ) {
                int middle = minY + (sizeY >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        maxX, middle, maxZ, output);
                split(shape, range, minX, middle + 1, minZ,
                        maxX, maxY, maxZ, output);
            } else {
                int middle = minZ + (sizeZ >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        maxX, maxY, middle, output);
                split(shape, range, minX, minY, middle + 1,
                        maxX, maxY, maxZ, output);
            }
        }

        private static ShapeRelation relation(
                RadiusShapeType shape,
                double range,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ) {
            long minAbsX = intervalMinAbs(minX, maxX);
            long minAbsY = intervalMinAbs(minY, maxY);
            long minAbsZ = intervalMinAbs(minZ, maxZ);
            long maxAbsX = Math.max(Math.abs((long) minX), Math.abs((long) maxX));
            long maxAbsY = Math.max(Math.abs((long) minY), Math.abs((long) maxY));
            long maxAbsZ = Math.max(Math.abs((long) minZ), Math.abs((long) maxZ));
            double minimum;
            double maximum;
            switch (shape) {
                case SPHERE -> {
                    minimum = minAbsX * minAbsX
                            + minAbsY * minAbsY
                            + minAbsZ * minAbsZ;
                    maximum = maxAbsX * maxAbsX
                            + maxAbsY * maxAbsY
                            + maxAbsZ * maxAbsZ;
                    range *= range;
                }
                case OCTAHEDRON -> {
                    minimum = minAbsX + minAbsY + minAbsZ;
                    maximum = maxAbsX + maxAbsY + maxAbsZ;
                }
                case CUBE -> {
                    minimum = Math.max(minAbsX, Math.max(minAbsY, minAbsZ));
                    maximum = Math.max(maxAbsX, Math.max(maxAbsY, maxAbsZ));
                }
                default -> throw new IllegalStateException("Unknown shape " + shape);
            }
            if (minimum > range) return ShapeRelation.EMPTY;
            return maximum <= range ? ShapeRelation.FULL : ShapeRelation.PARTIAL;
        }

        private static long intervalMinAbs(int min, int max) {
            if (min <= 0 && max >= 0) return 0L;
            return Math.min(Math.abs((long) min), Math.abs((long) max));
        }

        boolean containsWorld(
                int x, int y, int z,
                int originX, int originY, int originZ) {
            long dx = (long) x - originX;
            long dy = (long) y - originY;
            long dz = (long) z - originZ;
            return switch (shape) {
                case SPHERE -> dx * dx + dy * dy + dz * dz <= range * range;
                case OCTAHEDRON -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= range;
                case CUBE -> Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= range;
            };
        }

        List<RelativeTile> orderedTiles(SearchBounds bounds) {
            List<RelativeTile> ordered = new ArrayList<>(tiles);
            ordered.sort(tileComparator(bounds));
            return ordered;
        }

        private static Comparator<RelativeTile> tileComparator(SearchBounds bounds) {
            int[] axes = switch (bounds.iterationOrder()) {
                case XYZ -> new int[]{2, 1, 0};
                case XZY -> new int[]{1, 2, 0};
                case YXZ -> new int[]{2, 0, 1};
                case YZX -> new int[]{0, 2, 1};
                case ZXY -> new int[]{1, 0, 2};
                case ZYX -> new int[]{0, 1, 2};
            };
            return (left, right) -> {
                for (int axis : axes) {
                    boolean increment = switch (axis) {
                        case 0 -> bounds.xIncrement();
                        case 1 -> bounds.yIncrement();
                        default -> bounds.zIncrement();
                    };
                    int leftValue = coordinate(left, axis, increment);
                    int rightValue = coordinate(right, axis, increment);
                    int compared = increment
                            ? Integer.compare(leftValue, rightValue)
                            : Integer.compare(rightValue, leftValue);
                    if (compared != 0) return compared;
                }
                return 0;
            };
        }

        private static int coordinate(
                RelativeTile tile, int axis, boolean increment) {
            return switch (axis) {
                case 0 -> increment ? tile.minX() : tile.maxX();
                case 1 -> increment ? tile.minY() : tile.maxY();
                default -> increment ? tile.minZ() : tile.maxZ();
            };
        }
    }

    private static final class CompiledWorkspace {
        private final boolean unrestricted;
        private final List<SearchBounds> boxes;

        private CompiledWorkspace(boolean unrestricted, List<SearchBounds> boxes) {
            this.unrestricted = unrestricted;
            this.boxes = boxes;
        }

        static CompiledWorkspace capture(SearchRequest request) {
            if (request.workspaceFilter() == WorkspaceFilter.NONE) {
                return new CompiledWorkspace(true, List.of());
            }
            List<SearchBounds> boxes;
            if (request.workspaceFilter() == WorkspaceFilter.SCHEMATIC) {
                SearchBounds limit = request.bounds();
                PrinterBox printerLimit = new PrinterBox(
                        limit.minX(), limit.minY(), limit.minZ(),
                        limit.maxX(), limit.maxY(), limit.maxZ());
                boxes = LitematicaUtils.getSchematicBoxesSnapshot(printerLimit)
                        .stream()
                        .map(box -> new SearchBounds(
                                box.minX, box.minY, box.minZ,
                                box.maxX, box.maxY, box.maxZ,
                                limit.iterationOrder(),
                                limit.xIncrement(), limit.yIncrement(),
                                limit.zIncrement()))
                        .toList();
            } else {
                boxes = request.selectionBoxes();
            }
            return new CompiledWorkspace(false, List.copyOf(boxes));
        }

        CoverageMode classify(TileSpec tile) {
            if (unrestricted) return CoverageMode.FULL;
            boolean intersects = false;
            for (SearchBounds box : boxes) {
                if (!box.intersects(tile)) continue;
                intersects = true;
                if (box.contains(tile)) return CoverageMode.FULL;
            }
            return intersects ? CoverageMode.PARTIAL : CoverageMode.EMPTY;
        }

        boolean contains(BlockPos pos) {
            if (unrestricted) return true;
            for (SearchBounds box : boxes) {
                if (box.contains(pos)) return true;
            }
            return false;
        }
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

    /**
     * Scheduler-owned page cache. Pages are captured lazily, shared by every
     * request using the same source, and released after their last dependent
     * search task finishes.
     */
    private static final class SnapshotPageCache {
        private final Level source;
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
                Level source,
                BlockState fallback,
                int worldMinY,
                int height) {
            this.source = source;
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
                    Math.min(worldMinY + height - 1, bounds.maxY() + safeHalo));
            coverageMaxZ = Math.max(coverageMaxZ, bounds.maxZ() + safeHalo);
        }

        void retain(PageRange range) {
            forEachPage(range, key -> remainingUses.merge(key, 1, Integer::sum));
        }

        ViewCapture capture(PageRange range) {
            SnapshotPage[] viewPages = new SnapshotPage[
                    Math.multiplyExact(Math.multiplyExact(
                            range.countX(), range.countY()), range.countZ())];
            long newlyCaptured = 0L;
            int index = 0;
            for (int pageX = range.minPageX(); pageX <= range.maxPageX(); pageX++) {
                for (int pageY = range.minPageY(); pageY <= range.maxPageY(); pageY++) {
                    for (int pageZ = range.minPageZ(); pageZ <= range.maxPageZ(); pageZ++) {
                        PageKey key = new PageKey(pageX, pageY, pageZ);
                        SnapshotPage page = pages.get(key);
                        if (page == null) {
                            page = capturePage(key);
                            pages.put(key, page);
                            newlyCaptured += page.volume();
                        }
                        viewPages[index++] = page;
                    }
                }
            }
            return new ViewCapture(
                    new SnapshotBlockView(
                            viewPages,
                            range.minPageX(), range.minPageY(), range.minPageZ(),
                            range.countX(), range.countY(), range.countZ(),
                            fallback, worldMinY, height),
                    newlyCaptured);
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
            var chunk = source.getChunk(
                    Math.floorDiv(minX, 16), Math.floorDiv(minZ, 16));
            var section = chunk.getSection(chunk.getSectionIndex(minY));
            int index = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        states[index++] = section.getBlockState(
                                x & 15, y & 15, z & 15);
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

    private record ViewCapture(
            SnapshotBlockView view,
            long newlyCapturedPositions) {
        static ViewCapture empty(SnapshotBlockView view) {
            return new ViewCapture(view, 0L);
        }
    }

    private record SnapshotCapture(
            SearchTileSnapshot snapshot,
            boolean newlyCreated) {
    }

    private record WorkUnit(
            RequestWork work,
            SharedTile shared) {
        void releasePages() {
            shared.releasePages();
        }
    }

    private record CapturedSearch(
            Module owner,
            Object context,
            SearchTileSnapshot snapshot,
            long targetBit) {
    }

    private static final class CaptureGroup {
        private final List<RequestWork> targets = new ArrayList<>();
        private final SearchRequest geometry;
        @Nullable
        private WorldSchematic schematic;
        private int maxHalo;
        private ArrayDeque<SharedTile> pendingTiles = new ArrayDeque<>();

        private CaptureGroup(RequestWork first) {
            this.geometry = first.request();
            add(first);
        }

        boolean canShare(SearchRequest request) {
            if (targets.size() >= Long.SIZE
                    || geometry.level() != request.level()
                    || !geometry.bounds().equals(request.bounds())
                    || !geometry.eyePos().equals(request.eyePos())
                    || Double.compare(geometry.range(), request.range()) != 0
                    || geometry.shape() != request.shape()) {
                return false;
            }
            WorldSchematic candidate = request.includeSchematic()
                    ? request.schematic() : null;
            return schematic == null || candidate == null || schematic == candidate;
        }

        void add(RequestWork work) {
            int targetIndex = targets.size();
            work.setTargetBit(1L << targetIndex);
            targets.add(work);
            SearchRequest request = work.request();
            if (request.includeSchematic() && request.schematic() != null) {
                schematic = request.schematic();
            }
            maxHalo = Math.max(maxHalo, request.snapshotHalo());
        }

        void initialize(
                SnapshotPageCache currentPages,
                @Nullable SnapshotPageCache requiredPages) {
            CompiledShapeMask mask = shapeMask(geometry);
            int originX = (int) Math.round(geometry.eyePos().x);
            int originY = (int) Math.round(geometry.eyePos().y);
            int originZ = (int) Math.round(geometry.eyePos().z);
            List<RelativeTile> relativeTiles =
                    mask.orderedTiles(geometry.bounds());
            pendingTiles = new ArrayDeque<>(relativeTiles.size());
            int ordinal = 0;
            for (RelativeTile relative : relativeTiles) {
                TileSpec spec = relative.translateAndClip(
                        ordinal++, originX, originY, originZ,
                        geometry.bounds());
                if (spec == null) continue;
                CoverageMode[] modes = new CoverageMode[targets.size()];
                boolean needed = false;
                for (int targetIndex = 0;
                        targetIndex < targets.size(); targetIndex++) {
                    CoverageMode mode = targets.get(targetIndex)
                            .workspace().classify(spec);
                    modes[targetIndex] = mode;
                    needed |= mode != CoverageMode.EMPTY;
                }
                if (!needed) continue;
                PageRange pageRange = PageRange.around(
                        spec,
                        maxHalo,
                        geometry.level().getMinY(),
                        geometry.level().getMaxY());
                pendingTiles.addLast(new SharedTile(
                        this, spec, currentPages, requiredPages, pageRange,
                        mask, relative.shapeFull(),
                        originX, originY, originZ, modes));
            }
        }

        SearchRequest representative() {
            return geometry;
        }

        List<RequestWork> targets() {
            return targets;
        }

        @Nullable
        WorldSchematic schematic() {
            return schematic;
        }

        int maxHalo() {
            return maxHalo;
        }

        @Nullable
        SharedTile pollTile() {
            return pendingTiles.pollFirst();
        }
    }

    private static final class SharedTile {
        private final CaptureGroup group;
        private final TileSpec spec;
        private final SnapshotPageCache currentPages;
        @Nullable
        private final SnapshotPageCache requiredPages;
        private final PageRange pageRange;
        private final CompiledShapeMask shapeMask;
        private final boolean shapeFull;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final CoverageMode[] modes;
        @Nullable
        private SearchTileSnapshot snapshot;

        private SharedTile(
                CaptureGroup group,
                TileSpec spec,
                SnapshotPageCache currentPages,
                @Nullable SnapshotPageCache requiredPages,
                PageRange pageRange,
                CompiledShapeMask shapeMask,
                boolean shapeFull,
                int originX,
                int originY,
                int originZ,
                CoverageMode[] modes) {
            this.group = group;
            this.spec = spec;
            this.currentPages = currentPages;
            this.requiredPages = requiredPages;
            this.pageRange = pageRange;
            this.shapeMask = shapeMask;
            this.shapeFull = shapeFull;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.modes = modes;
        }

        void retainPages() {
            currentPages.retain(pageRange);
            if (requiredPages != null) requiredPages.retain(pageRange);
        }

        void releasePages() {
            currentPages.release(pageRange);
            if (requiredPages != null) requiredPages.release(pageRange);
        }

        CaptureGroup group() {
            return group;
        }

        TileSpec spec() {
            return spec;
        }

        SnapshotPageCache currentPages() {
            return currentPages;
        }

        @Nullable
        SnapshotPageCache requiredPages() {
            return requiredPages;
        }

        PageRange pageRange() {
            return pageRange;
        }

        CompiledShapeMask shapeMask() {
            return shapeMask;
        }

        boolean shapeFull() {
            return shapeFull;
        }

        int originX() {
            return originX;
        }

        int originY() {
            return originY;
        }

        int originZ() {
            return originZ;
        }

        CoverageMode mode(int targetIndex) {
            return modes[targetIndex];
        }

        boolean includes(RequestWork target) {
            int targetIndex = Long.numberOfTrailingZeros(target.targetBit());
            return targetIndex < modes.length
                    && modes[targetIndex] != CoverageMode.EMPTY;
        }

        @Nullable
        SearchTileSnapshot snapshot() {
            return snapshot;
        }

        void setSnapshot(SearchTileSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    private static final class RequestWork {
        private final SearchRequest request;
        private final CompiledWorkspace workspace;
        private final List<SearchTileResult> results;
        private final AtomicLongCounter completedPositions = new AtomicLongCounter();
        private long targetBit;
        private long capturedCurrentPositions;
        private long capturedRequiredPositions;
        private long acceptedPositions;
        private long plannedPositions;

        private RequestWork(SearchRequest request) {
            this.request = request;
            this.workspace = CompiledWorkspace.capture(request);
            this.results = new ArrayList<>();
        }

        SearchRequest request() {
            return request;
        }

        List<SearchTileResult> results() {
            return results;
        }

        CompiledWorkspace workspace() {
            return workspace;
        }

        long targetBit() {
            return targetBit;
        }

        void setTargetBit(long targetBit) {
            this.targetBit = targetBit;
        }

        AtomicLongCounter completedPositions() {
            return completedPositions;
        }

        void addPlannedPositions(long count) {
            plannedPositions += count;
        }

        long plannedPositions() {
            return plannedPositions;
        }

        void captured(SearchTileSnapshot snapshot, boolean newlyCreated) {
            if (newlyCreated) {
                capturedCurrentPositions += snapshot.capturedCurrentPositions();
                capturedRequiredPositions += snapshot.capturedRequiredPositions();
            }
            acceptedPositions += snapshot.blockCount(targetBit);
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
        private long startedNanos;
        private long planNanos;
        private long captureNanos;
        private final AtomicLong searchNanos = new AtomicLong();
        private long completionNanos;
        private long publishNanos;
        private long capturedTileSnapshots;

        private SearchRound(List<SearchRequest> requests) {
            this.requests = requests;
        }

        @Override
        public List<WorkUnit> prepare() {
            startedNanos = System.nanoTime();
            List<RequestWork> prepared = new ArrayList<>(requests.size());
            List<CaptureGroup> groups = new ArrayList<>();
            ArrayDeque<WorkUnit> pending = new ArrayDeque<>();
            Map<ClientLevel, SnapshotPageCache> currentCaches =
                    new IdentityHashMap<>();
            Map<WorldSchematic, SnapshotPageCache> requiredCaches =
                    new IdentityHashMap<>();

            for (SearchRequest request : requests) {
                RequestWork work = new RequestWork(request);
                prepared.add(work);
                CaptureGroup group = null;
                for (CaptureGroup candidate : groups) {
                    if (candidate.canShare(request)) {
                        group = candidate;
                        break;
                    }
                }
                if (group == null) {
                    groups.add(new CaptureGroup(work));
                } else {
                    group.add(work);
                }
            }
            requestWork = List.copyOf(prepared);

            for (CaptureGroup group : groups) {
                SearchRequest request = group.representative();
                SnapshotPageCache currentCache = currentCaches.computeIfAbsent(
                        request.level(), level -> new SnapshotPageCache(
                                level,
                                Blocks.VOID_AIR.defaultBlockState(),
                                level.getMinY(),
                                level.getHeight()));
                currentCache.include(request.bounds(), group.maxHalo());

                SnapshotPageCache requiredCache = null;
                if (group.schematic() != null) {
                    WorldSchematic schematic = group.schematic();
                    requiredCache = requiredCaches.computeIfAbsent(
                            schematic, value -> new SnapshotPageCache(
                                    value,
                                    Blocks.AIR.defaultBlockState(),
                                    request.level().getMinY(),
                                    request.level().getHeight()));
                    requiredCache.include(request.bounds(), group.maxHalo());
                }
                group.initialize(currentCache, requiredCache);
            }

            // 不同捕获几何的小块轮询交错；兼容请求共用同一小块快照。
            boolean added;
            do {
                added = false;
                for (CaptureGroup group : groups) {
                    SharedTile shared = group.pollTile();
                    if (shared == null) continue;
                    for (RequestWork target : group.targets()) {
                        if (!shared.includes(target)) continue;
                        shared.retainPages();
                        pending.addLast(new WorkUnit(target, shared));
                        target.addPlannedPositions(shared.spec().volume());
                    }
                    added = true;
                }
            } while (added);
            for (RequestWork work : requestWork) {
                work.request().owner().searchRoundStarted(
                        work.request(), work.plannedPositions());
            }
            planNanos = System.nanoTime() - startedNanos;
            return List.copyOf(pending);
        }

        @Override
        public CapturedSearch capture(WorkUnit unit) {
            long profileStarted = PROFILE_SCAN ? System.nanoTime() : 0L;
            SearchRequest request = unit.work().request();
            SnapshotCapture capture = captureSmallSnapshot(unit);
            if (PROFILE_SCAN) {
                if (capture.newlyCreated()) capturedTileSnapshots++;
                unit.work().captured(
                        capture.snapshot(), capture.newlyCreated());
            }
            CapturedSearch result = new CapturedSearch(
                    request.owner(),
                    request.context(),
                    capture.snapshot(),
                    unit.work().targetBit());
            if (PROFILE_SCAN) captureNanos += System.nanoTime() - profileStarted;
            return result;
        }

        @Override
        public SearchTileResult search(CapturedSearch captured) {
            long profileStarted = PROFILE_SCAN ? System.nanoTime() : 0L;
            try {
                return captured.owner().searchSnapshotTile(
                        captured.context(),
                        captured.snapshot(),
                        captured.targetBit());
            } finally {
                if (PROFILE_SCAN) {
                    searchNanos.addAndGet(System.nanoTime() - profileStarted);
                }
            }
        }

        @Override
        public void captureFailed(WorkUnit unit, Throwable throwable) {
            Reference.LOGGER.error(
                    "读取异步搜索小快照失败: {}",
                    unit.shared().spec(), throwable);
            SearchTileSnapshot snapshot = emptySnapshot(unit);
            unit.shared().setSnapshot(snapshot);
            completed(unit, emptyResult(snapshot));
        }

        @Override
        public SearchTileResult searchFailed(
                WorkUnit unit,
                CapturedSearch captured,
                Throwable throwable) {
            Reference.LOGGER.error(
                    "异步搜索小任务失败: {}",
                    unit.shared().spec(), throwable);
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
            long profileStarted = PROFILE_SCAN ? System.nanoTime() : 0L;
            unit.releasePages();
            RequestWork work = unit.work();
            work.results().add(result);
            long scanned = work.completedPositions().addAndGet(
                    result.scannedPositions());
            work.request().owner().searchRoundProgress(
                    work.request(), scanned, work.plannedPositions());
            if (PROFILE_SCAN) {
                completionNanos += System.nanoTime() - profileStarted;
            }
        }

        @Override
        public void finish() {
            for (RequestWork work : requestWork) {
                work.results().sort(Comparator.comparingInt(SearchTileResult::ordinal));
            }

            long scanNanos = System.nanoTime() - startedNanos;
            List<RequestProfile> profiles = PROFILE_SCAN
                    ? requestWork.stream().map(SearchRound::profile).toList()
                    : List.of();

            long publishStarted = PROFILE_SCAN ? System.nanoTime() : 0L;
            for (RequestWork work : requestWork) {
                work.request().owner().publishSearchRound(
                        work.request(), List.copyOf(work.results()));
            }
            if (PROFILE_SCAN) publishNanos = System.nanoTime() - publishStarted;

            if (PROFILE_SCAN) {
                RoundProfile profile = new RoundProfile(
                        PROFILE_SEQUENCE.incrementAndGet(), scanNanos, planNanos,
                        captureNanos, searchNanos.get(), completionNanos,
                        publishNanos,
                        capturedTileSnapshots, profiles);
                lastRoundProfile = profile;
                ROUND_PROFILES.add(profile);
            }
        }

        private static RequestProfile profile(RequestWork work) {
            long jobCount = 0L;
            long jobXor = 0L;
            long jobSum = 0L;
            long iceWaterJobs = 0L;
            for (SearchTileResult result : work.results()) {
                for (JobPool.Job<BlockPos, TransactionKey> job : result.jobs()) {
                    long fingerprint = fingerprint(job);
                    jobCount++;
                    jobXor ^= fingerprint;
                    jobSum += fingerprint;
                    if (job.key().category() == TransactionKey.Category.ICE_WATER) {
                        iceWaterJobs++;
                    }
                }
            }
            long stateReads = work.capturedCurrentPositions
                    + work.capturedRequiredPositions;
            return new RequestProfile(
                    work.request().owner().getId(),
                    work.request().bounds().volume(),
                    work.results().size(),
                    work.capturedCurrentPositions,
                    stateReads,
                    work.acceptedPositions,
                    jobCount,
                    jobXor,
                    jobSum,
                    iceWaterJobs);
        }

        private static long fingerprint(JobPool.Job<BlockPos, TransactionKey> job) {
            TransactionKey key = job.key();
            long value = job.position().asLong();
            value ^= (long) key.category().ordinal() << 56;
            if (key.primaryItem() != null) {
                value ^= (long) BuiltInRegistries.ITEM.getKey(key.primaryItem())
                        .toString().hashCode() << 17;
            }
            value ^= value >>> 30;
            value *= 0xbf58476d1ce4e5b9L;
            value ^= value >>> 27;
            value *= 0x94d049bb133111ebL;
            return value ^ value >>> 31;
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

    public record RoundProfile(
            long sequence,
            long scanNanos,
            long planNanos,
            long captureNanos,
            long searchNanos,
            long completionNanos,
            long publishNanos,
            long capturedTileSnapshots,
            List<RequestProfile> requests) {

        public long activeWorkNanos() {
            return planNanos + captureNanos + searchNanos
                    + completionNanos + publishNanos;
        }
    }

    public record RequestProfile(
            String moduleId,
            long boundsVolume,
            int tileCount,
            long capturedPositions,
            long stateReads,
            long acceptedPositions,
            long jobCount,
            long jobXor,
            long jobSum,
            long iceWaterJobs) {
    }
}
