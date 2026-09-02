package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
public final class FullPrintPerformanceGameTest implements FabricClientGameTest {
    private static final int MIN_X = -6;
    private static final int MAX_X = 8;
    private static final int MIN_Y = 64;
    private static final int MAX_Y = 71;
    private static final int MIN_Z = -6;
    private static final int MAX_Z = 8;
    private static final int SHAPE_CENTER_X = 1;
    private static final int SHAPE_CENTER_Y = 66;
    private static final int SHAPE_CENTER_Z = 1;
    private static final int SHAPE_RADIUS_SQUARED = 49;
    private static final int BLOCKS_PER_TICK = 8;
    private static final int ITERATION_TIME_LIMIT_MS = 8;
    private static final List<BlockPos> TARGETS = createTargets();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GameTestMode.isFullPrintPerformance()) return;

        Recording recording = null;
        boolean metricsActive = false;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 0 64 0");
            // The printer intentionally supports configured work ranges beyond
            // vanilla's default reach. Match the permissive-server profile this
            // benchmark represents without bypassing the real placement path.
            singleplayer.getServer().runCommand(
                    "attribute @p minecraft:block_interaction_range base set 16");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && client.level != null
                    && !client.player.getAbilities().instabuild
                    && client.player.getY() >= 63.9D
                    && client.level.getBlockState(new BlockPos(0, 63, 0))
                    .is(Blocks.BEDROCK)
                    && countProfileMaterials(client.player.getInventory())
                    == TARGETS.size());

            context.runOnClient(client -> {
                prepareSchematic();
                configurePrinter();
            });
            context.waitFor(client -> !AsyncSearchCoordinator.INSTANCE.isBusy(), 1200);

            recording = createRecording();
            if (recording != null) recording.start();
            AsyncSearchCoordinator.resetRoundProfileForTesting();
            FullPrintProfileMetrics.start();
            metricsActive = true;
            long startedNanos = System.nanoTime();
            long startedTick = context.computeOnClient(client -> client.level.getGameTime());

            context.runOnClient(client ->
                    Configs.Core.WORK_SWITCH.setBooleanValue(true));
            try {
                context.waitFor(client ->
                        FullPrintProfileMetrics.serverPlacementSuccesses()
                                >= TARGETS.size(), 1200);
            } catch (RuntimeException | Error failure) {
                FullPrintProfileMetrics.Snapshot partialMetrics =
                        FullPrintProfileMetrics.snapshot();
                WorldResult partialWorld = singleplayer.getServer().computeOnServer(
                        server -> inspectWorld(
                                server.overworld(),
                                server.getPlayerList().getPlayers().getFirst()
                                        .getInventory()));
                List<BlockPos> serverMismatches =
                        singleplayer.getServer().computeOnServer(server ->
                                mismatchPositions(server.overworld()));
                WorldResult partialClient = context.computeOnClient(client ->
                        inspectWorld(client.level, client.player.getInventory()));
                List<BlockPos> clientMismatches = context.computeOnClient(
                        client -> mismatchPositions(client.level));
                throw new AssertionError(
                        "Complete print stalled: metrics=" + partialMetrics
                                + ", server=" + partialWorld
                                + ", client=" + partialClient
                                + ", serverMismatches=" + serverMismatches
                                + ", clientMismatches=" + clientMismatches,
                        failure);
            }

            long allPlacedNanos = System.nanoTime();
            context.runOnClient(client ->
                    Configs.Core.WORK_SWITCH.setBooleanValue(false));
            context.waitFor(client -> !AsyncSearchCoordinator.INSTANCE.isBusy(), 1200);
            context.waitTicks(5);
            long completedTick = context.computeOnClient(
                    client -> client.level.getGameTime());
            long completedNanos = System.nanoTime();

            FullPrintProfileMetrics.Snapshot metrics =
                    FullPrintProfileMetrics.stop();
            metricsActive = false;
            List<AsyncSearchCoordinator.RoundProfile> rounds =
                    AsyncSearchCoordinator.drainRoundProfilesForTesting();
            stopAndDump(recording);
            recording = null;

            WorldResult serverResult = singleplayer.getServer().computeOnServer(
                    server -> inspectWorld(
                            server.overworld(),
                            server.getPlayerList().getPlayers().getFirst()
                                    .getInventory()));
            WorldResult clientResult = context.computeOnClient(client ->
                    inspectWorld(client.level, client.player.getInventory()));
            assertCompleted(serverResult, clientResult, metrics);

            ScanTotals scanTotals = ScanTotals.from(rounds);
            printResult(startedNanos, allPlacedNanos, completedNanos,
                    startedTick, completedTick, metrics, scanTotals,
                    serverResult);
        } finally {
            if (metricsActive) FullPrintProfileMetrics.stop();
            stopAndDump(recording);
            context.runOnClient(client -> cleanup());
        }
    }

    private static Recording createRecording() {
        String destination = System.getProperty(
                "litematica-printer.gametest.fullPrintJfr", "");
        if (destination.isBlank()) return null;
        try {
            Recording recording = new Recording(
                    Configuration.getConfiguration("profile"));
            recording.setName("Litematica Printer complete print");
            recording.setToDisk(true);
            recording.enable(FullPrintProfileMetrics.PLACEMENT_EVENT_NAME)
                    .withThreshold(Duration.ZERO);
            recording.setDestination(Path.of(destination));
            return recording;
        } catch (Exception error) {
            throw new AssertionError("Could not configure full-print JFR", error);
        }
    }

    private static void stopAndDump(Recording recording) {
        if (recording == null) return;
        try {
            if (recording.getState() == RecordingState.RUNNING) {
                recording.stop();
            }
            recording.close();
        } catch (Exception error) {
            throw new AssertionError("Could not finish full-print JFR", error);
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        Map<Item, Integer> materials = requiredMaterials();
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();

            for (int x = MIN_X - 1; x <= MAX_X + 1; x++) {
                for (int z = MIN_Z - 1; z <= MAX_Z + 1; z++) {
                    level.setBlockAndUpdate(
                            new BlockPos(x, MIN_Y - 1, z),
                            Blocks.BEDROCK.defaultBlockState());
                }
            }
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    for (int z = MIN_Z; z <= MAX_Z; z++) {
                        level.setBlockAndUpdate(
                                new BlockPos(x, y, z),
                                Blocks.AIR.defaultBlockState());
                    }
                }
            }

            int slot = 0;
            for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
                int remaining = entry.getValue();
                while (remaining > 0) {
                    int count = Math.min(remaining, 64);
                    player.getInventory().setItem(
                            slot++, new ItemStack(entry.getKey(), count));
                    remaining -= count;
                }
            }
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void prepareSchematic() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            throw new AssertionError("Schematic world is unavailable");
        }
        for (int chunkX = Math.floorDiv(MIN_X, 16);
                chunkX <= Math.floorDiv(MAX_X, 16); chunkX++) {
            for (int chunkZ = Math.floorDiv(MIN_Z, 16);
                    chunkZ <= Math.floorDiv(MAX_Z, 16); chunkZ++) {
                schematic.getChunkSource().loadChunk(chunkX, chunkZ);
            }
        }
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    schematic.setBlock(new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (BlockPos target : TARGETS) {
            schematic.setBlock(target, expectedState(target), 3);
        }
        TestSchematicRegion.activate(
                new BlockPos(MIN_X, MIN_Y, MIN_Z),
                new BlockPos(MAX_X, MAX_Y, MAX_Z));
    }

    private static void configurePrinter() {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.RENDER_HUD.setBooleanValue(true);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(8.0D);
        Configs.Core.SEARCH_THREADS.setIntegerValue(4);
        Configs.Core.ITERATION_TIME_LIMIT.setIntegerValue(
                ITERATION_TIME_LIMIT_MS);
        Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);

        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(
                GameTestMode.isFullPrintPacketMode());
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(BLOCKS_PER_TICK);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Placement.FALLING_CHECK.setBooleanValue(true);

        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);

        ModuleManager.GUI.resetScanState();
        ModuleManager.PRINT.resetScanState();
    }

    private static void cleanup() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Core.RENDER_HUD.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        ModuleManager.GUI.resetScanState();
        ModuleManager.PRINT.resetScanState();
        AsyncSearchCoordinator.resetRoundProfileForTesting();
        TestSchematicRegion.clear();

        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    schematic.setBlock(new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static WorldResult inspectWorld(
            net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Inventory inventory) {
        int correct = 0;
        int wrong = 0;
        int extra = 0;
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState current = level.getBlockState(pos);
                    BlockState expected = isTarget(pos)
                            ? expectedState(pos)
                            : Blocks.AIR.defaultBlockState();
                    if (current.equals(expected)) {
                        if (!expected.isAir()) correct++;
                    } else if (expected.isAir()) {
                        extra++;
                    } else {
                        wrong++;
                    }
                }
            }
        }
        return new WorldResult(
                correct, wrong, extra, countProfileMaterials(inventory));
    }

    private static List<BlockPos> mismatchPositions(
            net.minecraft.world.level.Level level) {
        List<BlockPos> mismatches = new ArrayList<>();
        for (BlockPos target : TARGETS) {
            if (!level.getBlockState(target).equals(expectedState(target))) {
                mismatches.add(target);
                if (mismatches.size() == 32) break;
            }
        }
        return List.copyOf(mismatches);
    }

    private static void assertCompleted(
            WorldResult server,
            WorldResult client,
            FullPrintProfileMetrics.Snapshot metrics) {
        if (server.correctBlocks() != TARGETS.size()
                || server.wrongBlocks() != 0
                || server.extraBlocks() != 0
                || server.remainingMaterials() != 0) {
            throw new AssertionError("Server did not receive a complete print: " + server);
        }
        if (!client.equals(server)) {
            throw new AssertionError("Client did not converge to server print state: client="
                    + client + ", server=" + server);
        }
        if (metrics.serverPlacementSuccesses() != TARGETS.size()) {
            throw new AssertionError("Successful server placements did not match targets: "
                    + metrics);
        }
        if (metrics.useItemPackets() < TARGETS.size()) {
            throw new AssertionError("Fewer placement packets than completed blocks: "
                    + metrics);
        }
    }

    private static void printResult(
            long startedNanos,
            long allPlacedNanos,
            long completedNanos,
            long startedTick,
            long completedTick,
            FullPrintProfileMetrics.Snapshot metrics,
            ScanTotals scans,
            WorldResult world) {
        double placementMethodMs = (metrics.clientPlacementNanos()
                + metrics.serverPlacementNanos()) / 1_000_000.0D;
        long successfulPlacementNanos = metrics.clientSuccessfulPlacementNanos()
                + metrics.serverSuccessfulPlacementNanos();
        long trackedActiveNanos = scans.activeWorkNanos()
                + metrics.consumerPhaseNanos()
                + metrics.serverPlacementNanos();
        long consumerOtherNanos = Math.max(0L,
                metrics.consumerPhaseNanos()
                        - metrics.consumerValidationNanos()
                        - metrics.consumerExecutionNanos());
        double wallMs = (completedNanos - startedNanos) / 1_000_000.0D;
        double targetVsProducedJobRatio = scans.printJobs() == 0L ? 0.0D
                : TARGETS.size() * 100.0D / scans.printJobs();
        double successfulAttemptRatio = metrics.useItemPackets() == 0L ? 0.0D
                : TARGETS.size() * 100.0D / metrics.useItemPackets();
        double targetVsValidationRatio = metrics.consumerValidationCalls() == 0L
                ? 0.0D
                : TARGETS.size() * 100.0D
                / metrics.consumerValidationCalls();
        double packetVsExecutionRatio = metrics.consumerExecutionCalls() == 0L
                ? 0.0D
                : metrics.useItemPackets() * 100.0D
                / metrics.consumerExecutionCalls();
        System.out.println("[FullPrintProfile] targets=" + TARGETS.size()
                + " materials=3 blocksPerTick=" + BLOCKS_PER_TICK
                + " iterationTimeLimitMs=" + ITERATION_TIME_LIMIT_MS
                + " packetMode=" + GameTestMode.isFullPrintPacketMode()
                + " ticks=" + (completedTick - startedTick)
                + " allPlacedMs=" + (allPlacedNanos - startedNanos) / 1_000_000.0D
                + " wallMs=" + wallMs);
        System.out.println("[FullPrintProfile] rounds=" + scans.rounds()
                + " scanMs=" + scans.scanNanos() / 1_000_000.0D
                + " planMs=" + scans.planNanos() / 1_000_000.0D
                + " stateReads=" + scans.stateReads()
                + " printJobs=" + scans.printJobs()
                + " targetVsProducedJobRatio="
                + targetVsProducedJobRatio + "%");
        System.out.println("[FullPrintProfile] producerActiveMs="
                + scans.activeWorkNanos() / 1_000_000.0D
                + " planMs=" + scans.planNanos() / 1_000_000.0D
                + " captureMs=" + scans.captureNanos() / 1_000_000.0D
                + " searchWorkerMs=" + scans.searchNanos() / 1_000_000.0D
                + " completionMs=" + scans.completionNanos() / 1_000_000.0D
                + " publishMs=" + scans.publishNanos() / 1_000_000.0D);
        System.out.println("[FullPrintProfile] consumerActiveMs="
                + metrics.consumerPhaseNanos() / 1_000_000.0D
                + " phases=" + metrics.consumerPhaseCalls()
                + " validationMs="
                + metrics.consumerValidationNanos() / 1_000_000.0D
                + " validations=" + metrics.consumerValidationCalls()
                + " matched=" + metrics.consumerValidationMatches()
                + " executionMs="
                + metrics.consumerExecutionNanos() / 1_000_000.0D
                + " executions=" + metrics.consumerExecutionCalls()
                + " targetVsValidationRatio="
                + targetVsValidationRatio + "%"
                + " packetVsExecutionRatio="
                + packetVsExecutionRatio + "%"
                + " schedulerOtherMs=" + consumerOtherNanos / 1_000_000.0D);
        System.out.println("[FullPrintProfile] useItemPackets="
                + metrics.useItemPackets()
                + " carriedItemPackets=" + metrics.carriedItemPackets()
                + " containerClickPackets=" + metrics.containerClickPackets()
                + " clientPlace=" + metrics.clientPlacementSuccesses()
                + "/" + metrics.clientPlacementCalls()
                + " serverPlace=" + metrics.serverPlacementSuccesses()
                + "/" + metrics.serverPlacementCalls()
                + " successfulAttemptRatio=" + successfulAttemptRatio + "%");
        System.out.println("[FullPrintProfile] placementMethodMs="
                + placementMethodMs
                + " placementMethodWallRatio="
                + (wallMs == 0.0D ? 0.0D : placementMethodMs * 100.0D / wallMs)
                + "% successfulPlacementTrackedActiveRatio="
                + (trackedActiveNanos == 0L ? 0.0D
                : successfulPlacementNanos * 100.0D / trackedActiveNanos)
                + "% trackedActiveMs=" + trackedActiveNanos / 1_000_000.0D
                + " world=" + world);
    }

    private static Map<Item, Integer> requiredMaterials() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (BlockPos pos : TARGETS) {
            counts.merge(expectedState(pos).getBlock().asItem(), 1, Integer::sum);
        }
        return counts;
    }

    private static int countProfileMaterials(
            net.minecraft.world.entity.player.Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Blocks.STONE.asItem())
                    || stack.is(Blocks.COBBLESTONE.asItem())
                    || stack.is(Blocks.DIRT.asItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static List<BlockPos> createTargets() {
        List<BlockPos> targets = new ArrayList<>();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isTarget(pos)) targets.add(pos);
                }
            }
        }
        return List.copyOf(targets);
    }

    private static boolean isTarget(BlockPos pos) {
        if (pos.getX() == 0 && pos.getZ() == 0
                && (pos.getY() == 64 || pos.getY() == 65)) {
            return false;
        }
        int dx = pos.getX() - SHAPE_CENTER_X;
        int dy = pos.getY() - SHAPE_CENTER_Y;
        int dz = pos.getZ() - SHAPE_CENTER_Z;
        return dx * dx + dy * dy + dz * dz
                <= SHAPE_RADIUS_SQUARED;
    }

    private static BlockState expectedState(BlockPos pos) {
        Block block = switch (Math.floorMod(
                pos.getX() * 31 + pos.getY() * 17 + pos.getZ() * 13, 3)) {
            case 0 -> Blocks.STONE;
            case 1 -> Blocks.COBBLESTONE;
            default -> Blocks.DIRT;
        };
        return block.defaultBlockState();
    }

    private record WorldResult(
            int correctBlocks,
            int wrongBlocks,
            int extraBlocks,
            int remainingMaterials) {
    }

    private record ScanTotals(
            int rounds,
            long scanNanos,
            long planNanos,
            long captureNanos,
            long searchNanos,
            long completionNanos,
            long publishNanos,
            long stateReads,
            long printJobs) {
        static ScanTotals from(List<AsyncSearchCoordinator.RoundProfile> profiles) {
            long scan = 0L;
            long plan = 0L;
            long capture = 0L;
            long search = 0L;
            long completion = 0L;
            long publish = 0L;
            long reads = 0L;
            long jobs = 0L;
            for (AsyncSearchCoordinator.RoundProfile profile : profiles) {
                scan += profile.scanNanos();
                plan += profile.planNanos();
                capture += profile.captureNanos();
                search += profile.searchNanos();
                completion += profile.completionNanos();
                publish += profile.publishNanos();
                for (AsyncSearchCoordinator.RequestProfile request : profile.requests()) {
                    reads += request.stateReads();
                    if (request.moduleId().equals("print")) {
                        jobs += request.jobCount();
                    }
                }
            }
            return new ScanTotals(profiles.size(), scan, plan, capture, search,
                    completion, publish, reads, jobs);
        }

        long activeWorkNanos() {
            return planNanos + captureNanos + searchNanos
                    + completionNanos + publishNanos;
        }
    }
}
