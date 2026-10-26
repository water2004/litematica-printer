package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import java.util.HashMap;
import java.util.Map;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.dependency.DependencyCheck.Reason;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.handler.search.PlacementGuideTestAccess;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.placement.PlacementGuide;
import me.aleksilassila.litematica.printer.printer.PlacementSafety;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WallSide;

@SuppressWarnings("UnstableApiUsage")
public final class PlacementSafetyGameTest implements FabricClientGameTest {
    private static final BlockPos O = new BlockPos(0, 64, 0);
    private static final BlockPos P = O.west();
    private static final BlockPos CYCLE = O.south(2);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!System.getProperty("litematica-printer.gametest.bedrockMiner", "none").equals("none")) return;
        for (String mode : new String[] {"scanPerformance", "fullPrintPerformance",
                "quickshulkerStress", "networkFaults", "quickshulkerIntegrationOnly",
                "quickshulkerPacketLoss", "quickshulkerLegacyPacketLoss"}) {
            if (Boolean.getBoolean("litematica-printer.gametest." + mode)) return;
        }
        boolean oldSafety = context.computeOnClient(c -> Configs.Print.SAFELY_OBSERVER.getBooleanValue());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitFor(c -> c.player != null && c.level != null);
            context.runOnClient(this::checkRules);
            prepare(world);
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("tp @p 0.5 64 -3.5");
            context.waitTicks(5);
            world.getConnection().waitForChunksDownload();
            context.waitFor(c -> c.player != null && !c.player.getAbilities().instabuild
                    && c.player.getInventory().countItem(Items.OBSERVER) == 16
                    && c.level.getBlockState(O).isAir(), 200);
            context.runOnClient(c -> configure(c.player));
            // Cyclic jobs must still be discovered. Safety is a live consumer decision.
            context.waitFor(c -> ModuleManager.PRINT.getQueuedJobCount() == 6, 400);
            context.runOnClient(c -> c.player.containerMenu = c.player.inventoryMenu);
            context.waitFor(c -> c.level.getBlockState(O).is(Blocks.OBSERVER)
                    && c.level.getBlockState(P).is(Blocks.PISTON)
                    && c.level.getBlockState(O.east(2)).is(Blocks.STONE), 600);
            context.waitTicks(20);
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var player = server.getPlayerList().getPlayers().getFirst();
                require(level.getBlockState(O.east()).is(Blocks.STONE), "input was not built");
                require(level.getBlockState(O).getValue(ObserverBlock.FACING) == Direction.EAST,
                        "observer orientation changed");
                require(level.getBlockState(P).getValue(BlockStateProperties.FACING) == Direction.NORTH,
                        "piston orientation changed");
                require(!level.getBlockState(P).getValue(BlockStateProperties.EXTENDED),
                        "piston unexpectedly extended");
                require(level.getBlockState(CYCLE).isAir()
                        && level.getBlockState(CYCLE.east()).isAir(), "unsafe cycle was placed");
                require(player.getInventory().countItem(Items.STONE) == 14, "stone accounting");
                require(player.getInventory().countItem(Items.OBSERVER) == 15, "observer accounting");
                require(player.getInventory().countItem(Items.PISTON) == 15, "piston accounting");
            });
            context.runOnClient(c -> {
                require(ModuleManager.PRINT.getScanState()
                        != me.aleksilassila.litematica.printer.enums.ScanState.WAITING,
                        "safety rejection must not enter WAITING");
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                require(!ModuleManager.PRINT.canProcessPos(CYCLE), "live consumer must reject cycle");
                require(ModuleManager.PRINT.getSafetyRejection() != null
                        && ModuleManager.PRINT.getSafetyRejection().result().reason() == Reason.CYCLE,
                        "live consumer did not expose cycle reason");
                Configs.Print.SAFELY_OBSERVER.setBooleanValue(false);
                require(ModuleManager.PRINT.canProcessPos(CYCLE), "disabled safety still rejected cycle");
                require(ModuleManager.PRINT.getSafetyRejection() == null, "stale rejection reason");
                Configs.Print.SAFELY_OBSERVER.setBooleanValue(true);
            });
            System.out.println("[PlacementSafety] PASS dependency rules; live input/observer/piston order; "
                    + "cyclic jobs skipped while unrelated jobs complete; nonzero cooldown");
        } finally {
            context.runOnClient(c -> {
                Configs.Core.WORK_SWITCH.setBooleanValue(false);
                Configs.Print.ENABLED.setBooleanValue(false);
                Configs.Print.SAFELY_OBSERVER.setBooleanValue(oldSafety);
                if (c.player != null) c.player.containerMenu = c.player.inventoryMenu;
                ModuleManager.PRINT.resetScanState();
                ActionManager.INSTANCE.clearQueue();
                TestSchematicRegion.clear();
            });
        }
    }

    private void checkRules(Minecraft client) {
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.SAFELY_OBSERVER.setBooleanValue(true);
        Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
        var current = airCube();
        var required = airCube();
        expect(client, "air input", observer(Direction.EAST), current, required, Reason.READY);
        required.put(O.east(), STONE);
        expect(client, "unfinished input", observer(Direction.EAST), current, required, Reason.MISMATCH);
        current.put(O.east(), STONE);
        expect(client, "finished input", observer(Direction.EAST), current, required, Reason.READY);

        // A deferred direction/connection property is not the same as ignoring all state.
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        required.put(O.east(), fence.setValue(BlockStateProperties.WEST, true));
        current.put(O.east(), fence);
        expect(client, "self-derived fence connection", observer(Direction.EAST), current, required, Reason.READY);
        required.put(O.east(), fence.setValue(BlockStateProperties.WEST, true)
                .setValue(BlockStateProperties.NORTH, true));
        expect(client, "unrelated fence connection", observer(Direction.EAST), current, required, Reason.MISMATCH);
        BlockState wall = Blocks.COBBLESTONE_WALL.defaultBlockState();
        required.put(O.east(), wall.setValue(WallBlock.WEST, WallSide.TALL));
        current.put(O.east(), wall);
        expect(client, "self-derived wall connection", observer(Direction.EAST), current, required, Reason.READY);
        required.put(O.east(), wall.setValue(WallBlock.WEST, WallSide.LOW)
                .setValue(WallBlock.EAST, WallSide.LOW).setValue(WallBlock.UP, false));
        current.put(O.east(), wall.setValue(WallBlock.EAST, WallSide.LOW));
        expect(client, "self-derived wall post", observer(Direction.EAST), current, required, Reason.READY);
        required.put(O.east().above(), STONE);
        expect(client, "unfinished work above wall", observer(Direction.EAST), current, required, Reason.MISMATCH);
        required.put(O.east().above(), AIR);

        var verticalCurrent = airCube();
        var verticalRequired = airCube();
        verticalCurrent.put(O.below(), wall.setValue(WallBlock.EAST, WallSide.LOW)
                .setValue(WallBlock.WEST, WallSide.LOW).setValue(WallBlock.UP, false));
        verticalRequired.put(O.below(), wall.setValue(WallBlock.EAST, WallSide.TALL)
                .setValue(WallBlock.WEST, WallSide.TALL).setValue(WallBlock.UP, false));
        expect(client, "self-derived wall heights below", observer(Direction.DOWN),
                verticalCurrent, verticalRequired, Reason.READY);
        verticalRequired.put(O.below(), verticalRequired.get(O.below()).setValue(WallBlock.NORTH, WallSide.TALL));
        expect(client, "missing side below is not derived", observer(Direction.DOWN),
                verticalCurrent, verticalRequired, Reason.MISMATCH);

        // Missing face-to-face observers form a construction cycle, not a forever-waiting job.
        required.put(O.east(), observer(Direction.WEST));
        current.put(O.east(), AIR);
        expect(client, "missing observer cycle", observer(Direction.EAST), current, required, Reason.CYCLE);
        current.put(O.east(), observer(Direction.WEST));
        expect(client, "existing observer cycle", observer(Direction.EAST), current, required, Reason.CYCLE);

        current = airCube();
        required = airCube();
        for (int x = 1; x <= 24; x++) {
            current.put(O.east(x), observer(Direction.EAST));
            required.put(O.east(x), observer(Direction.EAST));
        }
        required.put(O.east(25), STONE);
        current.put(O.east(25), AIR);
        expect(client, "long chain missing terminal", observer(Direction.EAST), current, required, Reason.MISMATCH);
        current.put(O.east(25), STONE);
        expect(client, "long chain ready", observer(Direction.EAST), current, required, Reason.READY);

        current = airCube();
        required = airCube();
        required.put(O.east(), Blocks.SAND.defaultBlockState());
        current.put(O.east(), Blocks.SAND.defaultBlockState());
        required.put(O.east().below(), STONE);
        expect(client, "falling input support missing", observer(Direction.EAST), current, required, Reason.MISMATCH);
        current.put(O.east().below(), STONE);
        expect(client, "falling input support ready", observer(Direction.EAST), current, required, Reason.READY);

        BlockState piston = Blocks.PISTON.defaultBlockState();
        current = airCube();
        required = airCube();
        required.put(O.east(), observer(Direction.EAST));
        required.put(O.east(2), STONE);
        expect(client, "piston input terminal missing", piston, current, required, Reason.MISMATCH);
        current.put(O.east(2), STONE);
        expect(client, "piston observer missing", piston, current, required, Reason.MISMATCH);
        current.put(O.east(), observer(Direction.EAST));
        expect(client, "piston dependency ready", piston, current, required, Reason.READY);
        current.put(O.east(), observer(Direction.EAST).setValue(ObserverBlock.POWERED, true));
        expect(client, "active observer is not settled", piston, current, required, Reason.MISMATCH);

        // Old piston traversal loops here: neither observer faces EAST so it never checks mismatch.
        current = airCube();
        required = airCube();
        required.put(O.east(), observer(Direction.NORTH));
        required.put(O.east().north(), observer(Direction.SOUTH));
        expect(client, "unrelated sideways cycle", piston, current, required, Reason.READY);

        current = airCube();
        required = airCube();
        required.put(O.west(), STONE);
        current.put(O.west().north(), piston);
        expect(client, "unfinished output beside existing piston", observer(Direction.EAST),
                current, required, Reason.BLOCKED);

        var narrow = PlacementGuideTestAccess.snapshotContext(client, O, AIR,
                observer(Direction.EAST), Map.of(), Map.of());
        require(PlacementSafety.evaluate(narrow).reason() == Reason.UNKNOWN,
                "uncaptured dependency was treated as air");
        require(new PlacementGuide(client).getAction(narrow) != null,
                "producer must not reject candidates using incomplete safety data");
        System.out.println("[PlacementSafety] PASS 24 rule/boundary checks");
    }

    private static void expect(Minecraft client, String name, BlockState candidate,
            Map<BlockPos, BlockState> current, Map<BlockPos, BlockState> required, Reason expected) {
        var ctx = PlacementGuideTestAccess.snapshotContext(client, O, AIR, candidate, current, required);
        var result = PlacementSafety.evaluate(ctx);
        require(result.reason() == expected, name + ": expected " + expected + ", got " + result);
    }

    private static Map<BlockPos, BlockState> airCube() {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
            result.put(O.offset(x, y, z), AIR);
        return result;
    }

    private static BlockState observer(Direction direction) {
        return Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, direction);
    }

    private static void prepare(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            for (int x = -4; x <= 5; x++) for (int z = -6; z <= 4; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 63, z), STONE);
                for (int y = 64; y <= 67; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), AIR);
            }
            player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            player.getInventory().setItem(1, new ItemStack(Items.OBSERVER, 16));
            player.getInventory().setItem(2, new ItemStack(Items.PISTON, 16));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static void configure(Player player) {
        var schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) throw new AssertionError("No schematic world");
        schematic.setBlock(O, observer(Direction.EAST), 3);
        schematic.setBlock(O.east(), STONE, 3);
        schematic.setBlock(O.east(2), STONE, 3);
        schematic.setBlock(P, Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.NORTH), 3);
        schematic.setBlock(CYCLE, observer(Direction.EAST), 3);
        schematic.setBlock(CYCLE.east(), observer(Direction.WEST), 3);
        TestSchematicRegion.activate(P, O.east(2).south(2));
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(12);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(1);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(4);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.SpawnProofing.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        player.containerMenu = new PausedMenu(player.inventoryMenu.containerId + 1);
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class PausedMenu extends AbstractContainerMenu {
        private PausedMenu(int id) { super(null, id); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}

