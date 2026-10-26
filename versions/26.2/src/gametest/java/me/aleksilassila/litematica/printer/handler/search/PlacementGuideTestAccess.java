package me.aleksilassila.litematica.printer.handler.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import me.aleksilassila.litematica.printer.handler.search.SnapshotPages;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Test-only access to the package-private immutable snapshot implementation. */
public final class PlacementGuideTestAccess {
    private PlacementGuideTestAccess() {
    }

    public static SchematicBlockContext snapshotContext(
            Minecraft client,
            BlockPos center,
            BlockState current,
            BlockState required,
            Map<BlockPos, BlockState> currentNeighbors,
            Map<BlockPos, BlockState> requiredNeighbors) {
        var currentView = denseView(center, current, currentNeighbors);
        var requiredView = denseView(center, required, requiredNeighbors);
        return new SchematicBlockContext(client, currentView, requiredView, center);
    }

    private static SnapshotPages.SnapshotBlockView denseView(
            BlockPos center,
            BlockState centerState,
            Map<BlockPos, BlockState> neighbors) {
        Map<BlockPos, BlockState> source = new HashMap<>(neighbors);
        source.put(center, centerState);
        int minX = center.getX();
        int maxX = center.getX();
        int minY = center.getY();
        int maxY = center.getY();
        int minZ = center.getZ();
        int maxZ = center.getZ();
        for (BlockPos pos : neighbors.keySet()) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        int minPageX = Math.floorDiv(minX, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int minPageY = Math.floorDiv(minY, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int minPageZ = Math.floorDiv(minZ, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int maxPageX = Math.floorDiv(maxX, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int maxPageY = Math.floorDiv(maxY, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int maxPageZ = Math.floorDiv(maxZ, SnapshotPages.SNAPSHOT_PAGE_EDGE);
        int pagesX = maxPageX - minPageX + 1;
        int pagesY = maxPageY - minPageY + 1;
        int pagesZ = maxPageZ - minPageZ + 1;
        var pages = new SnapshotPages.SnapshotPage[
                pagesX * pagesY * pagesZ];
        int pageIndex = 0;
        for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
            for (int pageY = minPageY; pageY <= maxPageY; pageY++) {
                for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
                    int pageMinX = Math.max(
                            minX, pageX * SnapshotPages.SNAPSHOT_PAGE_EDGE);
                    int pageMinY = Math.max(
                            minY, pageY * SnapshotPages.SNAPSHOT_PAGE_EDGE);
                    int pageMinZ = Math.max(
                            minZ, pageZ * SnapshotPages.SNAPSHOT_PAGE_EDGE);
                    int pageMaxX = Math.min(maxX,
                            (pageX + 1) * SnapshotPages.SNAPSHOT_PAGE_EDGE - 1);
                    int pageMaxY = Math.min(maxY,
                            (pageY + 1) * SnapshotPages.SNAPSHOT_PAGE_EDGE - 1);
                    int pageMaxZ = Math.min(maxZ,
                            (pageZ + 1) * SnapshotPages.SNAPSHOT_PAGE_EDGE - 1);
                    int pageSizeX = pageMaxX - pageMinX + 1;
                    int pageSizeY = pageMaxY - pageMinY + 1;
                    int pageSizeZ = pageMaxZ - pageMinZ + 1;
                    BlockState[] states = new BlockState[
                            pageSizeX * pageSizeY * pageSizeZ];
                    Arrays.fill(states, Blocks.AIR.defaultBlockState());
                    int stateIndex = 0;
                    for (int x = pageMinX; x <= pageMaxX; x++) {
                        for (int y = pageMinY; y <= pageMaxY; y++) {
                            for (int z = pageMinZ; z <= pageMaxZ; z++) {
                                states[stateIndex++] = source.getOrDefault(
                                        new BlockPos(x, y, z),
                                        Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                    pages[pageIndex++] = new SnapshotPages.SnapshotPage(
                            states,
                            pageMinX, pageMinY, pageMinZ,
                            pageSizeX, pageSizeY, pageSizeZ);
                }
            }
        }
        return new SnapshotPages.SnapshotBlockView(
                pages, minPageX, minPageY, minPageZ, pagesX, pagesY, pagesZ,
                Blocks.AIR.defaultBlockState(), -64, 384);
    }
}
