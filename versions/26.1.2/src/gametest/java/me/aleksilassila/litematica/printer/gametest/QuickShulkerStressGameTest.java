package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observational stress test for the direct and legacy Quick Shulker paths.
 * It deliberately does not require the legacy path to finish under packet loss;
 * the structured result records stalls, divergence and inventory conservation.
 */
@SuppressWarnings("UnstableApiUsage")
public final class QuickShulkerStressGameTest implements FabricClientGameTest {
    private static final List<Block> MATERIALS = List.of(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.OAK_PLANKS,
            Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_PLANKS,
            Blocks.BRICKS,
            Blocks.STONE_BRICKS,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.IRON_BLOCK,
            Blocks.GOLD_BLOCK,
            Blocks.DIAMOND_BLOCK,
            Blocks.EMERALD_BLOCK,
            Blocks.LAPIS_BLOCK,
            Blocks.REDSTONE_BLOCK,
            Blocks.POLISHED_ANDESITE,
            Blocks.TERRACOTTA,
            Blocks.GLASS,
            Blocks.OBSIDIAN);
    private static final List<Item> NOISE_ITEMS = List.of(
            Items.STICK,
            Items.APPLE,
            Items.COAL,
            Items.CHARCOAL,
            Items.FEATHER,
            Items.FLINT,
            Items.BONE,
            Items.STRING,
            Items.LEATHER,
            Items.PAPER,
            Items.BOOK,
            Items.CLAY_BALL,
            Items.BRICK,
            Items.SNOWBALL,
            Items.EGG,
            Items.WHEAT,
            Items.CARROT,
            Items.POTATO);
    private static final List<BlockPos> TARGETS = rectangle(-8, 9, 64, 2, 9);

    private static final int LOOSE_PER_MATERIAL = 2;
    private static final int BOXED_PER_MATERIAL = 10;
    private static final int EXPECTED_INITIAL_MATERIALS =
            MATERIALS.size() * (LOOSE_PER_MATERIAL + BOXED_PER_MATERIAL);
    private static final int LOSS_PERCENT = Integer.getInteger(
            "litematica-printer.gametest.quickshulkerStressLossPercent", 15);
    private static final long LOSS_SEED = 0x515549434B53484CL;
    private static final int MAX_TICKS = 800;
    private static final int SAMPLE_TICKS = 5;
    private static final int CONVERGED_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;

        String mode = System.getProperty(
                "litematica-printer.gametest.quickshulker", "none");
        if (!mode.equals("direct") && !mode.equals("legacy")) {
            throw new AssertionError(
                    "QuickShulker stress mode must be direct or legacy, not " + mode);
        }
        assertLoadedVersion(mode);

        NetworkFaultController.reset();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 0.5 65 0.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && client.level != null
                    && initialInventoryReady(client.player.getInventory())
                    && readWorld(client.level).missing() == TARGETS.size());

            context.runOnClient(client -> {
                assertSelectedPath(mode);
                client.player.getInventory().setSelectedSlot(0);
                prepareSchematic();
                configurePrinter();
                if (LOSS_PERCENT > 0) {
                    NetworkFaultController.startRandomLoss(LOSS_SEED, LOSS_PERCENT);
                }
            });

            int elapsedTicks = 0;
            int firstServerCompleteTick = -1;
            int firstConvergedTick = -1;
            int busyStreak = 0;
            int maxBusyStreak = 0;
            int convergedStreak = 0;
            boolean sawForeignContainer = false;

            while (elapsedTicks < MAX_TICKS) {
                context.waitTicks(SAMPLE_TICKS);
                elapsedTicks += SAMPLE_TICKS;

                ServerSnapshot server = singleplayer.getServer().computeOnServer(gameServer -> {
                    var player = gameServer.getPlayerList().getPlayers().getFirst();
                    return new ServerSnapshot(
                            readWorld(gameServer.overworld()),
                            readInventory(player.getInventory(), player.containerMenu.getCarried()));
                });
                ClientSnapshot client = context.computeOnClient(minecraft ->
                        new ClientSnapshot(
                                readWorld(minecraft.level),
                                readInventory(minecraft.player.getInventory(),
                                        minecraft.player.containerMenu.getCarried()),
                                QuickShulkerCompat.isBusy(),
                                minecraft.player.containerMenu != minecraft.player.inventoryMenu));

                sawForeignContainer |= client.foreignContainer();
                busyStreak = client.busy() ? busyStreak + SAMPLE_TICKS : 0;
                maxBusyStreak = Math.max(maxBusyStreak, busyStreak);

                if (firstServerCompleteTick < 0
                        && server.world().correct() == TARGETS.size()) {
                    firstServerCompleteTick = elapsedTicks;
                }
                boolean converged = server.world().correct() == TARGETS.size()
                        && client.world().correct() == TARGETS.size()
                        && !client.busy() && !client.foreignContainer();
                if (converged) {
                    if (firstConvergedTick < 0) firstConvergedTick = elapsedTicks;
                    convergedStreak += SAMPLE_TICKS;
                    if (convergedStreak >= CONVERGED_TICKS) break;
                } else {
                    convergedStreak = 0;
                }
            }

            int sampledTicks = elapsedTicks;
            NetworkFaultController.RandomLossSnapshot loss = context.computeOnClient(client -> {
                disablePrinter();
                return NetworkFaultController.stopRandomLoss();
            });
            ServerSnapshot server = singleplayer.getServer().computeOnServer(gameServer -> {
                var player = gameServer.getPlayerList().getPlayers().getFirst();
                return new ServerSnapshot(
                        readWorld(gameServer.overworld()),
                        readInventory(player.getInventory(), player.containerMenu.getCarried()));
            });
            ClientSnapshot client = context.computeOnClient(minecraft ->
                    new ClientSnapshot(
                            readWorld(minecraft.level),
                            readInventory(minecraft.player.getInventory(),
                                    minecraft.player.containerMenu.getCarried()),
                            QuickShulkerCompat.isBusy(),
                            minecraft.player.containerMenu != minecraft.player.inventoryMenu));
            Comparison comparison = compare(server.world(), client.world());
            LossSummary lossSummary = summarize(loss);
            StressResult result = new StressResult(
                    mode,
                    LOSS_PERCENT,
                    sampledTicks,
                    firstServerCompleteTick,
                    firstConvergedTick,
                    maxBusyStreak,
                    sawForeignContainer,
                    server,
                    client,
                    comparison,
                    server.inventory().totalTargetItems()
                            + server.world().targetMaterialBlocks()
                            - EXPECTED_INITIAL_MATERIALS,
                    lossSummary);

            System.out.println("[Litematica Printer GameTest] QuickShulker stress result: "
                    + result);
            validateObservation(mode, result);
        } finally {
            NetworkFaultController.reset();
            TestSchematicRegion.clear();
            context.runOnClient(client -> {
                disablePrinter();
                if (client.player != null
                        && client.player.containerMenu != client.player.inventoryMenu) {
                    client.player.closeContainer();
                }
                QuickShulkerCompat.onDisconnect();
            });
        }
    }

    private static void assertLoadedVersion(String mode) {
        String version = FabricLoader.getInstance()
                .getModContainer("quickshulker")
                .orElseThrow(() -> new AssertionError("QuickShulker is not installed"))
                .getMetadata().getVersion().getFriendlyString();
        String expected = mode.equals("direct") ? "4.0.0" : "3.0.4";
        if (!version.startsWith(expected)) {
            throw new AssertionError(
                    "Expected QuickShulker " + expected + " but loaded " + version);
        }
    }

    private static void assertSelectedPath(String mode) {
        try {
            Field selectedPath = QuickShulkerCompat.class.getDeclaredField("selectedPath");
            selectedPath.setAccessible(true);
            String actual = selectedPath.get(null).toString();
            String expected = mode.equals("direct") ? "DIRECT" : "LEGACY";
            if (!actual.equals(expected)) {
                throw new AssertionError(
                        "Stress test selected " + actual + " instead of " + expected);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inspect QuickShulker path", error);
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            var interactionRange = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (interactionRange == null) {
                throw new AssertionError("Player has no block interaction range attribute");
            }
            interactionRange.setBaseValue(32.0D);

            for (int x = -9; x <= 10; x++) {
                for (int z = -1; z <= 10; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));

            Inventory inventory = player.getInventory();
            inventory.clearContent();
            inventory.setSelectedSlot(0);
            for (int index = 0; index < MATERIALS.size(); index++) {
                inventory.setItem(index, new ItemStack(
                        MATERIALS.get(index).asItem(), LOOSE_PER_MATERIAL));

                ItemStack box = new ItemStack(Items.SHULKER_BOX);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                        new ItemStack(MATERIALS.get(index).asItem(), BOXED_PER_MATERIAL),
                        new ItemStack(NOISE_ITEMS.get(index), index % 8 + 1))));
                inventory.setItem(MATERIALS.size() + index, box);
            }
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static boolean initialInventoryReady(Inventory inventory) {
        InventorySnapshot snapshot = readInventory(inventory, ItemStack.EMPTY);
        return snapshot.occupiedSlots() == 36
                && snapshot.shulkerBoxes() == 18
                && snapshot.looseTargetItems()
                == MATERIALS.size() * LOOSE_PER_MATERIAL
                && snapshot.boxedTargetItems()
                == MATERIALS.size() * BOXED_PER_MATERIAL;
    }

    private static void prepareSchematic() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("Schematic world is unavailable");

        BlockPos first = TARGETS.getFirst();
        BlockPos last = TARGETS.getLast();
        int minChunkX = Math.floorDiv(Math.min(first.getX(), last.getX()), 16);
        int maxChunkX = Math.floorDiv(Math.max(first.getX(), last.getX()), 16);
        int minChunkZ = Math.floorDiv(Math.min(first.getZ(), last.getZ()), 16);
        int maxChunkZ = Math.floorDiv(Math.max(first.getZ(), last.getZ()), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                schematic.getChunkSource().loadChunk(chunkX, chunkZ);
            }
        }

        for (int index = 0; index < TARGETS.size(); index++) {
            schematic.setBlock(TARGETS.get(index),
                    expectedBlock(index).defaultBlockState(), 3);
        }

        for (int index = 0; index < TARGETS.size(); index++) {
            BlockPos pos = TARGETS.get(index);
            Block expected = expectedBlock(index);
            Block actual = schematic.getBlockState(pos).getBlock();
            if (actual != expected) {
                throw new AssertionError("Schematic fixture mismatch at " + pos
                        + ": expected " + expected + " but read " + actual);
            }
        }
        TestSchematicRegion.activate(TARGETS.getFirst(), TARGETS.getLast());
    }

    private static void configurePrinter() {
        configureSelection();
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(24.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(16);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
        Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
        Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0);
        Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void configureSelection() {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var selectionManager = DataManager.getSelectionManager();
        if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) {
            selectionManager.switchSelectionMode();
        }
        AreaSelection selection = DataManager.getSimpleArea();
        Box box = selection.getSubRegionBox(selection.getName());
        if (box == null) box = selection.getSelectedSubRegionBox();
        if (box == null) throw new AssertionError("Litematica simple selection has no box");
        box.setPos1(TARGETS.getFirst());
        box.setPos2(TARGETS.getLast());
    }

    private static void disablePrinter() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        ModuleManager.PRINT.resetScanState();
    }

    private static WorldSnapshot readWorld(Level level) {
        int correct = 0;
        int wrong = 0;
        int missing = 0;
        int targetMaterialBlocks = 0;
        Set<Block> distinctCorrect = new HashSet<>();
        Set<String> anomalies = new HashSet<>();
        List<Block> blocks = TARGETS.stream()
                .map(pos -> level.getBlockState(pos).getBlock())
                .toList();
        for (int index = 0; index < blocks.size(); index++) {
            Block actual = blocks.get(index);
            Block expected = expectedBlock(index);
            if (MATERIALS.contains(actual)) targetMaterialBlocks++;
            if (actual == expected) {
                correct++;
                distinctCorrect.add(actual);
            } else if (actual == Blocks.AIR) {
                missing++;
                anomalies.add(expected + "->air");
            } else {
                wrong++;
                anomalies.add(expected + "->" + actual);
            }
        }
        return new WorldSnapshot(
                correct, wrong, missing, distinctCorrect.size(), targetMaterialBlocks,
                Set.copyOf(anomalies), blocks);
    }

    private static InventorySnapshot readInventory(Inventory inventory, ItemStack carried) {
        int occupied = 0;
        int shulkers = 0;
        int loose = 0;
        int boxed = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            occupied++;
            if (isTargetItem(stack)) loose += stack.getCount();
            if (!stack.is(Items.SHULKER_BOX)) continue;
            shulkers++;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                boxed += contents.nonEmptyItemCopyStream()
                        .filter(QuickShulkerStressGameTest::isTargetItem)
                        .mapToInt(ItemStack::getCount)
                        .sum();
            }
        }
        int carriedItems = isTargetItem(carried) ? carried.getCount() : 0;
        return new InventorySnapshot(
                occupied, shulkers, loose, boxed, carriedItems,
                loose + boxed + carriedItems);
    }

    private static boolean isTargetItem(ItemStack stack) {
        return MATERIALS.stream().anyMatch(block -> stack.is(block.asItem()));
    }

    private static Comparison compare(WorldSnapshot server, WorldSnapshot client) {
        int mismatches = 0;
        int ghosts = 0;
        for (int index = 0; index < TARGETS.size(); index++) {
            Block serverBlock = server.blocks().get(index);
            Block clientBlock = client.blocks().get(index);
            if (serverBlock != clientBlock) mismatches++;
            if (serverBlock != expectedBlock(index)
                    && clientBlock == expectedBlock(index)) {
                ghosts++;
            }
        }
        return new Comparison(mismatches, ghosts);
    }

    private static LossSummary summarize(NetworkFaultController.RandomLossSnapshot loss) {
        return new LossSummary(
                loss.seenPackets(),
                loss.droppedPackets(),
                loss.maxConsecutiveDrops(),
                packetCount(loss.seenByClass(), "ServerboundCustomPayloadPacket"),
                packetCount(loss.droppedByClass(), "ServerboundCustomPayloadPacket"),
                packetCount(loss.seenByClass(), "ClientboundCustomPayloadPacket"),
                packetCount(loss.droppedByClass(), "ClientboundCustomPayloadPacket"),
                packetCount(loss.seenByClass(), "ServerboundContainerClickPacket"),
                packetCount(loss.droppedByClass(), "ServerboundContainerClickPacket"),
                packetCount(loss.seenByClass(), "ClientboundContainerSetContentPacket"),
                packetCount(loss.droppedByClass(), "ClientboundContainerSetContentPacket"),
                packetCount(loss.seenByClass(), "ServerboundUseItemOnPacket"),
                packetCount(loss.droppedByClass(), "ServerboundUseItemOnPacket"));
    }

    private static long packetCount(Map<String, Long> packets, String simpleName) {
        return packets.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith('.' + simpleName))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private static void validateObservation(String mode, StressResult result) {
        if (LOSS_PERCENT > 0 && (result.loss().droppedPackets() < 10
                || result.loss().maxConsecutiveDrops() < 2)) {
            throw new AssertionError("Stress test did not exercise meaningful packet loss: "
                    + result.loss());
        }
        if (mode.equals("direct") && result.sawForeignContainer()) {
            throw new AssertionError("Direct protocol opened a Screen during stress: " + result);
        }
    }

    private static Block expectedBlock(int index) {
        return MATERIALS.get(index % MATERIALS.size());
    }

    private static List<BlockPos> rectangle(
            int minX, int maxX, int y, int minZ, int maxZ) {
        return java.util.stream.IntStream.rangeClosed(minZ, maxZ)
                .boxed()
                .flatMap(z -> java.util.stream.IntStream.rangeClosed(minX, maxX)
                        .mapToObj(x -> new BlockPos(x, y, z)))
                .toList();
    }

    private record WorldSnapshot(
            int correct,
            int wrong,
            int missing,
            int distinctCorrectMaterials,
            int targetMaterialBlocks,
            Set<String> anomalies,
            List<Block> blocks) {
        @Override
        public String toString() {
            return "World[correct=" + correct
                    + ", wrong=" + wrong
                    + ", missing=" + missing
                    + ", materials=" + distinctCorrectMaterials
                    + ", anomalies=" + anomalies + ']';
        }
    }

    private record InventorySnapshot(
            int occupiedSlots,
            int shulkerBoxes,
            int looseTargetItems,
            int boxedTargetItems,
            int carriedTargetItems,
            int totalTargetItems) {
    }

    private record ServerSnapshot(WorldSnapshot world, InventorySnapshot inventory) {
    }

    private record ClientSnapshot(
            WorldSnapshot world,
            InventorySnapshot inventory,
            boolean busy,
            boolean foreignContainer) {
    }

    private record Comparison(int clientServerMismatches, int ghostBlocks) {
    }

    private record LossSummary(
            long seenPackets,
            long droppedPackets,
            long maxConsecutiveDrops,
            long serverboundCustomPayloadsSeen,
            long serverboundCustomPayloadsDropped,
            long clientboundCustomPayloadsSeen,
            long clientboundCustomPayloadsDropped,
            long containerClicksSeen,
            long containerClicksDropped,
            long containerContentsSeen,
            long containerContentsDropped,
            long placementsSeen,
            long placementsDropped) {
    }

    private record StressResult(
            String mode,
            int lossPercent,
            int sampledTicks,
            int firstServerCompleteTick,
            int firstConvergedTick,
            int maxBusyStreak,
            boolean sawForeignContainer,
            ServerSnapshot server,
            ClientSnapshot client,
            Comparison comparison,
            int serverConservationDelta,
            LossSummary loss) {
    }
}
