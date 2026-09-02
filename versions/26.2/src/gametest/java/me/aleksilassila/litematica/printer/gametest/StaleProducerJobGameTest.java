package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Locks the producer snapshot/consumer live-state validation boundary. */
@SuppressWarnings("UnstableApiUsage")
public final class StaleProducerJobGameTest implements FabricClientGameTest {
    private static final BlockPos COMPLETED_EXTERNALLY = new BlockPos(0, 64, 0);
    private static final BlockPos RECLASSIFIED = new BlockPos(2, 64, 0);
    private static final int MATERIAL_COUNT = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (shouldSkip()) return;

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 1.5 64 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && client.level != null
                    && client.level.getBlockState(COMPLETED_EXTERNALLY).isAir()
                    && client.level.getBlockState(RECLASSIFIED).isAir()
                    && client.player.getInventory().countItem(Items.STONE) == MATERIAL_COUNT
                    && client.player.getInventory().countItem(Items.DIRT) == MATERIAL_COUNT,
                    200);

            context.runOnClient(client -> configureAndPauseConsumer(client.player));
            context.waitFor(client -> ModuleManager.PRINT.getQueuedJobCount() == 2, 400);

            // The first queued job becomes complete. The second changes from a stone
            // placement into a dirt placement while its old stone job remains queued.
            singleplayer.getServer().runOnServer(server ->
                    server.overworld().setBlockAndUpdate(
                            COMPLETED_EXTERNALLY, Blocks.STONE.defaultBlockState()));
            context.runOnClient(client -> {
                var schematic = SchematicWorldHandler.getSchematicWorld();
                if (schematic == null) {
                    throw new AssertionError("Schematic world disappeared during stale-job test");
                }
                schematic.setBlock(RECLASSIFIED, Blocks.DIRT.defaultBlockState(), 3);
            });
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(COMPLETED_EXTERNALLY).is(Blocks.STONE),
                    200);

            context.runOnClient(client -> resumeConsumer(client.player));

            // Both old jobs must be discarded without action. A later producer round
            // must rediscover the dirt job, which is then allowed to execute.
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(RECLASSIFIED).is(Blocks.DIRT), 600);
            context.waitTicks(10);

            Result result = singleplayer.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                return new Result(
                        server.overworld().getBlockState(COMPLETED_EXTERNALLY).is(Blocks.STONE),
                        server.overworld().getBlockState(RECLASSIFIED).is(Blocks.DIRT),
                        player.getInventory().countItem(Items.STONE),
                        player.getInventory().countItem(Items.DIRT));
            });
            int queued = context.computeOnClient(client ->
                    ModuleManager.PRINT.getQueuedJobCount());

            if (!result.completedExternallyStillStone()
                    || !result.reclassifiedIsDirt()
                    || result.remainingStone() != MATERIAL_COUNT
                    || result.remainingDirt() != MATERIAL_COUNT - 1
                    || queued != 0) {
                throw new AssertionError(
                        "Consumer executed a stale producer job or failed to rediscover it: "
                                + result + ", queued=" + queued);
            }
        } finally {
            context.runOnClient(client -> cleanup(client.player));
        }
    }

    private static boolean shouldSkip() {
        return GameTestMode.isAnyPerformance()
                || GameTestMode.isBedrockIntegration()
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")
                || Boolean.getBoolean("litematica-printer.gametest.networkFaults")
                || Boolean.getBoolean(
                        "litematica-printer.gametest.quickshulkerIntegrationOnly")
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerPacketLoss")
                || Boolean.getBoolean(
                        "litematica-printer.gametest.quickshulkerLegacyPacketLoss");
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int x = -3; x <= 5; x++) {
                for (int z = -6; z <= 3; z++) {
                    level.setBlockAndUpdate(
                            new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(COMPLETED_EXTERNALLY, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(RECLASSIFIED, Blocks.AIR.defaultBlockState());
            player.getInventory().setItem(0, new ItemStack(Items.STONE, MATERIAL_COUNT));
            player.getInventory().setItem(1, new ItemStack(Items.DIRT, MATERIAL_COUNT));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void configureAndPauseConsumer(Player player) {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            throw new AssertionError("Litematica schematic world is unavailable");
        }
        schematic.setBlock(COMPLETED_EXTERNALLY, Blocks.STONE.defaultBlockState(), 3);
        schematic.setBlock(RECLASSIFIED, Blocks.STONE.defaultBlockState(), 3);
        TestSchematicRegion.activate(COMPLETED_EXTERNALLY, RECLASSIFIED);

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(2);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();

        // ModuleManager starts producer rounds before this container gate.
        player.containerMenu = new ConsumerPauseMenu(player.inventoryMenu.containerId + 1);
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void resumeConsumer(Player player) {
        player.containerMenu = player.inventoryMenu;
    }

    private static void cleanup(Player player) {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        if (player != null) player.containerMenu = player.inventoryMenu;
        ActionManager.INSTANCE.clearQueue();
        ModuleManager.PRINT.resetScanState();
        TestSchematicRegion.clear();
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic != null) {
            schematic.setBlock(COMPLETED_EXTERNALLY, Blocks.AIR.defaultBlockState(), 3);
            schematic.setBlock(RECLASSIFIED, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static final class ConsumerPauseMenu extends AbstractContainerMenu {
        private ConsumerPauseMenu(int containerId) {
            super(null, containerId);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private record Result(boolean completedExternallyStillStone,
                          boolean reclassifiedIsDirt,
                          int remainingStone,
                          int remainingDirt) {
    }
}
