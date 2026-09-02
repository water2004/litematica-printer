package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.options.ConfigBase;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GUI 统计处理器 — 与作业生产者共用同一异步小快照搜索管线。
 */
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
    public GUI() {
        super(NAME, Configs.Core.RENDER_HUD, null, true);
    }

    @Override
    protected boolean needsAreaCheck() {
        return false;
    }

    @Override
    protected boolean canExecute() {
        return false;
    }

    @Override
    protected boolean usesAsyncSearch() {
        return true;
    }

    @Override
    protected boolean canSearch() {
        return true;
    }

    @Override
    protected boolean includeSchematicSnapshot() {
        return true;
    }

    @Override
    protected AsyncSearchCoordinator.WorkspaceFilter workspaceFilter() {
        return AsyncSearchCoordinator.WorkspaceFilter.NONE;
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return true;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return true;
    }

    @Override
    protected Object captureSearchContext() {
        return new GuiSearchContext(
                Configs.Print.ENABLED.getBooleanValue(),
                Configs.Fluid.ENABLED.getBooleanValue(),
                Configs.Fill.ENABLED.getBooleanValue(),
                Set.copyOf(List.of(ModuleManager.FILL.getFillModeItemList())));
    }

    @Override
    protected AsyncSearchCoordinator.SearchTileResult searchTile(
            Object searchContext,
            AsyncSearchCoordinator.SearchTileSnapshot snapshot) {
        GuiSearchContext context = (GuiSearchContext) searchContext;
        MutableStats stats = new MutableStats();

        for (AsyncSearchCoordinator.SearchBlockSnapshot block : snapshot.blocks()) {
            if (context.printEnabled()
                    && block.requiredState() != null
                    && !block.requiredState().isAir()) {
                stats.printTotal++;
                stats.totalTotal++;
                if (BlockMatchingType.get(
                        block.requiredState(), block.currentState())
                        == BlockMatchingType.CORRECT) {
                    stats.printFinished++;
                    stats.totalFinished++;
                }
            }
            if (context.fluidEnabled()) {
                stats.fluidTotal++;
                stats.totalTotal++;
                if (!(block.currentState().getBlock() instanceof LiquidBlock)) {
                    stats.fluidFinished++;
                    stats.totalFinished++;
                }
            }
            if (context.fillEnabled()) {
                stats.fillTotal++;
                stats.totalTotal++;
                if (context.fillItems().contains(
                        block.currentState().getBlock().asItem())) {
                    stats.fillFinished++;
                    stats.totalFinished++;
                }
            }
        }

        return new AsyncSearchCoordinator.SearchTileResult(
                snapshot.ordinal(),
                snapshot.scannedPositions(),
                List.of(),
                stats.freeze());
    }

    @Override
    protected void publishSearchRound(
            AsyncSearchCoordinator.SearchRequest request,
            List<AsyncSearchCoordinator.SearchTileResult> results) {
        if (!isSearchRequestCurrent(request)) return;

        MutableStats totals = new MutableStats();
        for (AsyncSearchCoordinator.SearchTileResult result : results) {
            if (result.payload() instanceof GuiStats stats) totals.add(stats);
        }
        totalProgress.publish(totals.totalTotal, totals.totalFinished);
        printProgress.publish(totals.printTotal, totals.printFinished);
        fluidProgress.publish(totals.fluidTotal, totals.fluidFinished);
        fillProgress.publish(totals.fillTotal, totals.fillFinished);
    }

    public static class Progress {
        private final ConfigBase<?> config;
        private final AtomicReference<ProgressSnapshot> published =
                new AtomicReference<>(new ProgressSnapshot(0L, 0L, 0.0D));

        public Progress(ConfigBase<?> config) {
            this.config = config;
        }

        public ConfigBase<?> getConfig() {
            return config;
        }

        public long getTotal() {
            return published.get().total();
        }

        public long getFinished() {
            return published.get().finished();
        }

        public double getProgress() {
            return published.get().progress();
        }

        public void publish(long total, long finished) {
            ProgressSnapshot previous = published.get();
            double progress = total < 1
                    ? previous.progress()
                    : (double) finished / total;
            published.set(new ProgressSnapshot(total, finished, progress));
        }

        private record ProgressSnapshot(long total, long finished, double progress) {
        }
    }

    private record GuiSearchContext(
            boolean printEnabled,
            boolean fluidEnabled,
            boolean fillEnabled,
            Set<Item> fillItems) {
    }

    private record GuiStats(
            long totalTotal,
            long totalFinished,
            long printTotal,
            long printFinished,
            long fluidTotal,
            long fluidFinished,
            long fillTotal,
            long fillFinished) {
    }

    private static final class MutableStats {
        long totalTotal;
        long totalFinished;
        long printTotal;
        long printFinished;
        long fluidTotal;
        long fluidFinished;
        long fillTotal;
        long fillFinished;

        void add(GuiStats stats) {
            totalTotal += stats.totalTotal();
            totalFinished += stats.totalFinished();
            printTotal += stats.printTotal();
            printFinished += stats.printFinished();
            fluidTotal += stats.fluidTotal();
            fluidFinished += stats.fluidFinished();
            fillTotal += stats.fillTotal();
            fillFinished += stats.fillFinished();
        }

        GuiStats freeze() {
            return new GuiStats(
                    totalTotal, totalFinished,
                    printTotal, printFinished,
                    fluidTotal, fluidFinished,
                    fillTotal, fillFinished);
        }
    }
}
