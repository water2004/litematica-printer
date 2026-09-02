package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("UnstableApiUsage")
public final class ScanPerformanceGameTest implements FabricClientGameTest {
    private static final int MIN_X = -48;
    private static final int MAX_X = 48;
    private static final int MIN_Y = 130;
    private static final int MAX_Y = 230;
    private static final int MIN_Z = -48;
    private static final int MAX_Z = 48;
    private static final double WORK_RANGE = 40.0D;
    private static final long EXPECTED_BOUNDS = 531_441L;
    private static final long EXPECTED_ACCEPTED = 531_441L;
    private static final int EXPECTED_SEARCH_TILES = 4_817;
    private static final long MAX_SHARED_STATE_READS = 1_300_000L;
    private static final long EXPECTED_PRINT_JOBS = 65_229L;
    private static final long EXPECTED_PRINT_JOB_XOR = 8_994_597_566_184_131_034L;
    private static final long EXPECTED_PRINT_JOB_SUM = 7_194_905_270_522_797_690L;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GameTestMode.isScanPerformance()) return;

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareCurrentBehaviorStates(singleplayer);
            singleplayer.getServer().runCommand("gamemode spectator @p");
            singleplayer.getServer().runCommand("tp @p 0 180 0");
            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && client.level != null
                    && client.player.getY() > 179.0D
                    && behaviorWorldArrived(client.level));

            context.runOnClient(client -> {
                prepareSchematic();
                configureScanner();
            });

            int iterations = Math.max(1, Integer.getInteger(
                    "litematica-printer.gametest.scanIterations", 1));
            List<Long> samples = new ArrayList<>(iterations);
            for (int iteration = 0; iteration < iterations; iteration++) {
                if (iteration > 0) {
                    context.waitFor(client ->
                            !AsyncSearchCoordinator.INSTANCE.isBusy(), 1200);
                    context.runOnClient(client -> {
                        ModuleManager.GUI.resetScanState();
                        ModuleManager.PRINT.resetScanState();
                        AsyncSearchCoordinator.resetRoundProfileForTesting();
                        Configs.Core.WORK_SWITCH.setBooleanValue(true);
                    });
                }
                context.waitFor(client -> completedPrintProfile() != null, 1200);
                AsyncSearchCoordinator.RoundProfile profile =
                        context.computeOnClient(client -> {
                            Configs.Core.WORK_SWITCH.setBooleanValue(false);
                            return completedPrintProfile();
                        });
                assertProfile(profile);
                printProfile(profile, iteration + 1);
                samples.add(profile.scanNanos());
            }
            printBenchmarkSummary(samples);

            if (Boolean.getBoolean(
                    "litematica-printer.gametest.scanBenchmarkOnly")) {
                return;
            }

            context.waitFor(client -> !AsyncSearchCoordinator.INSTANCE.isBusy(), 1200);
            double publishedProgress = context.computeOnClient(client ->
                    ModuleManager.GUI.getTotalProgress().getProgress());
            AtomicBoolean observedProducerPartialProgress = new AtomicBoolean();
            context.runOnClient(client -> {
                Configs.Core.SEARCH_THREADS.setIntegerValue(1);
                ModuleManager.GUI.resetScanState();
                ModuleManager.PRINT.resetScanState();
                AsyncSearchCoordinator.resetRoundProfileForTesting();
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
            });
            context.waitFor(client -> {
                double visibleProgress =
                        ModuleManager.GUI.getTotalProgress().getProgress();
                if (Double.compare(visibleProgress, publishedProgress) != 0) {
                    throw new AssertionError(
                            "Published GUI progress followed the producer scan: "
                                    + publishedProgress + " -> " + visibleProgress);
                }
                long scanned = ModuleManager.GUI.getProducerScannedPositions();
                long total = ModuleManager.GUI.getProducerTotalPositions();
                if (scanned > 0L && scanned < total) {
                    observedProducerPartialProgress.set(true);
                }
                return AsyncSearchCoordinator.getLastRoundProfileForTesting() != null;
            }, 1200);
            context.runOnClient(client ->
                    Configs.Core.WORK_SWITCH.setBooleanValue(false));
            if (!observedProducerPartialProgress.get()) {
                throw new AssertionError(
                        "Did not observe an in-flight producer scan while checking GUI progress");
            }
        } finally {
            context.runOnClient(client -> cleanup());
        }
    }

    private static void prepareSchematic() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("Schematic world is unavailable");

        int minChunkX = Math.floorDiv(MIN_X, 16);
        int maxChunkX = Math.floorDiv(MAX_X, 16);
        int minChunkZ = Math.floorDiv(MIN_Z, 16);
        int maxChunkZ = Math.floorDiv(MAX_Z, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                schematic.getChunkSource().loadChunk(chunkX, chunkZ);
            }
        }

        forEachPatternPosition(pos ->
                schematic.setBlock(pos, expectedBlock(pos).defaultBlockState(), 3));
        forEachBehaviorPosition((behavior, pos) -> {
            clearFixtureCube((clearPos) ->
                    schematic.setBlock(clearPos, Blocks.AIR.defaultBlockState(), 3), pos);
            schematic.setBlock(pos, behavior.required(), 3);
            behavior.requiredNeighbors().forEach((source, state) ->
                    schematic.setBlock(translate(source, pos), state, 3));
        });
        TestSchematicRegion.activate(
                new BlockPos(MIN_X, MIN_Y, MIN_Z),
                new BlockPos(MAX_X, MAX_Y, MAX_Z));
    }

    private static void prepareCurrentBehaviorStates(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            forEachBehaviorPosition((behavior, pos) -> {
                clearFixtureCube((clearPos) -> level.setBlockAndUpdate(
                        clearPos, Blocks.AIR.defaultBlockState()), pos);
                level.setBlockAndUpdate(pos, behavior.current());
                behavior.currentNeighbors().forEach((source, state) ->
                        level.setBlockAndUpdate(translate(source, pos), state));
            });
        });
    }

    private static boolean behaviorWorldArrived(net.minecraft.client.multiplayer.ClientLevel level) {
        List<PrinterBehaviorBaselineGameTest.BehaviorCase> cases =
                PrinterBehaviorBaselineGameTest.cases();
        for (int index = 0; index < cases.size(); index++) {
            BlockState expected = cases.get(index).current();
            if (expected.isAir()) continue;
            return level.getBlockState(behaviorPosition(index)).equals(expected);
        }
        return false;
    }

    private static void configureScanner() {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.RENDER_HUD.setBooleanValue(true);
        Configs.Core.WORK_RANGE.setDoubleValue(WORK_RANGE);
        Configs.Core.SEARCH_THREADS.setIntegerValue(4);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.CUBE);

        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(0);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
        PrinterBehaviorBaselineGameTest.configureBehaviorFeatures();
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);

        ModuleManager.GUI.resetScanState();
        ModuleManager.PRINT.resetScanState();
        AsyncSearchCoordinator.resetRoundProfileForTesting();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void assertProfile(AsyncSearchCoordinator.RoundProfile profile) {
        if (profile == null) throw new AssertionError("Scan profile was not published");
        AsyncSearchCoordinator.RequestProfile gui = request(profile, "gui");
        AsyncSearchCoordinator.RequestProfile print = request(profile, "print");
        if (profile.requests().size() != 2) {
            throw new AssertionError("Expected GUI and Print requests only: "
                    + profile.requests());
        }
        if (gui.boundsVolume() != EXPECTED_BOUNDS
                || print.boundsVolume() != EXPECTED_BOUNDS) {
            throw new AssertionError("Scan bounds changed: " + profile.requests());
        }
        if (gui.acceptedPositions() != EXPECTED_ACCEPTED
                || print.acceptedPositions() != EXPECTED_ACCEPTED) {
            throw new AssertionError("Accepted scan coordinates changed: "
                    + profile.requests());
        }
        if (gui.tileCount() != EXPECTED_SEARCH_TILES
                || print.tileCount() != EXPECTED_SEARCH_TILES) {
            throw new AssertionError(
                    "The scanner no longer preserves the compiled mask partition: "
                            + profile.requests());
        }
        if (profile.capturedTileSnapshots() != EXPECTED_SEARCH_TILES) {
            throw new AssertionError(
                    "GUI and printer did not share exactly one snapshot per tile: "
                            + profile.capturedTileSnapshots());
        }
        long sharedStateReads = gui.stateReads() + print.stateReads();
        if (sharedStateReads > MAX_SHARED_STATE_READS) {
            throw new AssertionError(
                    "GUI and printer snapshots are reading duplicate world data: "
                            + sharedStateReads);
        }
        long guiTotal = ModuleManager.GUI.getPrintProgress().getTotal();
        long guiFinished = ModuleManager.GUI.getPrintProgress().getFinished();
        if (guiTotal < 50_000L || guiFinished < 1L || print.jobCount() < 50_000L) {
            throw new AssertionError("Mixed scan fixture was not fully observed: jobs="
                    + print.jobCount() + ", guiTotal=" + guiTotal
                    + ", guiFinished=" + guiFinished);
        }
        if (print.jobCount() != EXPECTED_PRINT_JOBS
                || print.jobXor() != EXPECTED_PRINT_JOB_XOR
                || print.jobSum() != EXPECTED_PRINT_JOB_SUM) {
            throw new AssertionError("Printer job set changed: " + print);
        }
        if (print.iceWaterJobs() != 1L) {
            throw new AssertionError(
                    "Expected exactly one ice-to-water phase-two job: " + print);
        }
    }

    private static AsyncSearchCoordinator.RequestProfile request(
            AsyncSearchCoordinator.RoundProfile profile, String id) {
        return profile.requests().stream()
                .filter(request -> request.moduleId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing scan request " + id));
    }

    private static AsyncSearchCoordinator.RoundProfile completedPrintProfile() {
        AsyncSearchCoordinator.RoundProfile profile =
                AsyncSearchCoordinator.getLastRoundProfileForTesting();
        if (profile == null) return null;
        return profile.requests().stream()
                .anyMatch(request -> request.moduleId().equals("print"))
                ? profile : null;
    }

    private static void printProfile(
            AsyncSearchCoordinator.RoundProfile profile, int iteration) {
        String label = System.getProperty(
                "litematica-printer.gametest.scanProfileLabel", "unspecified");
        System.out.println("[ScanProfile] label=" + label
                + " iteration=" + iteration
                + " sequence=" + profile.sequence()
                + " snapshots=" + profile.capturedTileSnapshots()
                + " planMs=" + profile.planNanos() / 1_000_000.0D
                + " scanMs=" + profile.scanNanos() / 1_000_000.0D);
        for (AsyncSearchCoordinator.RequestProfile request : profile.requests()) {
            System.out.println("[ScanProfile] module=" + request.moduleId()
                    + " bounds=" + request.boundsVolume()
                    + " tiles=" + request.tileCount()
                    + " captured=" + request.capturedPositions()
                    + " stateReads=" + request.stateReads()
                    + " accepted=" + request.acceptedPositions()
                    + " jobs=" + request.jobCount()
                    + " jobXor=" + Long.toUnsignedString(request.jobXor())
                    + " jobSum=" + Long.toUnsignedString(request.jobSum())
                    + " iceWaterJobs=" + request.iceWaterJobs());
        }
    }

    private static void printBenchmarkSummary(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double medianNanos;
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 0) {
            medianNanos = (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D;
        } else {
            medianNanos = sorted.get(middle);
        }
        String label = System.getProperty(
                "litematica-printer.gametest.scanProfileLabel", "unspecified");
        System.out.println("[ScanBenchmark] label=" + label
                + " iterations=" + samples.size()
                + " medianMs=" + medianNanos / 1_000_000.0D
                + " samplesMs=" + samples.stream()
                .map(nanos -> Double.toString(nanos / 1_000_000.0D))
                .toList());
    }

    private static void cleanup() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.RENDER_HUD.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        ModuleManager.GUI.resetScanState();
        ModuleManager.PRINT.resetScanState();
        TestSchematicRegion.clear();

        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic != null) {
            forEachPatternPosition(pos ->
                    schematic.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
            forEachBehaviorPosition((behavior, pos) ->
                    clearFixtureCube((clearPos) -> schematic.setBlock(
                            clearPos, Blocks.AIR.defaultBlockState(), 3), pos));
        }
    }

    private static void forEachBehaviorPosition(BehaviorPositionConsumer consumer) {
        List<PrinterBehaviorBaselineGameTest.BehaviorCase> cases =
                PrinterBehaviorBaselineGameTest.cases();
        for (int index = 0; index < cases.size(); index++) {
            consumer.accept(cases.get(index), behaviorPosition(index));
        }
    }

    private static BlockPos behaviorPosition(int index) {
        int layerIndex = index % 81;
        int x = -36 + (layerIndex % 9) * 9;
        int z = -36 + ((layerIndex / 9) % 9) * 9;
        int y = 180 + (index / 81) * 9;
        return new BlockPos(x, y, z);
    }

    private static BlockPos translate(BlockPos source, BlockPos target) {
        return target.offset(
                source.getX() - PrinterBehaviorBaselineGameTest.ORIGIN.getX(),
                source.getY() - PrinterBehaviorBaselineGameTest.ORIGIN.getY(),
                source.getZ() - PrinterBehaviorBaselineGameTest.ORIGIN.getZ());
    }

    private static void clearFixtureCube(PositionConsumer consumer, BlockPos center) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    consumer.accept(center.offset(dx, dy, dz));
                }
            }
        }
    }

    private static Block expectedBlock(BlockPos pos) {
        return switch (Math.floorMod(pos.getX() * 31
                + pos.getY() * 17 + pos.getZ() * 13, 3)) {
            case 0 -> Blocks.STONE;
            case 1 -> Blocks.DIRT;
            default -> Blocks.COBBLESTONE;
        };
    }

    private static boolean isPatternPosition(int x, int y, int z) {
        return Math.floorMod(x * 31 + y * 17 + z * 13, 8) == 0;
    }

    private static void forEachPatternPosition(PositionConsumer consumer) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    if (!isPatternPosition(x, y, z)) continue;
                    consumer.accept(mutable.set(x, y, z).immutable());
                }
            }
        }
    }

    @FunctionalInterface
    private interface PositionConsumer {
        void accept(BlockPos pos);
    }

    @FunctionalInterface
    private interface BehaviorPositionConsumer {
        void accept(PrinterBehaviorBaselineGameTest.BehaviorCase behavior, BlockPos pos);
    }
}
