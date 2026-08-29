package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class PacketLossReproductionGameTest implements FabricClientGameTest {
    private static final List<BlockPos> WRONG_BLOCK_TARGETS = row(2, 5, 64, 0);
    private static final List<BlockPos> GHOST_BLOCK_TARGETS = row(2, 5, 64, 2);
    private static final List<BlockPos> RANDOM_LOSS_TARGETS = rectangle(2, 9, 64, 4, 7);
    private static final int MATERIAL_COUNT = 16;
    private static final int RANDOM_LOSS_MATERIAL_COUNT = 64;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        if (!Boolean.getBoolean("litematica-printer.gametest.networkFaults")) return;

        NetworkFaultController.reset();
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
            reproduceGeneralPacketLoss(context, singleplayer);
        } finally {
            context.runOnClient(client -> disablePrinter());
            NetworkFaultController.reset();
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
            NetworkFaultController.arm(
                    NetworkFaultController.Fault.DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON);
            configureFillPrinter(WRONG_BLOCK_TARGETS, true);
        });

        context.waitFor(client -> NetworkFaultController.droppedCarriedItemPackets() > 0);
        context.waitFor(client -> client.level != null
                && countBlocks(client.level, WRONG_BLOCK_TARGETS, Blocks.DIRT)
                == WRONG_BLOCK_TARGETS.size());
        context.waitTicks(5);
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
                + NetworkFaultController.droppedCarriedItemPackets());

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
            NetworkFaultController.armUseItemOnBurst(GHOST_BLOCK_TARGETS.size());
            configureFillPrinter(GHOST_BLOCK_TARGETS, false);
        });

        context.waitFor(client -> NetworkFaultController.droppedUseItemOnPackets()
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
            NetworkFaultController.reset();
            NetworkFaultController.startRandomLoss(0x4C4954454D415449L, 35);
            configureFillPrinter(RANDOM_LOSS_TARGETS, false);
        });
        context.waitTicks(80);
        NetworkFaultController.RandomLossSnapshot loss = context.computeOnClient(client -> {
            disablePrinter();
            return NetworkFaultController.stopRandomLoss();
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
        if (loss.droppedPackets() < 2
                || loss.droppedByClass().size() < 2
                || (wrongBlocks == 0 && ghostBlocks == 0 && mismatches == 0)) {
            throw new AssertionError("General packet loss did not reproduce a placement anomaly: "
                    + "loss=" + loss + ", wrongBlocks=" + wrongBlocks
                    + ", ghostBlocks=" + ghostBlocks + ", mismatches=" + mismatches);
        }
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
        Configs.Fill.ENABLED.setBooleanValue(false);
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
}
