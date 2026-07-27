package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.malilib.util.position.LayerRange;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 搜索范围管理器。
 * 只在客户端主线程捕获玩家位置、层范围与遍历设置，生成扫描边界。
 * 实际坐标遍历和形状过滤统一由 {@link AsyncSearchCoordinator} 完成。
 */
public class IteratorManager {
    private PrinterBox box;

    private BlockPos lastEyePos;
    private int lastExpandRange = -1;
    private int lastLayerMin = Integer.MIN_VALUE;
    private int lastLayerMax = Integer.MIN_VALUE;
    private int lastLayerSingle = Integer.MIN_VALUE;
    private int lastLayerAbove = Integer.MIN_VALUE;
    private int lastLayerBelow = Integer.MIN_VALUE;
    @Nullable
    private Direction.Axis lastLayerAxis = null;
    @Nullable
    private LayerMode lastLayerMode = null;
    @Nullable
    private SelectionType lastSelectionType = null;
    @Nullable
    private PrinterBox lastBox;

    private boolean needsRebuild;

    public IteratorManager() {
        this.needsRebuild = true;
    }

    /**
     * 根据玩家位置和配置重建 PrinterBox，返回是否需要重置扫描状态。
     */
    public boolean tryBuildBox(LocalPlayer player, @Nullable Object selectionTypeObj) {
        BlockPos eyeBP = new BlockPos(new Vec3i(
                (int) Math.round(player.getX()),
                (int) Math.round(player.getEyeY()),
                (int) Math.round(player.getZ())));

        double effectiveRange = ConfigUtils.getEffectiveRange();
        int currentRange = (int) Math.ceil(effectiveRange);

        LayerRange layerRange = DataManager.getRenderLayerRange();
        LayerMode layerMode = layerRange.getLayerMode();
        Direction.Axis layerAxis = layerRange.getAxis();
        int layerMin = layerRange.getLayerRangeMin();
        int layerMax = layerRange.getLayerRangeMax();
        int layerSingle = layerRange.getLayerSingle();
        int layerAbove = layerRange.getLayerAbove();
        int layerBelow = layerRange.getLayerBelow();

        SelectionType selectionType = selectionTypeObj instanceof SelectionType s ? s : null;

        boolean needRebuild = this.needsRebuild
                || this.box == null
                || !this.box.equals(lastBox)
                || lastEyePos == null
                || !lastEyePos.closerThan(eyeBP, effectiveRange * 0.4)
                || lastExpandRange != currentRange
                || layerMin != lastLayerMin
                || layerMax != lastLayerMax
                || layerSingle != lastLayerSingle
                || layerAbove != lastLayerAbove
                || layerBelow != lastLayerBelow
                || layerAxis != lastLayerAxis
                || layerMode != lastLayerMode
                || selectionType != lastSelectionType;

        if (needRebuild) {
            lastEyePos = eyeBP;
            lastExpandRange = currentRange;
            lastLayerMin = layerMin;
            lastLayerMax = layerMax;
            lastLayerSingle = layerSingle;
            lastLayerAbove = layerAbove;
            lastLayerBelow = layerBelow;
            lastLayerAxis = layerAxis;
            lastLayerMode = layerMode;
            lastSelectionType = selectionType;

            int minX = (int) Math.floor(player.getX() - effectiveRange);
            int maxX = (int) Math.ceil(player.getX() + effectiveRange);
            int minY = (int) Math.floor(player.getEyeY() - effectiveRange);
            int maxY = (int) Math.ceil(player.getEyeY() + effectiveRange);
            int minZ = (int) Math.floor(player.getZ() - effectiveRange);
            int maxZ = (int) Math.ceil(player.getZ() + effectiveRange);

            // 层范围裁剪应对所有选区模式生效，而非仅限"可见层"模式
            if (layerMode != LayerMode.ALL) {
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

            if (selectionType != null) {
                if (selectionType == SelectionType.LITEMATICA_SELECTION_BELOW_PLAYER) {
                    maxY = Math.min(maxY, (int) Math.floor(player.getY()));
                } else if (selectionType == SelectionType.LITEMATICA_SELECTION_ABOVE_PLAYER) {
                    minY = Math.max(minY, (int) Math.ceil(player.getY()));
                }
            }

            box = new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
            lastBox = box;

            box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
            box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
            box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
            box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();

            this.needsRebuild = false;
            return true;
        }

        this.needsRebuild = false;
        return false;
    }

    public void markNeedsRebuild() {
        this.needsRebuild = true;
    }

    public boolean isNeedsRebuild() {
        return needsRebuild;
    }

    @Nullable
    public PrinterBox getBox() {
        return box;
    }

    public boolean hasBox() {
        return box != null;
    }

}
