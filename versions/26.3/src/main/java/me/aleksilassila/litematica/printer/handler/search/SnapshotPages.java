package me.aleksilassila.litematica.printer.handler.search;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.SearchBounds;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.TileSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.SignalGetter;

/** Round-local paged capture and immutable block views. */
public final class SnapshotPages {
    private SnapshotPages() {}

    static final int SNAPSHOT_PAGE_EDGE = 8;
    static final int SNAPSHOT_PAGE_SHIFT = 3;
    public static final class SnapshotBlockView implements SignalGetter {
        final PageSlot[] slots;
        final int minPageX;
        final int minPageY;
        final int minPageZ;
        final int pageCountX;
        final int pageCountY;
        final int pageCountZ;
        final BlockState fallback;
        final int worldMinY;
        final int height;

        SnapshotBlockView(
                PageSlot[] slots,
                int minPageX,
                int minPageY,
                int minPageZ,
                int pageCountX,
                int pageCountY,
                int pageCountZ,
                BlockState fallback,
                int worldMinY,
                int height) {
            this.slots = slots;
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
            this(wrapPages(
                            pages, minPageX, minPageY, minPageZ,
                            pageCountX, pageCountY, pageCountZ),
                    minPageX, minPageY, minPageZ,
                    pageCountX, pageCountY, pageCountZ,
                    fallback, worldMinY, height);
        }

        static PageSlot[] wrapPages(
                SnapshotPage[] pages,
                int minPageX,
                int minPageY,
                int minPageZ,
                int pageCountX,
                int pageCountY,
                int pageCountZ) {
            PageSlot[] slots = new PageSlot[pages.length];
            int index = 0;
            for (int pageX = minPageX;
                    pageX < minPageX + pageCountX; pageX++) {
                for (int pageY = minPageY;
                        pageY < minPageY + pageCountY; pageY++) {
                    for (int pageZ = minPageZ;
                            pageZ < minPageZ + pageCountZ; pageZ++) {
                        SnapshotPage page = pages[index];
                        if (page != null) {
                            PageSlot slot = new PageSlot(
                                    BlockPos.asLong(pageX, pageY, pageZ),
                                    pageX, pageY, pageZ);
                            slot.page = page;
                            slots[index] = slot;
                        }
                        index++;
                    }
                }
            }
            return slots;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return getBlockState(pos.getX(), pos.getY(), pos.getZ());
        }

        BlockState getBlockState(int x, int y, int z) {
            return getBlockState(pageIndex(x, y, z), x, y, z);
        }

        public boolean containsState(BlockPos pos) {
            int index = pageIndex(pos.getX(), pos.getY(), pos.getZ());
            if (index < 0 || index >= slots.length) return false;
            PageSlot slot = slots[index];
            SnapshotPage page = slot == null ? null : slot.page;
            return page != null && page.get(pos.getX(), pos.getY(), pos.getZ(), null) != null;
        }

        int pageIndex(int x, int y, int z) {
            int pageX = (x >> SNAPSHOT_PAGE_SHIFT) - minPageX;
            int pageY = (y >> SNAPSHOT_PAGE_SHIFT) - minPageY;
            int pageZ = (z >> SNAPSHOT_PAGE_SHIFT) - minPageZ;
            if (pageX < 0 || pageX >= pageCountX
                    || pageY < 0 || pageY >= pageCountY
                    || pageZ < 0 || pageZ >= pageCountZ) {
                return -1;
            }
            return (pageX * pageCountY + pageY) * pageCountZ + pageZ;
        }

        BlockState getBlockState(
                int pageIndex, int x, int y, int z) {
            if (pageIndex < 0 || pageIndex >= slots.length) return fallback;
            PageSlot slot = slots[pageIndex];
            SnapshotPage page = slot == null ? null : slot.page;
            return page == null ? fallback : page.get(x, y, z, fallback);
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

    static final class SnapshotPage {
        final BlockState[] states;
        final int minX;
        final int minY;
        final int minZ;
        final int sizeX;
        final int sizeY;
        final int sizeZ;

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

        BlockState get(int worldX, int worldY, int worldZ, BlockState fallback) {
            int x = worldX - minX;
            int y = worldY - minY;
            int z = worldZ - minZ;
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY
                    || z < 0 || z >= sizeZ) {
                return fallback;
            }
            return states[(x * sizeY + y) * sizeZ + z];
        }
    }

    record PageRange(
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

    record PageWindow(PageRange range, PageSlot[] slots) {
    }

    static final class PageSlot {
        final long key;
        final int pageX;
        final int pageY;
        final int pageZ;
        SnapshotPage page;
        int remainingUses;

        PageSlot(long key, int pageX, int pageY, int pageZ) {
            this.key = key;
            this.pageX = pageX;
            this.pageY = pageY;
            this.pageZ = pageZ;
        }
    }

    static final class SnapshotPageCache {
        final Level source;
        final BlockState fallback;
        final int worldMinY;
        final int height;
        final Long2ObjectOpenHashMap<PageSlot> slots =
                new Long2ObjectOpenHashMap<>();
        int coverageMinX = Integer.MAX_VALUE;
        int coverageMinY = Integer.MAX_VALUE;
        int coverageMinZ = Integer.MAX_VALUE;
        int coverageMaxX = Integer.MIN_VALUE;
        int coverageMaxY = Integer.MIN_VALUE;
        int coverageMaxZ = Integer.MIN_VALUE;

        SnapshotPageCache(
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

        PageWindow register(PageRange range, int uses) {
            if (uses <= 0) {
                throw new IllegalArgumentException("page window needs a consumer");
            }
            PageSlot[] windowSlots = new PageSlot[
                    Math.multiplyExact(Math.multiplyExact(
                            range.countX(), range.countY()), range.countZ())];
            int index = 0;
            for (int pageX = range.minPageX();
                    pageX <= range.maxPageX(); pageX++) {
                for (int pageY = range.minPageY();
                        pageY <= range.maxPageY(); pageY++) {
                    for (int pageZ = range.minPageZ();
                            pageZ <= range.maxPageZ(); pageZ++) {
                        long key = pageKey(pageX, pageY, pageZ);
                        PageSlot slot = slots.get(key);
                        if (slot == null) {
                            slot = new PageSlot(key, pageX, pageY, pageZ);
                            slots.put(key, slot);
                        }
                        slot.remainingUses += uses;
                        windowSlots[index++] = slot;
                    }
                }
            }
            return new PageWindow(range, windowSlots);
        }

        ViewCapture capture(PageWindow window) {
            long newlyCaptured = 0L;
            for (PageSlot slot : window.slots()) {
                SnapshotPage page = slot.page;
                if (page == null) {
                    page = capturePage(slot.pageX, slot.pageY, slot.pageZ);
                    slot.page = page;
                    newlyCaptured += page.volume();
                }
            }
            PageRange range = window.range();
            return new ViewCapture(
                    new SnapshotBlockView(
                            window.slots(),
                            range.minPageX(), range.minPageY(), range.minPageZ(),
                            range.countX(), range.countY(), range.countZ(),
                            fallback, worldMinY, height),
                    newlyCaptured);
        }

        void release(PageWindow window) {
            for (PageSlot slot : window.slots()) {
                if (--slot.remainingUses == 0) slots.remove(slot.key);
            }
        }

        SnapshotPage capturePage(int pageX, int pageY, int pageZ) {
            int pageMinX = pageX * SNAPSHOT_PAGE_EDGE;
            int pageMinY = pageY * SNAPSHOT_PAGE_EDGE;
            int pageMinZ = pageZ * SNAPSHOT_PAGE_EDGE;
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

        static long pageKey(int pageX, int pageY, int pageZ) {
            return BlockPos.asLong(pageX, pageY, pageZ);
        }
    }

    record ViewCapture(
            SnapshotBlockView view,
            long newlyCapturedPositions) {
        static ViewCapture empty(SnapshotBlockView view) {
            return new ViewCapture(view, 0L);
        }
    }
}
