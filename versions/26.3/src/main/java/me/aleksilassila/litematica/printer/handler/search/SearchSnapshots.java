package me.aleksilassila.litematica.printer.handler.search;

import java.util.List;
import me.aleksilassila.litematica.printer.core.job.JobPool;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CompiledShapeMask;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CompiledWorkspace;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CoverageMode;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.SearchBounds;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.TileSpec;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.SnapshotBlockView;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import static me.aleksilassila.litematica.printer.handler.search.SearchGeometry.continuing;
import static me.aleksilassila.litematica.printer.handler.search.SearchGeometry.shapeMask;

/** Worker-facing snapshot cursors, tile payloads and results. */
public final class SearchSnapshots {
    private SearchSnapshots() {}

    public static final class SearchBlockSnapshot {
        final SearchTileSnapshot tile;
        final int targetIndex;
        final int xStart;
        final int xEnd;
        final int yStart;
        final int yEnd;
        final int zStart;
        final int zEnd;
        final int xStep;
        final int yStep;
        final int zStep;
        int x;
        int y;
        int z;
        int acceptedPositions;
        boolean started;
        @Nullable
        BlockPos pos;
        BlockState currentState;
        @Nullable
        BlockState requiredState;

        SearchBlockSnapshot(SearchTileSnapshot tile, long targetBit) {
            this.tile = tile;
            this.targetIndex = Long.numberOfTrailingZeros(targetBit);
            SearchBounds bounds = tile.bounds;
            TileSpec spec = tile.spec;
            this.xStart = bounds.xIncrement() ? spec.minX() : spec.maxX();
            this.xEnd = bounds.xIncrement() ? spec.maxX() : spec.minX();
            this.yStart = bounds.yIncrement() ? spec.minY() : spec.maxY();
            this.yEnd = bounds.yIncrement() ? spec.maxY() : spec.minY();
            this.zStart = bounds.zIncrement() ? spec.minZ() : spec.maxZ();
            this.zEnd = bounds.zIncrement() ? spec.maxZ() : spec.minZ();
            this.xStep = bounds.xIncrement() ? 1 : -1;
            this.yStep = bounds.yIncrement() ? 1 : -1;
            this.zStep = bounds.zIncrement() ? 1 : -1;
        }

        public boolean advance() {
            if (tile.empty || targetIndex >= tile.modes.length) return false;
            CoverageMode mode = tile.modes[targetIndex];
            if (mode == CoverageMode.EMPTY) return false;
            while (advanceCoordinate()) {
                if (!tile.shapeFull
                        && !tile.shapeMask.containsWorld(
                                x, y, z,
                                tile.originX, tile.originY, tile.originZ)) {
                    continue;
                }
                if (mode == CoverageMode.PARTIAL
                        && !tile.workspaces[targetIndex].contains(x, y, z)) {
                    continue;
                }
                pos = null;
                int pageIndex = tile.currentView.pageIndex(x, y, z);
                currentState = tile.currentView.getBlockState(
                        pageIndex, x, y, z);
                requiredState = tile.includeRequired
                        ? tile.requiredView.getBlockState(
                                pageIndex, x, y, z) : null;
                acceptedPositions++;
                return true;
            }
            return false;
        }

        boolean advanceCoordinate() {
            if (!started) {
                started = true;
                x = xStart;
                y = yStart;
                z = zStart;
                return true;
            }
            return switch (tile.bounds.iterationOrder()) {
                case XYZ -> advanceXyz();
                case XZY -> advanceXzy();
                case YXZ -> advanceYxz();
                case YZX -> advanceYzx();
                case ZXY -> advanceZxy();
                case ZYX -> advanceZyx();
            };
        }

        boolean advanceXyz() {
            x += xStep;
            if (continuing(x, xEnd, xStep)) return true;
            x = xStart;
            y += yStep;
            if (continuing(y, yEnd, yStep)) return true;
            y = yStart;
            z += zStep;
            return continuing(z, zEnd, zStep);
        }

        boolean advanceXzy() {
            x += xStep;
            if (continuing(x, xEnd, xStep)) return true;
            x = xStart;
            z += zStep;
            if (continuing(z, zEnd, zStep)) return true;
            z = zStart;
            y += yStep;
            return continuing(y, yEnd, yStep);
        }

        boolean advanceYxz() {
            y += yStep;
            if (continuing(y, yEnd, yStep)) return true;
            y = yStart;
            x += xStep;
            if (continuing(x, xEnd, xStep)) return true;
            x = xStart;
            z += zStep;
            return continuing(z, zEnd, zStep);
        }

        boolean advanceYzx() {
            y += yStep;
            if (continuing(y, yEnd, yStep)) return true;
            y = yStart;
            z += zStep;
            if (continuing(z, zEnd, zStep)) return true;
            z = zStart;
            x += xStep;
            return continuing(x, xEnd, xStep);
        }

        boolean advanceZxy() {
            z += zStep;
            if (continuing(z, zEnd, zStep)) return true;
            z = zStart;
            x += xStep;
            if (continuing(x, xEnd, xStep)) return true;
            x = xStart;
            y += yStep;
            return continuing(y, yEnd, yStep);
        }

        boolean advanceZyx() {
            z += zStep;
            if (continuing(z, zEnd, zStep)) return true;
            z = zStart;
            y += yStep;
            if (continuing(y, yEnd, yStep)) return true;
            y = yStart;
            x += xStep;
            return continuing(x, xEnd, xStep);
        }

        public BlockPos pos() {
            if (pos == null) pos = new BlockPos(x, y, z);
            return pos;
        }

        public BlockState currentState() {
            return currentState;
        }

        @Nullable
        public BlockState requiredState() {
            return requiredState;
        }

        public int acceptedPositions() {
            return acceptedPositions;
        }

        public SnapshotBlockView currentView() {
            return tile.currentView();
        }

        public SnapshotBlockView requiredView() {
            return tile.requiredView();
        }
    }

    public static final class SearchTileSnapshot {
        final TileSpec spec;
        final SearchBounds bounds;
        final long capturedCurrentPositions;
        final long capturedRequiredPositions;
        final SnapshotBlockView currentView;
        final SnapshotBlockView requiredView;
        final boolean includeRequired;
        final CompiledShapeMask shapeMask;
        final boolean shapeFull;
        final int originX;
        final int originY;
        final int originZ;
        final CoverageMode[] modes;
        final CompiledWorkspace[] workspaces;
        final boolean empty;

        SearchTileSnapshot(
                TileSpec spec,
                SearchBounds bounds,
                long capturedCurrentPositions,
                long capturedRequiredPositions,
                SnapshotBlockView currentView,
                SnapshotBlockView requiredView,
                boolean includeRequired,
                CompiledShapeMask shapeMask,
                boolean shapeFull,
                int originX,
                int originY,
                int originZ,
                CoverageMode[] modes,
                CompiledWorkspace[] workspaces,
                boolean empty) {
            this.spec = spec;
            this.bounds = bounds;
            this.capturedCurrentPositions = capturedCurrentPositions;
            this.capturedRequiredPositions = capturedRequiredPositions;
            this.currentView = currentView;
            this.requiredView = requiredView;
            this.includeRequired = includeRequired;
            this.shapeMask = shapeMask;
            this.shapeFull = shapeFull;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.modes = modes;
            this.workspaces = workspaces;
            this.empty = empty;
        }

        public int ordinal() {
            return spec.ordinal();
        }

        public long scannedPositions() {
            return spec.volume();
        }

        public long capturedCurrentPositions() {
            return capturedCurrentPositions;
        }

        public long capturedRequiredPositions() {
            return capturedRequiredPositions;
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

    public record SearchTileResult(
            int ordinal,
            long scannedPositions,
            long acceptedPositions,
            List<JobPool.Job<BlockPos, TransactionKey>> jobs,
            @Nullable Object payload) {
        public SearchTileResult(
                int ordinal,
                long scannedPositions,
                List<JobPool.Job<BlockPos, TransactionKey>> jobs,
                @Nullable Object payload) {
            this(ordinal, scannedPositions, 0L, jobs, payload);
        }

        public static SearchTileResult jobs(
                SearchTileSnapshot snapshot,
                List<JobPool.Job<BlockPos, TransactionKey>> jobs) {
            return new SearchTileResult(
                    snapshot.ordinal(), snapshot.scannedPositions(), List.copyOf(jobs), null);
        }
    }
}
