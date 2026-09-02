package me.aleksilassila.litematica.printer.gametest;

import org.edtp.networkchaos.api.ChaosConfig;
import org.edtp.networkchaos.api.ChaosStats;
import org.edtp.networkchaos.api.ExactDropRule;
import org.edtp.networkchaos.api.LinkProfile;
import org.edtp.networkchaos.api.NetworkChaos;
import org.edtp.networkchaos.api.TrafficDirection;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("UnstableApiUsage")
public final class PacketLossReproductionGameTest implements FabricClientGameTest {
    private static final List<BlockPos> WRONG_BLOCK_TARGETS = row(2, 5, 64, 0);
    private static final List<BlockPos> GHOST_BLOCK_TARGETS = row(2, 5, 64, 2);
    private static final List<BlockPos> RANDOM_LOSS_TARGETS = rectangle(2, 9, 64, 4, 7);
    private static final List<BlockPos> MULTI_MATERIAL_TARGETS =
            rectangle(-8, 15, 64, 10, 21);
    private static final List<Block> MULTI_MATERIAL_BLOCKS = List.of(
            Blocks.STONE,
            Blocks.OAK_PLANKS,
            Blocks.GLASS,
            Blocks.IRON_BLOCK,
            Blocks.COBBLESTONE,
            Blocks.BRICKS,
            Blocks.POLISHED_ANDESITE,
            Blocks.TERRACOTTA);
    private static final int MATERIAL_COUNT = 16;
    private static final int WRONG_PLACEMENT_CARRIED_PACKET_DROPS = 2;
    private static final int RANDOM_LOSS_MATERIAL_COUNT = 64;
    private static final int MULTI_MATERIAL_STACK_COUNT = 48;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isScanPerformance()) return;
        if (GameTestMode.isBedrockIntegration()) return;
        if (Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        if (!Boolean.getBoolean("litematica-printer.gametest.networkFaults")) return;

        NetworkChaos.reset();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 3.5 65 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            int wrongHandSlot = prepareWrongPlacementInventory(
                    context, singleplayer, MATERIAL_COUNT);
            context.waitFor(client -> client.player != null
                    && client.level != null
                    && client.player.getInventory().getSelectedSlot() == wrongHandSlot
                    && client.player.getMainHandItem().is(Items.DIRT)
                    && client.player.getMainHandItem().getCount() == MATERIAL_COUNT);

            reproduceWrongBlockPlacement(context, singleplayer);
            reproduceGhostBlock(context, singleplayer);
            if ("legacy".equals(System.getProperty(
                    "litematica-printer.gametest.quickshulker", "none"))) {
                reproduceMultiMaterialQuickShulker(context, singleplayer);
            } else {
                reproduceGeneralPacketLoss(context, singleplayer);
            }
        } finally {
            context.runOnClient(client -> disablePrinter());
            TestSchematicRegion.clear();
            NetworkChaos.reset();
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();

            for (int x = -4; x <= 8; x++) {
                for (int z = -7; z <= 8; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            WRONG_BLOCK_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            GHOST_BLOCK_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            RANDOM_LOSS_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));

            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(9, new ItemStack(Items.STONE, MATERIAL_COUNT));
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static int prepareWrongPlacementInventory(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            int dirtCount) {
        int pickSlot = context.computeOnClient(client ->
                InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(
                        client.player.getInventory()));
        if (pickSlot < 0 || pickSlot > 8) {
            throw new AssertionError("No empty Litematica pick-block hotbar slot is available");
        }
        int wrongHandSlot = (pickSlot + 1) % 9;

        singleplayer.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().setSelectedSlot(wrongHandSlot);
            player.getInventory().setItem(
                    wrongHandSlot, new ItemStack(Items.DIRT, dirtCount));
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.runOnClient(client ->
                client.player.getInventory().setSelectedSlot(wrongHandSlot));
        return wrongHandSlot;
    }

    private static void prepareClientSlot(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.player == null) throw new AssertionError("Client player is missing");
            client.player.getInventory().setSelectedSlot(0);
        });
    }

    private static void reproduceWrongBlockPlacement(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        context.runOnClient(client -> {
            NetworkChaos.reset();
            NetworkChaos.enable(ChaosConfig.clear().withExactDropRules(
                    ExactDropRule.forPacketClass(
                            TrafficDirection.CLIENT_TO_SERVER,
                            ServerboundSetCarriedItemPacket.class,
                            WRONG_PLACEMENT_CARRIED_PACKET_DROPS)));
            configureFillPrinter(WRONG_BLOCK_TARGETS, true);
        });

        context.waitFor(client -> exactDroppedPackets()
                == WRONG_PLACEMENT_CARRIED_PACKET_DROPS);
        context.waitTicks(40);
        context.runOnClient(client -> disablePrinter());

        WorldState serverState = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return new WorldState(
                    countBlocks(server.overworld(), WRONG_BLOCK_TARGETS, Blocks.DIRT),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.STONE),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.DIRT));
        });
        WorldState clientState = context.computeOnClient(client -> new WorldState(
                countBlocks(client.level, WRONG_BLOCK_TARGETS, Blocks.DIRT),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.STONE),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.DIRT)));

        System.out.println("[Litematica Printer GameTest] Wrong-placement observation: server="
                + serverState + ", client=" + clientState
                + ", droppedCarriedItemPackets="
                + exactDroppedPackets());

        if (serverState.matchingTargets() != WRONG_BLOCK_TARGETS.size()
                || clientState.matchingTargets() != WRONG_BLOCK_TARGETS.size()
                || serverState.stoneCount() != MATERIAL_COUNT
                || serverState.dirtCount() != MATERIAL_COUNT - WRONG_BLOCK_TARGETS.size()) {
            throw new AssertionError("Failed to reproduce wrong-block placement after dropping "
                    + "the carried-item packet: server=" + serverState
                    + ", client=" + clientState);
        }
        System.out.println("[Litematica Printer GameTest] Reproduced wrong placement: "
                + "client requested stone, server placed dirt; server=" + serverState
                + ", client=" + clientState);
    }

    private static void reproduceGhostBlock(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            GHOST_BLOCK_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(0, new ItemStack(Items.STONE, MATERIAL_COUNT));
            player.inventoryMenu.sendAllDataToRemote();
        });
        prepareClientSlot(context);
        context.waitFor(client -> client.player != null
                && client.level != null
                && client.player.getInventory().getSelectedSlot() == 0
                && client.player.getMainHandItem().is(Items.STONE)
                && client.player.getMainHandItem().getCount() == MATERIAL_COUNT
                && countBlocks(client.level, GHOST_BLOCK_TARGETS, Blocks.AIR)
                == GHOST_BLOCK_TARGETS.size());

        context.runOnClient(client -> {
            NetworkChaos.reset();
            NetworkChaos.enable(ChaosConfig.clear().withExactDropRules(
                    ExactDropRule.forPacketClass(
                            TrafficDirection.CLIENT_TO_SERVER,
                            ServerboundUseItemOnPacket.class,
                            GHOST_BLOCK_TARGETS.size())));
            configureFillPrinter(GHOST_BLOCK_TARGETS, false);
        });

        context.waitFor(client -> exactDroppedPackets()
                == GHOST_BLOCK_TARGETS.size()
                && client.level != null
                && countBlocks(client.level, GHOST_BLOCK_TARGETS, Blocks.STONE)
                == GHOST_BLOCK_TARGETS.size());
        context.waitTicks(40);
        context.runOnClient(client -> disablePrinter());

        WorldState serverState = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return new WorldState(
                    countBlocks(server.overworld(), GHOST_BLOCK_TARGETS, Blocks.AIR),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.STONE),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.DIRT));
        });
        WorldState clientState = context.computeOnClient(client -> new WorldState(
                countBlocks(client.level, GHOST_BLOCK_TARGETS, Blocks.STONE),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.STONE),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.DIRT)));

        if (serverState.matchingTargets() != GHOST_BLOCK_TARGETS.size()
                || clientState.matchingTargets() != GHOST_BLOCK_TARGETS.size()
                || serverState.stoneCount() != MATERIAL_COUNT
                || clientState.stoneCount()
                != MATERIAL_COUNT - GHOST_BLOCK_TARGETS.size()) {
            throw new AssertionError("Failed to reproduce a persistent ghost block after dropping "
                    + "the use-item-on packet: server=" + serverState
                    + ", client=" + clientState);
        }
        System.out.println("[Litematica Printer GameTest] Reproduced ghost block after 40 ticks: "
                + "server remained air while client retained stone; server=" + serverState
                + ", client=" + clientState);
    }

    private static void reproduceGeneralPacketLoss(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runCommand("tp @p 5.5 65 2.5");
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            RANDOM_LOSS_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(
                    9, new ItemStack(Items.STONE, RANDOM_LOSS_MATERIAL_COUNT));
            player.inventoryMenu.sendAllDataToRemote();
        });
        prepareClientSlot(context);
        context.waitFor(client -> client.player != null
                && client.level != null
                && countItem(client.player.getInventory().getNonEquipmentItems(), Items.STONE)
                == RANDOM_LOSS_MATERIAL_COUNT
                && countBlocks(client.level, RANDOM_LOSS_TARGETS, Blocks.AIR)
                == RANDOM_LOSS_TARGETS.size());
        int wrongHandSlot = prepareWrongPlacementInventory(
                context, singleplayer, RANDOM_LOSS_MATERIAL_COUNT);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == wrongHandSlot
                && client.player.getMainHandItem().is(Items.DIRT)
                && client.player.getMainHandItem().getCount() == RANDOM_LOSS_MATERIAL_COUNT);

        context.runOnClient(client -> {
            NetworkChaos.reset();
            NetworkChaos.enable(randomLossConfig(0x4C4954454D415449L, 35));
            configureFillPrinter(RANDOM_LOSS_TARGETS, false);
        });
        context.waitTicks(80);
        ChaosStats loss = context.computeOnClient(client -> {
            disablePrinter();
            NetworkChaos.disable();
            return NetworkChaos.stats();
        });
        context.waitTicks(40);

        ChaosWorldState serverState = singleplayer.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            return new ChaosWorldState(
                    blocksAt(server.overworld(), RANDOM_LOSS_TARGETS),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.STONE),
                    countItem(player.getInventory().getNonEquipmentItems(), Items.DIRT));
        });
        ChaosWorldState clientState = context.computeOnClient(client -> new ChaosWorldState(
                blocksAt(client.level, RANDOM_LOSS_TARGETS),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.STONE),
                countItem(client.player.getInventory().getNonEquipmentItems(), Items.DIRT)));

        int mismatches = 0;
        int wrongBlocks = 0;
        int ghostBlocks = 0;
        for (int i = 0; i < RANDOM_LOSS_TARGETS.size(); i++) {
            Block serverBlock = serverState.blocks().get(i);
            Block clientBlock = clientState.blocks().get(i);
            if (serverBlock != clientBlock) mismatches++;
            if (serverBlock == Blocks.DIRT) wrongBlocks++;
            if (serverBlock == Blocks.AIR && clientBlock == Blocks.STONE) ghostBlocks++;
        }

        System.out.println("[Litematica Printer GameTest] General 35% packet loss: loss="
                + loss + ", wrongBlocks=" + wrongBlocks + ", ghostBlocks=" + ghostBlocks
                + ", clientServerMismatches=" + mismatches + ", server=" + serverState
                + ", client=" + clientState);
        if (droppedPackets(loss) < 2
                || loss.clientToServer().dropped() == 0
                || loss.serverToClient().dropped() == 0
                || (wrongBlocks == 0 && ghostBlocks == 0 && mismatches == 0)) {
            throw new AssertionError("General packet loss did not reproduce a placement anomaly: "
                    + "loss=" + loss + ", wrongBlocks=" + wrongBlocks
                    + ", ghostBlocks=" + ghostBlocks + ", mismatches=" + mismatches);
        }
    }

    private static void reproduceMultiMaterialQuickShulker(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer) {
        String actualVersion = FabricLoader.getInstance()
                .getModContainer("quickshulker")
                .orElseThrow(() -> new AssertionError("QuickShulker is not loaded"))
                .getMetadata().getVersion().getFriendlyString();
        if (!actualVersion.equals("3.0.4-26.2")) {
            throw new AssertionError("Expected QuickShulker 3.0.4-26.2 but loaded "
                    + actualVersion);
        }

        prepareMultiMaterialWorld(singleplayer);
        singleplayer.getServer().runCommand("tp @p 3.5 65 6.5");
        context.waitFor(client -> multiMaterialInventoryArrived(
                    client.player.getInventory().getNonEquipmentItems())
                && countBlocks(client.level, MULTI_MATERIAL_TARGETS, Blocks.AIR)
                == MULTI_MATERIAL_TARGETS.size());
        context.runOnClient(client -> {
            prepareMultiMaterialSchematic();
            configurePrintPrinter(MULTI_MATERIAL_TARGETS);
        });

        context.waitTicks(240);
        context.runOnClient(client -> disablePrinter());
        MultiMaterialWorldState baselineServer = singleplayer.getServer()
                .computeOnServer(server -> readMultiMaterialState(
                        server.overworld(),
                        server.getPlayerList().getPlayers().getFirst()
                                .getInventory().getNonEquipmentItems()));
        if (baselineServer.correctBlocks() != MULTI_MATERIAL_TARGETS.size()
                || baselineServer.distinctCorrectMaterials()
                != MULTI_MATERIAL_BLOCKS.size()
                || baselineServer.storedMaterials()
                >= MULTI_MATERIAL_STACK_COUNT * MULTI_MATERIAL_BLOCKS.size()) {
            throw new AssertionError("Large multi-material QuickShulker baseline failed: "
                    + baselineServer);
        }
        System.out.println("[Litematica Printer GameTest] QuickShulker 3.0.4 baseline: "
                + MULTI_MATERIAL_TARGETS.size() + " blocks across "
                + MULTI_MATERIAL_BLOCKS.size() + " materials placed; server="
                + baselineServer);

        prepareMultiMaterialWorld(singleplayer);
        context.waitFor(client -> multiMaterialInventoryArrived(
                    client.player.getInventory().getNonEquipmentItems())
                && countBlocks(client.level, MULTI_MATERIAL_TARGETS, Blocks.AIR)
                == MULTI_MATERIAL_TARGETS.size());
        context.runOnClient(client -> {
            NetworkChaos.reset();
            NetworkChaos.enable(randomLossConfig(6L, 15)
                    .withExactDropRules(ExactDropRule.forPacketClass(
                            TrafficDirection.CLIENT_TO_SERVER,
                            ServerboundContainerClickPacket.class,
                            2)));
            configurePrintPrinter(MULTI_MATERIAL_TARGETS);
        });
        context.waitTicks(320);
        ChaosStats loss = context.computeOnClient(client -> {
            disablePrinter();
            NetworkChaos.disable();
            return NetworkChaos.stats();
        });
        context.waitTicks(40);

        MultiMaterialWorldState serverState = singleplayer.getServer()
                .computeOnServer(server -> readMultiMaterialState(
                        server.overworld(),
                        server.getPlayerList().getPlayers().getFirst()
                                .getInventory().getNonEquipmentItems()));
        MultiMaterialWorldState clientState = context.computeOnClient(client ->
                readMultiMaterialState(client.level,
                        client.player.getInventory().getNonEquipmentItems()));
        MultiMaterialComparison comparison = compareMultiMaterialWorlds(
                serverState.blocks(), clientState.blocks());
        long containerClicks = loss.exactDropRules().getFirst().matched();
        long exactContainerClickDrops = loss.exactDropRules().getFirst().dropped();

        System.out.println("[Litematica Printer GameTest] QuickShulker 3.0.4 multi-material "
                + "15% all-packet loss: loss=" + loss
                + ", containerClicks=" + containerClicks
                + ", exactContainerClickDrops=" + exactContainerClickDrops
                + ", comparison=" + comparison
                + ", server=" + serverState + ", client=" + clientState);
        if (droppedPackets(loss) < 2
                || containerClicks == 0
                || exactContainerClickDrops != 2
                || serverState.distinctCorrectMaterials() < 2
                || (comparison.wrongBlocks() == 0
                    && comparison.ghostBlocks() == 0
                    && comparison.clientServerMismatches() == 0)) {
            throw new AssertionError("Multi-material QuickShulker packet loss did not exercise "
                    + "switching and reproduce an anomaly: loss=" + loss
                    + ", containerClicks=" + containerClicks
                    + ", comparison=" + comparison
                    + ", server=" + serverState + ", client=" + clientState);
        }
    }

    private static void prepareMultiMaterialWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            var interactionRange = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (interactionRange == null) {
                throw new AssertionError("Player has no block interaction range attribute");
            }
            interactionRange.setBaseValue(24.0D);
            for (int x = -9; x <= 16; x++) {
                for (int z = 9; z <= 22; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            MULTI_MATERIAL_TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));

            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            List<ItemStack> contents = MULTI_MATERIAL_BLOCKS.stream()
                    .map(block -> new ItemStack(
                            block.asItem(), MULTI_MATERIAL_STACK_COUNT))
                    .toList();
            shulker.set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(contents));
            player.getInventory().setItem(9, shulker);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static boolean multiMaterialInventoryArrived(List<ItemStack> inventory) {
        return countStoredMaterials(inventory)
                == MULTI_MATERIAL_STACK_COUNT * MULTI_MATERIAL_BLOCKS.size();
    }

    private static void prepareMultiMaterialSchematic() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("Schematic world is unavailable");
        for (int i = 0; i < MULTI_MATERIAL_TARGETS.size(); i++) {
            schematic.setBlock(
                    MULTI_MATERIAL_TARGETS.get(i),
                    expectedMultiMaterialBlock(i).defaultBlockState(),
                    3);
        }
        TestSchematicRegion.activate(
                MULTI_MATERIAL_TARGETS.getFirst(),
                MULTI_MATERIAL_TARGETS.getLast());
    }

    private static void configurePrintPrinter(List<BlockPos> targets) {
        configureSelection(targets);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(20.0D);
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
        Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void configureSelection(List<BlockPos> targets) {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var selectionManager = DataManager.getSelectionManager();
        if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) {
            selectionManager.switchSelectionMode();
        }
        AreaSelection selection = DataManager.getSimpleArea();
        Box box = selection.getSubRegionBox(selection.getName());
        if (box == null) box = selection.getSelectedSubRegionBox();
        if (box == null) throw new AssertionError("Litematica simple selection has no box");
        box.setPos1(targets.getFirst());
        box.setPos2(targets.getLast());
    }

    private static MultiMaterialWorldState readMultiMaterialState(
            Level level, List<ItemStack> inventory) {
        List<Block> blocks = blocksAt(level, MULTI_MATERIAL_TARGETS);
        int correct = 0;
        Set<Block> distinct = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block expected = expectedMultiMaterialBlock(i);
            if (blocks.get(i) == expected) {
                correct++;
                distinct.add(expected);
            }
        }
        return new MultiMaterialWorldState(
                blocks, correct, distinct.size(), countStoredMaterials(inventory));
    }

    private static MultiMaterialComparison compareMultiMaterialWorlds(
            List<Block> serverBlocks, List<Block> clientBlocks) {
        int wrong = 0;
        int ghosts = 0;
        int mismatches = 0;
        for (int i = 0; i < serverBlocks.size(); i++) {
            Block expected = expectedMultiMaterialBlock(i);
            Block serverBlock = serverBlocks.get(i);
            Block clientBlock = clientBlocks.get(i);
            if (serverBlock != Blocks.AIR && serverBlock != expected) wrong++;
            if (serverBlock != expected && clientBlock == expected) ghosts++;
            if (serverBlock != clientBlock) mismatches++;
        }
        return new MultiMaterialComparison(wrong, ghosts, mismatches);
    }

    private static int countCorrectBlocks(Level level, List<BlockPos> positions) {
        int correct = 0;
        for (int i = 0; i < positions.size(); i++) {
            if (level.getBlockState(positions.get(i))
                    .is(expectedMultiMaterialBlock(i))) {
                correct++;
            }
        }
        return correct;
    }

    private static Block expectedMultiMaterialBlock(int index) {
        return MULTI_MATERIAL_BLOCKS.get(index % MULTI_MATERIAL_BLOCKS.size());
    }

    private static int countStoredMaterials(List<ItemStack> inventory) {
        int count = 0;
        for (ItemStack stack : inventory) {
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) continue;
            count += contents.nonEmptyItemCopyStream()
                    .filter(item -> MULTI_MATERIAL_BLOCKS.stream()
                            .anyMatch(block -> item.is(block.asItem())))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }
        return count;
    }

    private static ChaosConfig randomLossConfig(long seed, int lossPercent) {
        double lossRate = lossPercent / 100.0D;
        LinkProfile lossy = new LinkProfile(
                lossRate, 0L, 0L, 0.0D, 0.0D, 0L);
        return new ChaosConfig(
                lossy,
                lossy,
                seed,
                true,
                ChaosConfig.ALL_PACKETS,
                ChaosConfig.NO_PACKETS);
    }

    private static long droppedPackets(ChaosStats stats) {
        return stats.clientToServer().dropped()
                + stats.serverToClient().dropped();
    }

    private static long exactDroppedPackets() {
        return NetworkChaos.stats().exactDropRules().stream()
                .mapToLong(rule -> rule.dropped())
                .sum();
    }

    private static int countItem(List<ItemStack> stacks, Item item) {
        return stacks.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static int countBlocks(Level level, List<BlockPos> positions, Block block) {
        return (int) positions.stream()
                .filter(pos -> level.getBlockState(pos).is(block))
                .count();
    }

    private static List<BlockPos> row(int minX, int maxX, int y, int z) {
        return java.util.stream.IntStream.rangeClosed(minX, maxX)
                .mapToObj(x -> new BlockPos(x, y, z))
                .toList();
    }

    private static List<BlockPos> rectangle(
            int minX, int maxX, int y, int minZ, int maxZ) {
        return java.util.stream.IntStream.rangeClosed(minZ, maxZ)
                .boxed()
                .flatMap(z -> java.util.stream.IntStream.rangeClosed(minX, maxX)
                        .mapToObj(x -> new BlockPos(x, y, z)))
                .toList();
    }

    private static List<Block> blocksAt(Level level, List<BlockPos> positions) {
        return positions.stream()
                .map(pos -> level.getBlockState(pos).getBlock())
                .toList();
    }

    private static void configureFillPrinter(
            List<BlockPos> targets, boolean packetPlacement) {
        configureSelection(targets);

        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(8.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(packetPlacement);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(targets.size());
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.REPLACEABLE_LIST.setStrings(List.of());
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
        Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:stone"));
        Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(FillModeFacingType.DOWN);
        Configs.Fill.ENABLED.setBooleanValue(true);
        ModuleManager.FILL.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void disablePrinter() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        ModuleManager.PRINT.resetScanState();
        ModuleManager.FILL.resetScanState();
    }

    private record WorldState(
            int matchingTargets,
            int stoneCount,
            int dirtCount) {
    }

    private record ChaosWorldState(
            List<Block> blocks,
            int stoneCount,
            int dirtCount) {
    }

    private record MultiMaterialWorldState(
            List<Block> blocks,
            int correctBlocks,
            int distinctCorrectMaterials,
            int storedMaterials) {
        @Override
        public String toString() {
            return "MultiMaterialWorldState[correctBlocks=" + correctBlocks
                    + ", distinctCorrectMaterials=" + distinctCorrectMaterials
                    + ", storedMaterials=" + storedMaterials + ']';
        }
    }

    private record MultiMaterialComparison(
            int wrongBlocks,
            int ghostBlocks,
            int clientServerMismatches) {
    }
}
