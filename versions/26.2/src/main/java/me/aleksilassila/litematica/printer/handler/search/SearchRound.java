package me.aleksilassila.litematica.printer.handler.search;

import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import me.aleksilassila.litematica.printer.core.concurrent.AsyncRoundCoordinator;
import me.aleksilassila.litematica.printer.core.job.JobPool;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.search.AsyncSearchCoordinator.SearchRequest;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CompiledShapeMask;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CompiledWorkspace;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.CoverageMode;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.RelativeTile;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.TileSpec;
import me.aleksilassila.litematica.printer.handler.search.SearchProfile.RequestProfile;
import me.aleksilassila.litematica.printer.handler.search.SearchProfile.RoundProfile;
import me.aleksilassila.litematica.printer.handler.search.SearchSnapshots.SearchTileResult;
import me.aleksilassila.litematica.printer.handler.search.SearchSnapshots.SearchTileSnapshot;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.PageRange;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.PageSlot;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.PageWindow;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.SnapshotBlockView;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.SnapshotPageCache;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages.ViewCapture;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.Reference;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import static me.aleksilassila.litematica.printer.handler.search.SearchGeometry.shapeMask;
import static me.aleksilassila.litematica.printer.handler.search.SearchProfile.lastRoundProfile;
import static me.aleksilassila.litematica.printer.handler.search.SearchProfile.PROFILE_SCAN;
import static me.aleksilassila.litematica.printer.handler.search.SearchProfile.PROFILE_SEQUENCE;
import static me.aleksilassila.litematica.printer.handler.search.SearchProfile.ROUND_PROFILES;

/** Plan shared captures, dispatch bounded work and publish one round. */
final class SearchRound implements AsyncRoundCoordinator.Round<SearchRound.WorkUnit, SearchRound.CapturedSearch, SearchTileResult> {

    final List<SearchRequest> requests;
    List<RequestWork> requestWork = List.of();
    long startedNanos;
    long planNanos;
    long captureNanos;
    final AtomicLong searchNanos = new AtomicLong();
    long completionNanos;
    long publishNanos;
    long capturedTileSnapshots;

    SearchRound(List<SearchRequest> requests) {
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

    static SearchTileResult emptyResult(SearchTileSnapshot snapshot) {
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
        if (PROFILE_SCAN) work.addAcceptedPositions(
                result.acceptedPositions());
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

    static RequestProfile profile(RequestWork work) {
        long jobCount = 0L;
        long jobXor = 0L;
        long jobSum = 0L;
        long chainBreakJobs = 0L;
        for (SearchTileResult result : work.results()) {
            for (JobPool.Job<BlockPos, TransactionKey> job : result.jobs()) {
                long fingerprint = fingerprint(job);
                jobCount++;
                jobXor ^= fingerprint;
                jobSum += fingerprint;
                if (job.key().category() == TransactionKey.Category.CHAIN_BREAK) {
                    chainBreakJobs++;
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
                chainBreakJobs);
    }

    static long fingerprint(JobPool.Job<BlockPos, TransactionKey> job) {
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

    record SnapshotCapture(
            SearchTileSnapshot snapshot,
            boolean newlyCreated) {
    }

    record WorkUnit(
            RequestWork work,
            SharedTile shared) {
        void releasePages() {
            shared.releasePages();
        }
    }

    record CapturedSearch(
            Module owner,
            Object context,
            SearchTileSnapshot snapshot,
            long targetBit) {
    }

    static final class CaptureGroup {
        final List<RequestWork> targets = new ArrayList<>();
        final SearchRequest geometry;
        @Nullable
        WorldSchematic schematic;
        int maxHalo;
        CompiledWorkspace[] workspaces = new CompiledWorkspace[0];
        ArrayDeque<SharedTile> pendingTiles = new ArrayDeque<>();

        CaptureGroup(RequestWork first) {
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
            workspaces = targets.stream()
                    .map(RequestWork::workspace)
                    .toArray(CompiledWorkspace[]::new);
            pendingTiles = new ArrayDeque<>(relativeTiles.size());
            int ordinal = 0;
            for (RelativeTile relative : relativeTiles) {
                TileSpec spec = relative.translateAndClip(
                        ordinal++, originX, originY, originZ,
                        geometry.bounds());
                if (spec == null) continue;
                CoverageMode[] modes = new CoverageMode[targets.size()];
                int consumers = 0;
                for (int targetIndex = 0;
                        targetIndex < targets.size(); targetIndex++) {
                    CoverageMode mode = targets.get(targetIndex)
                            .workspace().classify(spec);
                    modes[targetIndex] = mode;
                    if (mode != CoverageMode.EMPTY) consumers++;
                }
                if (consumers == 0) continue;
                PageRange pageRange = PageRange.around(
                        spec,
                        maxHalo,
                        geometry.level().getMinY(),
                        geometry.level().getMaxY());
                PageWindow currentWindow = currentPages.register(
                        pageRange, consumers);
                PageWindow requiredWindow = requiredPages == null
                        ? null : requiredPages.register(pageRange, consumers);
                pendingTiles.addLast(new SharedTile(
                        this, spec, currentPages, requiredPages,
                        currentWindow, requiredWindow,
                        mask, relative.shapeFull(),
                        originX, originY, originZ, modes, workspaces));
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

    static final class SharedTile {
        final CaptureGroup group;
        final TileSpec spec;
        final SnapshotPageCache currentPages;
        @Nullable
        final SnapshotPageCache requiredPages;
        final PageWindow currentWindow;
        @Nullable
        final PageWindow requiredWindow;
        final CompiledShapeMask shapeMask;
        final boolean shapeFull;
        final int originX;
        final int originY;
        final int originZ;
        final CoverageMode[] modes;
        final CompiledWorkspace[] workspaces;
        @Nullable
        SearchTileSnapshot snapshot;

        SharedTile(
                CaptureGroup group,
                TileSpec spec,
                SnapshotPageCache currentPages,
                @Nullable SnapshotPageCache requiredPages,
                PageWindow currentWindow,
                @Nullable PageWindow requiredWindow,
                CompiledShapeMask shapeMask,
                boolean shapeFull,
                int originX,
                int originY,
                int originZ,
                CoverageMode[] modes,
                CompiledWorkspace[] workspaces) {
            this.group = group;
            this.spec = spec;
            this.currentPages = currentPages;
            this.requiredPages = requiredPages;
            this.currentWindow = currentWindow;
            this.requiredWindow = requiredWindow;
            this.shapeMask = shapeMask;
            this.shapeFull = shapeFull;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.modes = modes;
            this.workspaces = workspaces;
        }

        void releasePages() {
            currentPages.release(currentWindow);
            if (requiredPages != null) requiredPages.release(requiredWindow);
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

        PageWindow currentWindow() {
            return currentWindow;
        }

        @Nullable
        PageWindow requiredWindow() {
            return requiredWindow;
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

        CoverageMode[] modes() {
            return modes;
        }

        CompiledWorkspace[] workspaces() {
            return workspaces;
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

    static final class RequestWork {
        final SearchRequest request;
        final CompiledWorkspace workspace;
        final List<SearchTileResult> results;
        final AtomicLongCounter completedPositions = new AtomicLongCounter();
        long targetBit;
        long capturedCurrentPositions;
        long capturedRequiredPositions;
        long acceptedPositions;
        long plannedPositions;

        RequestWork(SearchRequest request) {
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
        }

        void addAcceptedPositions(long count) {
            acceptedPositions += count;
        }
    }

    static final class AtomicLongCounter {
        long value;

        long addAndGet(long delta) {
            value += delta;
            return value;
        }
    }

    static SnapshotCapture captureSmallSnapshot(WorkUnit unit) {
        SearchTileSnapshot cached = unit.shared().snapshot();
        if (cached != null) return new SnapshotCapture(cached, false);

        SearchRequest request = unit.shared().group().representative();
        TileSpec spec = unit.shared().spec();
        ViewCapture currentCapture = unit.shared().currentPages()
                .capture(unit.shared().currentWindow());
        ViewCapture requiredCapture = unit.shared().requiredPages() == null
                ? ViewCapture.empty(emptyView(
                        Blocks.AIR.defaultBlockState(), request.level()))
                : unit.shared().requiredPages()
                        .capture(unit.shared().requiredWindow());

        SearchTileSnapshot snapshot = new SearchTileSnapshot(
                spec,
                request.bounds(),
                currentCapture.newlyCapturedPositions(),
                requiredCapture.newlyCapturedPositions(),
                currentCapture.view(),
                requiredCapture.view(),
                unit.shared().requiredPages() != null,
                unit.shared().shapeMask(),
                unit.shared().shapeFull(),
                unit.shared().originX(),
                unit.shared().originY(),
                unit.shared().originZ(),
                unit.shared().modes(),
                unit.shared().workspaces(),
                false);
        unit.shared().setSnapshot(snapshot);
        return new SnapshotCapture(snapshot, true);
    }

    static SearchTileSnapshot emptySnapshot(
            WorkUnit unit) {
        SearchRequest request = unit.work().request();
        TileSpec spec = unit.shared().spec();
        SnapshotBlockView current = emptyView(
                Blocks.VOID_AIR.defaultBlockState(), request.level());
        SnapshotBlockView required = emptyView(
                Blocks.AIR.defaultBlockState(), request.level());
        return new SearchTileSnapshot(
                spec, request.bounds(), 0L, 0L,
                current, required, false,
                unit.shared().shapeMask(), unit.shared().shapeFull(),
                unit.shared().originX(), unit.shared().originY(),
                unit.shared().originZ(), unit.shared().modes(),
                unit.shared().workspaces(), true);
    }

    static SnapshotBlockView emptyView(
            BlockState fallback, ClientLevel level) {
        return new SnapshotBlockView(
                new PageSlot[0], 0, 0, 0, 0, 0, 0,
                fallback, level.getMinY(), level.getHeight());
    }
}
