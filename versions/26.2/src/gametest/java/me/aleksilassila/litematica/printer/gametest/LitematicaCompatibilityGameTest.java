package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Verifies the printer against an explicitly selected Litematica/MaLiLib pair. */
@SuppressWarnings("UnstableApiUsage")
public final class LitematicaCompatibilityGameTest implements FabricClientGameTest {
    private static final String LITEMATICA_PROPERTY =
            "litematica-printer.gametest.litematica";
    private static final String MALILIB_PROPERTY =
            "litematica-printer.gametest.malilib";
    private static final BlockPos TARGET = new BlockPos(2, 64, 0);
    private static final int MATERIAL_COUNT = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isAnyPerformance()) return;
        String expectedLitematica = System.getProperty(LITEMATICA_PROPERTY, "default");
        if (expectedLitematica.equals("default")) return;
        String expectedMalilib = System.getProperty(MALILIB_PROPERTY, "default");

        context.runOnClient(client -> {
            assertVersion("litematica", expectedLitematica);
            assertVersion("malilib", expectedMalilib);
        });

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 2.5 65 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && client.level != null
                    && client.level.getBlockState(TARGET).isAir()
                    && client.player.getInventory().countItem(Items.STONE)
                    == MATERIAL_COUNT, 200);

            context.runOnClient(client -> configureSchematicPrinter());
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(TARGET).is(Blocks.STONE), 400);
            context.waitTicks(5);

            CompatibilityResult result = singleplayer.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                return new CompatibilityResult(
                        server.overworld().getBlockState(TARGET).is(Blocks.STONE),
                        player.getInventory().countItem(Items.STONE));
            });
            if (!result.placed() || result.remainingStone() != MATERIAL_COUNT - 1) {
                throw new AssertionError("Litematica " + expectedLitematica
                        + " schematic print failed with MaLiLib " + expectedMalilib
                        + ": " + result);
            }
        } finally {
            context.runOnClient(client -> disableSchematicPrinter());
        }
    }

    private static void assertVersion(String modId, String expected) {
        String actual = FabricLoader.getInstance().getModContainer(modId)
                .orElseThrow(() -> new AssertionError(modId + " is not loaded"))
                .getMetadata().getVersion().getFriendlyString();
        if (!actual.equals(expected)) {
            throw new AssertionError("Expected " + modId + " " + expected
                    + " but Fabric loaded " + actual);
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int x = -2; x <= 6; x++) {
                for (int z = -6; z <= 3; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(TARGET, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(TARGET.above(), Blocks.AIR.defaultBlockState());
            player.getInventory().setItem(0,
                    new ItemStack(Items.STONE, MATERIAL_COUNT));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void configureSchematicPrinter() {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            throw new AssertionError("Litematica schematic world is unavailable");
        }
        schematic.setBlock(TARGET, Blocks.STONE.defaultBlockState(), 3);
        TestSchematicRegion.activate(TARGET, TARGET);

        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var selectionManager = DataManager.getSelectionManager();
        if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) {
            selectionManager.switchSelectionMode();
        }
        AreaSelection selection = DataManager.getSimpleArea();
        Box box = selection.getSubRegionBox(selection.getName());
        if (box == null) box = selection.getSelectedSubRegionBox();
        if (box == null) {
            throw new AssertionError("Litematica simple selection has no box");
        }
        box.setPos1(TARGET);
        box.setPos2(TARGET);

        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6.0D);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
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
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void disableSchematicPrinter() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        ModuleManager.PRINT.resetScanState();
        TestSchematicRegion.clear();
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic != null) {
            schematic.setBlock(TARGET, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private record CompatibilityResult(boolean placed, int remainingStone) {
    }
}
