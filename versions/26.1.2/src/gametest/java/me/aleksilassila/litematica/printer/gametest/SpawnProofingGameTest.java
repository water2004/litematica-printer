package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.AreaSelectionType;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.List;

/** Real area-selection placement, including stale snapshots and the selection's top boundary. */
@SuppressWarnings("UnstableApiUsage")
public final class SpawnProofingGameTest implements FabricClientGameTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos LAST = new BlockPos(4, 64, 3);
    private static final BlockPos REMOVED_SUPPORT = new BlockPos(4, 64, 1);
    private static final BlockPos OCCUPIED_LATER = new BlockPos(4, 64, 2);
    private static final BlockPos OUTSIDE = new BlockPos(5, 64, 0);
    private static final BlockPos ALREADY_COVERED = new BlockPos(0, 64, 2);
    private static final BlockPos WATER = new BlockPos(2, 64, 2);
    private static final List<BlockPos> ELIGIBLE = List.of(
            new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
            new BlockPos(3, 64, 0), new BlockPos(4, 64, 0),
            new BlockPos(0, 64, 1), new BlockPos(0, 64, 3));

    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("litematica-printer.gametest.scanPerformance")
                || Boolean.getBoolean("litematica-printer.gametest.fullPrintPerformance")
                || !System.getProperty("litematica-printer.gametest.bedrockMiner", "none").equals("none")
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")
                || Boolean.getBoolean("litematica-printer.gametest.networkFaults")
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerIntegrationOnly")
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerPacketLoss")
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerLegacyPacketLoss")) return;
        runScenario(context, Blocks.STONE_SLAB, false);
        runScenario(context, Blocks.RAIL, false);
        runScenario(context, Blocks.STONE_SLAB, true);
    }

    private static void runScenario(ClientGameTestContext context, Block cover, boolean handheld) {
        int originalThreads = context.computeOnClient(client -> Configs.Core.SEARCH_THREADS.getIntegerValue());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepare(world, cover, handheld);
            world.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null && !client.player.getAbilities().instabuild
                    && client.player.getInventory().countItem(cover.asItem()) == 32
                    && client.level.getBlockState(REMOVED_SUPPORT).is(Blocks.STONE), 200);
            context.runOnClient(client -> {
                configure(cover, handheld);
                // Freeze consumption only. The normal snapshot producer must still discover jobs.
                client.player.containerMenu = new PausedMenu(client.player.inventoryMenu.containerId + 1);
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
            });
            context.waitFor(client -> ModuleManager.SPAWN_PROOFING.getQueuedJobCount() == 8, 400);
            world.getServer().runOnServer(server -> {
                server.overworld().setBlockAndUpdate(REMOVED_SUPPORT, Blocks.AIR.defaultBlockState());
                server.overworld().setBlockAndUpdate(OCCUPIED_LATER.above(), Blocks.STONE.defaultBlockState());
            });
            context.waitFor(client -> client.level.getBlockState(REMOVED_SUPPORT).isAir()
                    && client.level.getBlockState(OCCUPIED_LATER.above()).is(Blocks.STONE), 200);
            context.runOnClient(client -> client.player.containerMenu = client.player.inventoryMenu);
            context.waitFor(client -> ELIGIBLE.stream()
                    .allMatch(pos -> client.level.getBlockState(pos.above()).is(cover)), 400);
            context.waitTicks(30);

            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (BlockPos support : ELIGIBLE) {
                    var placed = level.getBlockState(support.above());
                    if (!placed.is(cover) || (cover instanceof SlabBlock
                            && placed.getValue(SlabBlock.TYPE) != SlabType.BOTTOM)) {
                        throw new AssertionError("Incorrect covering at " + support + ": " + placed);
                    }
                }
                if (!level.getBlockState(REMOVED_SUPPORT.above()).isAir()
                        || !level.getBlockState(OCCUPIED_LATER.above()).is(Blocks.STONE)
                        || !level.getBlockState(OUTSIDE.above()).isAir()
                        || !level.getBlockState(new BlockPos(2, 65, 0)).isAir()
                        || !level.getBlockState(new BlockPos(3, 65, 1)).isAir()
                        || !level.getBlockState(new BlockPos(1, 65, 3)).isAir()
                        || !level.getBlockState(WATER.above()).is(Blocks.WATER)
                        || !level.getBlockState(ALREADY_COVERED.above()).is(cover)) {
                    throw new AssertionError("Covered an invalid/stale/outside surface or replaced existing contents");
                }
                if (server.getPlayerList().getPlayers().getFirst().getInventory()
                        .countItem(cover.asItem()) != 32 - ELIGIBLE.size()) {
                    throw new AssertionError("Surface covering consumed extra materials");
                }
            });
            context.waitFor(client -> ModuleManager.GUI.getTotalProgress().getTotal() == 7
                    && ModuleManager.GUI.getTotalProgress().getFinished() == 7, 200);
            context.runOnClient(client -> {
                if (client.player.containerMenu != client.player.inventoryMenu) {
                    throw new AssertionError("Covering a barrel opened its inventory");
                }
                if (ModuleManager.SPAWN_PROOFING.getQueuedJobCount() != 0) {
                    throw new AssertionError("Completed or stale jobs remained queued");
                }
            });
            System.out.println("[SpawnProofing] PASS cover=" + cover + ", handheld=" + handheld
                    + ", placed=6, staleJobsDiscarded=2, remaining=26, progress=7/7");
        } finally {
            context.runOnClient(client -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.SpawnProofing.ENABLED.setBooleanValue(false);
                Configs.Core.RENDER_HUD.setBooleanValue(false);
                Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
                Configs.Core.SEARCH_THREADS.setIntegerValue(originalThreads);
                if (client.player != null) client.player.containerMenu = client.player.inventoryMenu;
                ModuleManager.SPAWN_PROOFING.resetScanState();
                ActionManager.INSTANCE.clearQueue();
            });
        }
    }

    private static void prepare(TestSingleplayerContext world, Block cover, boolean handheld) {
        world.getServer().runCommand("gamemode survival @p");
        world.getServer().runCommand("tp @p 2.5 67 -3.5");
        world.getServer().runOnServer(server -> {
            server.setDifficulty(Difficulty.PEACEFUL, true);
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(12);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.getInventory().clearContent();
            player.getInventory().setItem(handheld ? 0 : 9, new ItemStack(cover.asItem(), 32));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
            var level = server.overworld();
            for (int x = -2; x <= 7; x++) for (int z = -6; z <= 5; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState());
                for (int y = 64; y <= 68; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
            for (BlockPos pos : ELIGIBLE) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(1, 64, 0),
                    Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            level.setBlockAndUpdate(new BlockPos(2, 64, 0), Blocks.STONE_SLAB.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(3, 64, 0), Blocks.GLASS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(4, 64, 0),
                    Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP));
            level.setBlockAndUpdate(new BlockPos(0, 64, 1), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(0, 65, 3), Blocks.SHORT_GRASS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(3, 64, 1), Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(1, 64, 1), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(1, 65, 1), Blocks.STONE.defaultBlockState());
            for (BlockPos stale : List.of(REMOVED_SUPPORT, OCCUPIED_LATER, OUTSIDE, ALREADY_COVERED, WATER))
                level.setBlockAndUpdate(stale, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(ALREADY_COVERED.above(), cover.defaultBlockState());
            for (BlockPos wall : List.of(WATER.above().north(), WATER.above().south(),
                    WATER.above().east(), WATER.above().west()))
                level.setBlockAndUpdate(wall, Blocks.BARRIER.defaultBlockState());
            level.setBlockAndUpdate(WATER.above(), Blocks.WATER.defaultBlockState());
        });
    }

    private static void configure(Block cover, boolean handheld) {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        var selections = DataManager.getSelectionManager();
        if (selections.getSelectionMode() != SelectionMode.SIMPLE) selections.switchSelectionMode();
        var area = DataManager.getSimpleArea();
        var box = area.getSubRegionBox(area.getName());
        if (box == null) box = area.getSelectedSubRegionBox();
        if (box == null) throw new AssertionError("No simple selection");
        box.setPos1(FIRST);
        box.setPos2(LAST);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(12);
        Configs.Core.SEARCH_THREADS.setIntegerValue(3);
        Configs.Core.RENDER_HUD.setBooleanValue(true);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(4);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(1);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(cover == Blocks.RAIL);
        // The new mode must still click the real support when this unrelated setting is on.
        Configs.Print.PLACE_IN_AIR.setBooleanValue(true);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.SpawnProofing.SELECTION_TYPE.setOptionListValue(AreaSelectionType.ALL);
        Configs.SpawnProofing.BLOCK_MODE.setOptionListValue(
                handheld ? FillBlockModeType.HANDHELD : FillBlockModeType.BLOCKLIST);
        Configs.SpawnProofing.BLOCK_LIST.setStrings(List.of(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(cover).toString()));
        Configs.SpawnProofing.HANDHELD_BLACKLIST.setStrings(List.of());
        Configs.SpawnProofing.ENABLED.setBooleanValue(true);
        ModuleManager.SPAWN_PROOFING.resetScanState();
    }

    private static final class PausedMenu extends AbstractContainerMenu {
        private PausedMenu(int id) { super(null, id); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}

