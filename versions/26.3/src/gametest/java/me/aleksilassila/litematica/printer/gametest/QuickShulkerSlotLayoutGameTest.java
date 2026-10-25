package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;

import static me.aleksilassila.litematica.printer.gametest.PrinterIntegrationFixture.*;

/** Direct endpoints must retain physical slot indices before and after extracting a stack. */
@SuppressWarnings("UnstableApiUsage")
public final class QuickShulkerSlotLayoutGameTest implements FabricClientGameTest {
    private static final BlockPos FIRST = new BlockPos(-2, 64, -3);
    private static final BlockPos LAST = new BlockPos(5, 64, 5);
    private static final int BLOCK_COUNT = 72;
    private static final int BOX_SLOT = 9;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isAnyPerformance() || GameTestMode.isBedrockIntegration()
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        String mode = System.getProperty("litematica-printer.gametest.quickshulker", "none");
        if (!usesDirectProtocol(mode)) return;
        runScenario(context, mode, 0, 1);
        runScenario(context, mode, 13, 26);
    }

    private static void runScenario(ClientGameTestContext context, String mode,
                                    int firstSlot, int secondSlot) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepareWorld(world, true, BOX_SLOT);
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("tp @p 1.5 67 1.5");
            world.getServer().runOnServer(server -> {
                server.setDifficulty(Difficulty.PEACEFUL, true);
                var player = server.getPlayerList().getPlayers().getFirst();
                // Keep every target reachable, without creative mode bypassing material retrieval.
                player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(10);
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                for (BlockPos pos : BlockPos.betweenClosed(FIRST, LAST)) {
                    server.overworld().setBlockAndUpdate(pos.below(), Blocks.COBBLESTONE.defaultBlockState());
                    for (int y = 0; y <= 5; y++) {
                        server.overworld().setBlockAndUpdate(pos.above(y), Blocks.AIR.defaultBlockState());
                    }
                }
                var slots = NonNullList.withSize(27, ItemStack.EMPTY);
                slots.set(firstSlot, new ItemStack(Items.STONE, 64));
                slots.set(secondSlot, new ItemStack(Items.STONE, 64));
                ItemStack box = new ItemStack(Items.SHULKER_BOX);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
                player.getInventory().setItem(BOX_SLOT, box);
                player.inventoryMenu.sendAllDataToRemote();
            });
            world.getConnection().waitForChunksDownload();
            context.waitFor(client -> client.player != null && !client.player.getAbilities().instabuild
                    && countStoredStone(client.player.getInventory().getItem(BOX_SLOT)) == 128);
            context.runOnClient(client -> {
                assertQuickStorageCapability(mode);
                configurePrinter();
            });

            Result result = null;
            int tick;
            for (tick = 0; tick < 300; tick++) {
                result = readResult(world);
                if (result.isComplete()) break;
                context.waitTick();
            }
            if (result == null || !result.isComplete()) {
                throw new AssertionError("Shulker slots " + firstSlot + "/" + secondSlot
                        + " stalled after " + tick + " ticks: " + result);
            }
            context.waitFor(client -> !QuickShulkerCompat.isBusy(), 40);
            System.out.println("[QuickShulkerSlotLayout] PASS slots=" + firstSlot + "/" + secondSlot
                    + ", ticks=" + tick + ", " + result);
        } finally {
            context.runOnClient(client -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.ENABLED.setBooleanValue(false);
                ModuleManager.PRINT.resetScanState();
                TestSchematicRegion.clear();
            });
        }
    }

    private static void configurePrinter() {
        configureQuickShulker();
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        for (BlockPos pos : BlockPos.betweenClosed(FIRST, LAST)) {
            SchematicWorldHandler.getSchematicWorld().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        }
        TestSchematicRegion.activate(FIRST, LAST);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(10);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(1);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(10);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static Result readResult(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            int placed = 0;
            for (BlockPos pos : BlockPos.betweenClosed(FIRST, LAST)) {
                if (server.overworld().getBlockState(pos).is(Blocks.STONE)) placed++;
            }
            var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
            var box = inventory.getItem(BOX_SLOT);
            return new Result(placed, countLooseStone(inventory), countStoredStone(box), box.is(Items.SHULKER_BOX));
        });
    }

    private record Result(int placed, int loose, int boxed, boolean boxUnmoved) {
        boolean isComplete() {
            return placed == BLOCK_COUNT && loose == 128 - BLOCK_COUNT && boxed == 0 && boxUnmoved;
        }
    }
}
