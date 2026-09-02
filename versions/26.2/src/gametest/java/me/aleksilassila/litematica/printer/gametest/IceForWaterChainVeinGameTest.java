package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.status.PrinterWaitReason;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.ChainVeinCompat;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicBoolean;

/** End-to-end coverage for Print's place-ice, ChainVein-break, wait-for-water transaction. */
@SuppressWarnings("UnstableApiUsage")
public final class IceForWaterChainVeinGameTest implements FabricClientGameTest {
    private static final String CHAINVEIN_PROPERTY =
            "litematica-printer.gametest.chainvein";
    private static final BlockPos TARGET = new BlockPos(2, 64, 0);
    private static final int ICE_COUNT = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (shouldSkip()) return;
        boolean expectChainVein = Boolean.parseBoolean(
                System.getProperty(CHAINVEIN_PROPERTY, "false"));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 2.5 64 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && client.level != null
                    && client.level.getBlockState(TARGET).isAir()
                    && client.level.getBlockState(TARGET.below()).is(Blocks.WATER)
                    && client.level.getBlockState(TARGET.west()).is(Blocks.COBBLESTONE)
                    && client.player.getInventory().countItem(Items.ICE) == ICE_COUNT,
                    200);

            context.runOnClient(client -> {
                if (ChainVeinCompat.isAvailable() != expectChainVein) {
                    throw new AssertionError("ChainVein availability does not match GameTest mode");
                }
                configurePrinter();
            });

            // Phase one must be a normal printer placement in both optional-mod modes.
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(TARGET).is(Blocks.ICE), 400);

            if (!expectChainVein) {
                assertNoDestructiveFallback(context, singleplayer);
                return;
            }

            AtomicBoolean observedWorldWait = new AtomicBoolean();
            context.waitFor(client -> {
                if (ModuleManager.PRINT.getScanState() == ScanState.WAITING
                        && ModuleManager.PRINT.getWaitingReason()
                        == PrinterWaitReason.WORLD_UPDATE) {
                    observedWorldWait.set(true);
                }
                return client.level != null
                        && client.level.getBlockState(TARGET).is(Blocks.WATER);
            }, 600);
            context.waitFor(client -> ModuleManager.PRINT.getScanState() != ScanState.WAITING
                    && ModuleManager.PRINT.getQueuedJobCount() == 0, 200);
            context.waitTicks(5);

            Result result = readResult(singleplayer);
            if (!observedWorldWait.get()
                    || !result.waterSource()
                    || result.remainingIce() != ICE_COUNT - 1) {
                throw new AssertionError("Ice-for-water ChainVein transaction failed: "
                        + result + ", observedWorldWait=" + observedWorldWait.get());
            }
        } finally {
            context.runOnClient(client -> cleanup());
        }
    }

    private static boolean shouldSkip() {
        return GameTestMode.isScanPerformance()
                || GameTestMode.isBedrockIntegration()
                || !System.getProperty(
                        "litematica-printer.gametest.quickshulker", "none").equals("none")
                || !System.getProperty(
                        "litematica-printer.gametest.litematica", "default").equals("default")
                || Boolean.getBoolean("litematica-printer.gametest.servux")
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
            for (int x = -2; x <= 6; x++) {
                for (int z = -6; z <= 3; z++) {
                    level.setBlockAndUpdate(
                            new BlockPos(x, 62, z), Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            // Print permits an existing liquid below the target, and vanilla IceBlock
            // requires either liquid or a motion-blocking block below to create water
            // when mined without Silk Touch. The side support gives normal placement
            // a solid face without changing the block below the ice.
            level.setBlockAndUpdate(TARGET.below(), Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(TARGET.west(), Blocks.COBBLESTONE.defaultBlockState());
            level.setBlockAndUpdate(TARGET, Blocks.AIR.defaultBlockState());
            player.getInventory().setItem(0, new ItemStack(Items.ICE, ICE_COUNT));
            player.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void configurePrinter() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            throw new AssertionError("Litematica schematic world is unavailable");
        }
        schematic.setBlock(TARGET, Blocks.WATER.defaultBlockState(), 3);
        TestSchematicRegion.activate(TARGET, TARGET);

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.BREAK_COOLDOWN.setIntegerValue(0);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(
                SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void assertNoDestructiveFallback(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        context.waitTicks(30);
        Result result = readResult(singleplayer);
        boolean consumerIdle = context.computeOnClient(client ->
                ModuleManager.PRINT.getScanState() != ScanState.WAITING
                        && ModuleManager.PRINT.getQueuedJobCount() == 0);
        if (!result.iceStillPresent()
                || result.waterSource()
                || result.remainingIce() != ICE_COUNT - 1
                || !consumerIdle) {
            throw new AssertionError(
                    "Printer used a destructive fallback or retained an unserviceable job "
                            + "without ChainVein: " + result
                            + ", consumerIdle=" + consumerIdle);
        }
    }

    private static Result readResult(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var state = level.getBlockState(TARGET);
            return new Result(
                    state.is(Blocks.ICE),
                    state.is(Blocks.WATER) && level.getFluidState(TARGET).isSource(),
                    server.getPlayerList().getPlayers().getFirst()
                            .getInventory().countItem(Items.ICE));
        });
    }

    private static void cleanup() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
        ActionManager.INSTANCE.clearQueue();
        ModuleManager.PRINT.resetScanState();
        TestSchematicRegion.clear();
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic != null) {
            schematic.setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private record Result(boolean iceStillPresent, boolean waterSource, int remainingIce) {
    }
}
