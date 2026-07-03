package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBase;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class GUI extends Module {
    public final static String NAME = "gui";

    @Getter
    private final Progress totalProgress = new Progress(Configs.Print.ENABLED);
    @Getter
    private final Progress printProgress = new Progress(Configs.Print.ENABLED);
    @Getter
    private final Progress fluidProgress = new Progress(Configs.Fluid.ENABLED);
    @Getter
    private final Progress fillProgress = new Progress(Configs.Fill.ENABLED);
    @Getter
    private final Progress mineProgress = new Progress(Configs.Mine.ENABLED);

    public GUI() {
        super(NAME, Configs.Core.RENDER_HUD, null, true);
    }

    @Override
    protected boolean needsRangeCheck() {
        return false;
    }

    @Override
    protected boolean shouldProcessQueue() {
        return false;
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return super.canProcessPos(pos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Print.ENABLED.getBooleanValue()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            if (schematic != null) {
                SchematicBlockContext context = new SchematicBlockContext(client, level, schematic, blockPos);
                if (!context.requiredState.isAir()) {
                    if (BlockMatchingType.get(context) == BlockMatchingType.CORRECT) {
                        printProgress.finished++;
                        totalProgress.finished++;
                    }
                    printProgress.total++;
                    totalProgress.total++;
                }
            }
        }
        if (Configs.Fluid.ENABLED.getBooleanValue()) {
            if (!(level.getBlockState(blockPos).getBlock() instanceof LiquidBlock)) {
                fluidProgress.finished++;
                totalProgress.finished++;
            }
            fluidProgress.total++;
            totalProgress.total++;
        }
        if (Configs.Fill.ENABLED.getBooleanValue()) {
            if (Arrays.asList(ModuleManager.FILL.getFillModeItemList()).contains(level.getBlockState(blockPos).getBlock().asItem())) {
                fillProgress.finished++;
                totalProgress.finished++;
            }
            fillProgress.total++;
            totalProgress.total++;
        }
        if (Configs.Mine.ENABLED.getBooleanValue()) {
            if (level.getBlockState(blockPos).isAir()) {
                mineProgress.finished++;
                totalProgress.finished++;
            }
            mineProgress.total++;
            totalProgress.total++;
        }
        printProgress.calculateProgress();
        fluidProgress.calculateProgress();
        fillProgress.calculateProgress();
        mineProgress.calculateProgress();
        totalProgress.calculateProgress();
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (!interrupt) {
            totalProgress.reset();
            printProgress.reset();
            fluidProgress.reset();
            fillProgress.reset();
            mineProgress.reset();
        }
    }

    /**
     * 进度管理内部类（独立计数+自动修正进度范围）
     */
    @Getter
    public static class Progress {
        private final ConfigBase<?> config;
        private long total;
        private long finished;
        private double progress;
        private double lastProgress;

        public Progress(ConfigBase<?> config) {
            this.config = config;
            this.total = 0;
            this.finished = 0;
            this.progress = 0.0;
        }

        public double getProgress() {
            return progress <= 0 ? lastProgress : progress;
        }

        public void calculateProgress() {
            progress = total < 1 ? lastProgress : (float) finished / total;
            lastProgress = progress;
        }

        public void reset() {
            this.total = 0;
            this.finished = 0;
            this.progress = 0.0;
        }
    }
}