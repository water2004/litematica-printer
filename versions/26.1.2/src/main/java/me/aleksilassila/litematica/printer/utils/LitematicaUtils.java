package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;


@Environment(EnvType.CLIENT)
public class LitematicaUtils {
    public static final Minecraft client = Minecraft.getInstance();

    public static boolean isPositionWithinRange(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }

    public static Vec3 usePrecisionPlacement(BlockPos pos, BlockState stateSchematic) {
        if (Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue()) {
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
            Vec3 hitPos = Vec3.atLowerCornerOf(pos);
            if (protocol == EasyPlaceProtocol.V3) {
                return WorldUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
            } else if (protocol == EasyPlaceProtocol.V2) {
                // Carpet Accurate Block placements protocol support, plus slab support
                return WorldUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
            }
        }
        return null;
    }

    public static boolean isSchematicBlock(BlockPos pos) {
        SchematicPlacementManager schematicPlacementManager = DataManager.getSchematicPlacementManager();
        List<SchematicPlacementManager.PlacementPart> allPlacementsTouchingChunk = schematicPlacementManager.getAllPlacementsTouchingChunk(pos);

        for (SchematicPlacementManager.PlacementPart placementPart : allPlacementsTouchingChunk) {
            if (placementPart.getBox().containsPos(pos)) {
                SubRegionPlacement subRegion = getSubRegionForPlacementPart(placementPart);
                if (subRegion != null) {
                    if (subRegion.isEnabled()) {
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Captures the enabled placement geometry intersecting {@code limit}.
     * Litematica already indexes placement parts by chunk, so this performs one
     * lookup per relevant chunk instead of one lookup per scanned block.
     */
    public static List<PrinterBox> getSchematicBoxesSnapshot(PrinterBox limit) {
        if (limit == null) return List.of();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        List<PrinterBox> result = new ArrayList<>();
        int minChunkX = Math.floorDiv(limit.minX, 16);
        int maxChunkX = Math.floorDiv(limit.maxX, 16);
        int minChunkZ = Math.floorDiv(limit.minZ, 16);
        int maxChunkZ = Math.floorDiv(limit.maxZ, 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (SchematicPlacementManager.PlacementPart part
                        : manager.getPlacementPartsInChunk(chunkX, chunkZ)) {
                    SchematicPlacement placement = part.getPlacement();
                    if (placement == null || !placement.isEnabled()) continue;
                    SubRegionPlacement subRegion =
                            getSubRegionForPlacementPart(part);
                    if (subRegion != null && !subRegion.isEnabled()) continue;

                    var box = part.getBox();
                    int minX = Math.max(limit.minX, box.minX());
                    int minY = Math.max(limit.minY, box.minY());
                    int minZ = Math.max(limit.minZ, box.minZ());
                    int maxX = Math.min(limit.maxX, box.maxX());
                    int maxY = Math.min(limit.maxY, box.maxY());
                    int maxZ = Math.min(limit.maxZ, box.maxZ());
                    if (minX <= maxX && minY <= maxY && minZ <= maxZ) {
                        result.add(new PrinterBox(
                                minX, minY, minZ, maxX, maxY, maxZ));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    @Nullable
    private static SubRegionPlacement getSubRegionForPlacementPart(SchematicPlacementManager.PlacementPart part) {
        SchematicPlacement placement = part.getPlacement();
        String subName = part.getSubRegionName();
        if (placement != null && subName != null) {
            return placement.getRelativeSubRegionPlacement(subName);
        }
        return null;
    }

    public static boolean inSelection(BlockPos pos) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return false;
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            // 普通选区
            List<Box> arr = selection.getAllSubRegionBoxes();
            for (Box box : arr) {
                if (isPosInBox(box, pos)) {
                    return true;
                }
            }
            return false;
        } else {
            // 简单选区
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            return isPosInBox(box, pos);
        }
    }

    private static boolean isPosInBox(Box box, BlockPos pos) {
        if (box == null || box.getPos1() == null || box.getPos2() == null || pos == null) return false;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return x >= Math.min(box.getPos1().getX(), box.getPos2().getX())
            && x <= Math.max(box.getPos1().getX(), box.getPos2().getX())
            && y >= Math.min(box.getPos1().getY(), box.getPos2().getY())
            && y <= Math.max(box.getPos1().getY(), box.getPos2().getY())
            && z >= Math.min(box.getPos1().getZ(), box.getPos2().getZ())
            && z <= Math.max(box.getPos1().getZ(), box.getPos2().getZ());
    }

    private static List<PrinterBox> getSelectionBoxes(AreaSelection selection) {
        if (selection == null) {
            return Collections.emptyList();
        }

        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            return selection.getAllSubRegionBoxes().stream().map(LitematicaUtils::toPrinterBox).toList();
        }

        Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
        PrinterBox printerBox = toPrinterBox(box);
        return printerBox != null ? Collections.singletonList(printerBox) : Collections.emptyList();
    }

    /**
     * 在客户端主线程捕获当前选区的不可变边界副本，供异步搜索调度器使用。
     * 搜索线程只读取这些坐标边界，不再访问可变的 SelectionManager。
     */
    public static List<PrinterBox> getSelectionBoxesSnapshot() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return List.of();
        return getSelectionBoxes(selection).stream()
                .map(box -> new PrinterBox(
                        box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ))
                .toList();
    }

    /**
     * 获取当前投影选区的联合边界，用于裁剪迭代盒子。
     * @return 选区边界，无选区时返回 null
     */
    @Nullable
    public static PrinterBox getSelectionBounds() {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return null;

        List<PrinterBox> boxes = getSelectionBoxes(selection);
        if (boxes.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (PrinterBox box : boxes) {
            if (box.minX < minX) minX = box.minX;
            if (box.minY < minY) minY = box.minY;
            if (box.minZ < minZ) minZ = box.minZ;
            if (box.maxX > maxX) maxX = box.maxX;
            if (box.maxY > maxY) maxY = box.maxY;
            if (box.maxZ > maxZ) maxZ = box.maxZ;
        }

        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static PrinterBox toPrinterBox(Box box) {
        if (box == null || box.getPos1() == null || box.getPos2() == null) {
            return null;
        }
        return new PrinterBox(box.getPos1(), box.getPos2());
    }

    public static BlockState getBlockState(BlockPos pos) {
        return SchematicWorldHandler.getSchematicWorld().getBlockState(pos);
    }
}
