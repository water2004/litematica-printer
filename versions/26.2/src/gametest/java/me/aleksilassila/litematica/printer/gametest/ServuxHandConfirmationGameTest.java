package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.utils.ServuxHandItemConfirmation;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Verifies that an unconfirmed server hand can never produce a wrong block. */
@SuppressWarnings("UnstableApiUsage")
public final class ServuxHandConfirmationGameTest implements FabricClientGameTest {
    private static final List<BlockPos> TARGETS = java.util.stream.IntStream
            .rangeClosed(2, 5)
            .mapToObj(x -> new BlockPos(x, 64, 0))
            .toList();
    private static final int MATERIAL_COUNT = 16;
    private static final int CARRIED_PACKET_DROPS = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("litematica-printer.gametest.servux")) return;

        NetworkFaultController.reset();
        TestServuxProtocol.resetCounters();
        TestServuxProtocol.register();
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
             var connection = server.connect()) {
            server.runCommand("gamemode survival @p");
            prepareWorldAndInventory(context, server);
            server.runCommand("tp @p 3.5 65 -2.5");
            connection.getClientLevel().waitForChunksDownload();

            context.runOnClient(client -> {
                Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(true);
                ServuxHandItemConfirmation.reset();
            });
            context.waitFor(client -> EntityDataManager.getInstance().hasServuxServer(), 200);

            context.runOnClient(client -> {
                NetworkFaultController.armCarriedItemBurst(CARRIED_PACKET_DROPS);
                configurePrinter();
            });

            context.waitFor(client ->
                    NetworkFaultController.droppedCarriedItemPackets()
                            == CARRIED_PACKET_DROPS, 200);
            context.waitTicks(80);
            context.runOnClient(client -> disablePrinter());

            WorldState state = server.computeOnServer(minecraftServer -> {
                var player = minecraftServer.getPlayerList().getPlayers().getFirst();
                return new WorldState(
                        countBlocks(minecraftServer.overworld(), Blocks.STONE),
                        countBlocks(minecraftServer.overworld(), Blocks.DIRT),
                        countItem(player.getInventory().getNonEquipmentItems(), Items.STONE),
                        countItem(player.getInventory().getNonEquipmentItems(), Items.DIRT));
            });
            TestServuxProtocol.Snapshot protocol = TestServuxProtocol.snapshot();

            System.out.println("[Litematica Printer GameTest] Servux hand confirmation: "
                    + "droppedCarriedItemPackets="
                    + NetworkFaultController.droppedCarriedItemPackets()
                    + ", protocol=" + protocol + ", server=" + state);

            if (NetworkFaultController.droppedCarriedItemPackets()
                    != CARRIED_PACKET_DROPS) {
                throw new AssertionError("Expected exactly " + CARRIED_PACKET_DROPS
                        + " dropped carried-item packets, got "
                        + NetworkFaultController.droppedCarriedItemPackets());
            }
            if (protocol.metadataRequests() < 1
                    || protocol.entityRequests() < 2
                    || protocol.dirtHandResponses() < 1
                    || protocol.stoneHandResponses() < 1) {
                throw new AssertionError("The test did not exercise both the mismatched and "
                        + "confirmed authoritative hand states: " + protocol);
            }
            if (state.dirtBlocks() != 0) {
                throw new AssertionError("Wrong blocks reached the server despite confirmation: "
                        + state);
            }
            if (state.stoneBlocks() != TARGETS.size()) {
                throw new AssertionError("Printer did not recover after the finite packet loss: "
                        + state);
            }
            if (state.stoneItems() != MATERIAL_COUNT - TARGETS.size()
                    || state.dirtItems() != MATERIAL_COUNT) {
                throw new AssertionError("Server inventory does not match four correct stone "
                        + "placements and zero dirt placements: " + state);
            }
        } finally {
            context.runOnClient(client -> {
                disablePrinter();
                Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
                ServuxHandItemConfirmation.reset();
            });
            NetworkFaultController.reset();
            TestServuxProtocol.resetCounters();
        }
    }

    private static void prepareWorldAndInventory(
            ClientGameTestContext context,
            TestDedicatedServerContext server) {
        int pickSlot = context.computeOnClient(client ->
                InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(
                        client.player.getInventory()));
        if (pickSlot < 0 || pickSlot > 8) {
            throw new AssertionError("No empty Litematica pick-block hotbar slot is available");
        }
        int wrongHandSlot = (pickSlot + 1) % 9;

        server.runOnServer(minecraftServer -> {
            var level = minecraftServer.overworld();
            var player = minecraftServer.getPlayerList().getPlayers().getFirst();
            for (int x = 0; x <= 7; x++) {
                for (int z = -5; z <= 2; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            TARGETS.forEach(pos ->
                    level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));

            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(wrongHandSlot);
            player.getInventory().setItem(
                    wrongHandSlot, new ItemStack(Items.DIRT, MATERIAL_COUNT));
            player.getInventory().setItem(
                    9, new ItemStack(Items.STONE, MATERIAL_COUNT));
            player.inventoryMenu.sendAllDataToRemote();
        });
        context.runOnClient(client ->
                client.player.getInventory().setSelectedSlot(wrongHandSlot));
        context.waitFor(client -> client.player != null
                && client.level != null
                && client.player.getInventory().getSelectedSlot() == wrongHandSlot
                && client.player.getMainHandItem().is(Items.DIRT)
                && countItem(client.player.getInventory().getNonEquipmentItems(), Items.STONE)
                == MATERIAL_COUNT, 200);
    }

    private static void configurePrinter() {
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

        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(8.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(true);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(TARGETS.size());
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.REPLACEABLE_LIST.setStrings(List.of());
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
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
        ModuleManager.FILL.resetScanState();
    }

    private static int countBlocks(Level level, Block block) {
        return (int) TARGETS.stream()
                .filter(pos -> level.getBlockState(pos).is(block))
                .count();
    }

    private static int countItem(List<ItemStack> stacks, Item item) {
        return stacks.stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private record WorldState(
            int stoneBlocks,
            int dirtBlocks,
            int stoneItems,
            int dirtItems) {
    }
}
