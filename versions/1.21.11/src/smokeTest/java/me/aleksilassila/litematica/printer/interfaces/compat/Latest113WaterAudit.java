package me.aleksilassila.litematica.printer.interfaces.compat;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.status.PrinterWaitReason;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.handler.IteratorManager;
import me.aleksilassila.litematica.printer.core.spatial.PlayerHeightFilter;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native-client adaptation of upstream IceForWaterChainVeinGameTest assertions. */
final class Latest113WaterAudit {
    private static final BlockPos TARGET = new BlockPos(2, 64, 0);
    private static final List<Block> FLOORS = List.of(Blocks.GRASS_BLOCK, Blocks.WATER,
            Blocks.AIR, Blocks.SOUL_SAND, Blocks.MAGMA_BLOCK);
    private final Minecraft client;
    private final Consumer<String> mark;
    private final boolean chain;
    private int phase, scenario, ticks, cycles;
    private CompletableFuture<Void> work;
    private CompletableFuture<Boolean> snapshot;

    Latest113WaterAudit(Minecraft client, Consumer<String> mark) {
        this.client = client;
        this.mark = mark;
        this.chain = ChainVeinCompat.isAvailable();
    }

    boolean tick() {
        ticks++;
        switch (phase) {
            case 0 -> {
                stop();
                work = server(() -> {
                    cell(Blocks.GRASS_BLOCK);
                    client.getSingleplayerServer().overworld().setBlockAndUpdate(TARGET, Blocks.WATER.defaultBlockState());
                });
                next(1);
            }
            case 1 -> {
                if (!done() || !client.level.getBlockState(TARGET).is(Blocks.WATER)) return false;
                selectTarget();
                var area = DataManager.getSimpleArea();
                var schematic = LitematicaSchematic.createFromWorld(client.level, area,
                        new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                        "Water113Audit", message -> { throw new AssertionError(message); });
                check(schematic != null, "Cannot capture water schematic");
                DataManager.getSchematicPlacementManager().addSchematicPlacement(
                        SchematicPlacement.createFor(schematic, area.getEffectiveOrigin(), "Water 1.1.3 audit", true, true), false);
                next(2);
            }
            case 2 -> {
                var schematic = SchematicWorldHandler.getSchematicWorld();
                if (schematic == null || !schematic.getBlockState(TARGET).is(Blocks.WATER)) return false;
                stop();
                cycles = 0;
                work = server(() -> {
                    cell(FLOORS.get(scenario));
                    var player = client.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.teleportTo(2.5, 64, -3.5);
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, new ItemStack(Items.ICE, 4));
                    player.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
                    player.getInventory().setSelectedSlot(0);
                    player.inventoryMenu.sendAllDataToRemote();
                });
                next(3);
            }
            case 3 -> {
                if (!done() || countIce(client.player.getInventory()) != 4
                        || !client.level.getBlockState(TARGET.below()).is(FLOORS.get(scenario))) return false;
                if (scenario >= 3 ? !client.level.getBlockState(TARGET).is(Blocks.BUBBLE_COLUMN)
                        : !client.level.getBlockState(TARGET).isAir()) return false;
                verifyAreaSelectionOptions(client.player);
                configure();
                classifyWater();
                next(4);
            }
            case 4 -> {
                noWorldWait();
                if (scenario == 2) {
                    check(client.level.getBlockState(TARGET).isAir() && countIce(client.player.getInventory()) == 4,
                            "Unsupported ice was placed/consumed");
                    if (ticks < 60 || ModuleManager.PRINT.getProducerScannedPositions() == 0) return false;
                    work = assertServer(false, 4, Blocks.AIR);
                    next(9);
                } else if (scenario >= 3) {
                    check(client.level.getBlockState(TARGET).is(Blocks.BUBBLE_COLUMN)
                                    && countIce(client.player.getInventory()) == 4,
                            "Existing source bubble column was replaced");
                    if (ticks < 60) return false;
                    work = assertServer(true, 4, Blocks.BUBBLE_COLUMN);
                    next(7);
                } else {
                    if (!client.level.getBlockState(TARGET).is(Blocks.ICE)) return false;
                    Configs.Core.WORK_SWITCH.setBooleanValue(false);
                    next(5);
                }
            }
            case 5 -> {
                if (ticks < 3) return false;
                Configs.Core.WORK_SWITCH.setBooleanValue(true);
                next(6);
            }
            case 6 -> {
                noWorldWait();
                int expected = 3 - cycles;
                if (!chain) {
                    if (ticks < 30) return false;
                    check(ModuleManager.PRINT.getQueuedJobCount() == 0, "Unserviceable break job retained without ChainVein");
                    work = assertServer(false, expected, Blocks.ICE);
                    next(9);
                    return false;
                }
                if (!BlockUtils.matchesWaterSourceRequest(Blocks.WATER.defaultBlockState(), client.level.getBlockState(TARGET))
                        || countIce(client.player.getInventory()) != expected
                        || ModuleManager.PRINT.getQueuedJobCount() != 0 || ModuleManager.PRINT.isOnCooldown(TARGET)) return false;
                if (snapshot == null) {
                    snapshot = serverCheck(expected);
                    return false;
                }
                if (!snapshot.isDone()) return false;
                boolean verified = snapshot.join(); snapshot = null;
                if (!verified) return false;
                if (scenario < 2 && cycles++ == 0) {
                    work = server(() -> client.getSingleplayerServer().overworld().setBlockAndUpdate(TARGET, Blocks.AIR.defaultBlockState()));
                    next(8);
                } else if (scenario >= 3) next(10);
                else next(9);
            }
            case 7 -> {
                if (!done()) return false;
                if (!chain) { next(9); return false; }
                work = server(() -> client.getSingleplayerServer().overworld().setBlockAndUpdate(TARGET, Blocks.AIR.defaultBlockState()));
                next(8);
            }
            case 8 -> { if (done()) next(6); }
            case 9 -> {
                if (!done()) return false;
                stop();
                mark.accept("ice-water-floor-" + FLOORS.get(scenario) + "-" + (chain ? "chain" : "no-chain"));
                if (++scenario == FLOORS.size()) return true;
                next(2);
            }
            case 10 -> {
                noWorldWait();
                if (!client.level.getBlockState(TARGET).is(Blocks.BUBBLE_COLUMN)) return false;
                check(countIce(client.player.getInventory()) == 3, "Recreated bubble column consumed extra ice");
                if (ticks < 60) return false;
                work = assertServer(true, 3, Blocks.BUBBLE_COLUMN);
                next(9);
            }
        }
        return false;
    }

    private static void verifyAreaSelectionOptions(net.minecraft.client.player.LocalPlayer player) {
        verifyHeightFilterChanges(player);
        if (Configs.Fill.FILL_SELECTION_TYPE.getComment() != null
                || Configs.Fluid.FLUID_SELECTION_TYPE.getComment() != null) {
            throw new AssertionError("Area-selection scope must not create a hover tooltip");
        }
        if (Configs.Print.PRINT_SELECTION_TYPE.getComment() == null) {
            throw new AssertionError("Unrelated printing descriptions must remain available");
        }
        AreaSelectionType[] options = AreaSelectionType.values();
        if (options.length != 3 || options[2].cycle(true) != AreaSelectionType.ALL
                || AreaSelectionType.ALL.cycle(false) != AreaSelectionType.ABOVE_PLAYER) {
            throw new AssertionError("Area-selection dropdown must contain only its three scope choices");
        }
        for (var config : List.of(Configs.Fill.FILL_SELECTION_TYPE, Configs.Fluid.FLUID_SELECTION_TYPE)) {
            var originalValue = config.getOptionListValue();
            try {
                if (config.getDefaultOptionListValue() != AreaSelectionType.ALL) {
                    throw new AssertionError("Area-selection config still uses printing options");
                }
                for (SelectionType legacy : SelectionType.values()) {
                    AreaSelectionType expected = switch (legacy) {
                        case LITEMATICA_SELECTION_BELOW_PLAYER -> AreaSelectionType.BELOW_PLAYER;
                        case LITEMATICA_SELECTION_ABOVE_PLAYER -> AreaSelectionType.ABOVE_PLAYER;
                        default -> AreaSelectionType.ALL;
                    };
                    config.setValueFromString(legacy.getStringValue());
                    if (config.getOptionListValue() != expected) {
                        throw new AssertionError("Lost old area-selection setting: " + legacy);
                    }
                    String saved = config.getStringValue();
                    config.setValueFromString(saved);
                    if (config.getOptionListValue() != expected) {
                        throw new AssertionError("Area-selection setting did not round-trip: " + saved);
                    }
                    IteratorManager oldRange = new IteratorManager();
                    IteratorManager newRange = new IteratorManager();
                    oldRange.tryBuildBox(player, legacy.heightFilter());
                    newRange.tryBuildBox(player, expected.heightFilter());
                    if (!oldRange.getBox().equals(newRange.getBox())) {
                        throw new AssertionError("Area-selection migration changed the scan range: " + legacy);
                    }
                }
            } finally {
                config.setOptionListValue(originalValue);
            }
        }
    }

    private static void verifyHeightFilterChanges(net.minecraft.client.player.LocalPlayer player) {
        var layers = DataManager.getRenderLayerRange();
        var originalMode = layers.getLayerMode();
        try {
            layers.setLayerMode(fi.dy.masa.malilib.util.LayerMode.ALL);
            IteratorManager range = new IteratorManager();
            range.tryBuildBox(player, PlayerHeightFilter.ALL);
            var full = range.getBox();
            if (range.tryBuildBox(player, PlayerHeightFilter.ALL)) {
                throw new AssertionError("An unchanged height filter must reuse the scan range");
            }
            if (!range.tryBuildBox(player, PlayerHeightFilter.BELOW_PLAYER)
                    || range.getBox().maxY != Math.min(full.maxY, (int) Math.floor(player.getY()))
                    || range.getBox().minY != full.minY) {
                throw new AssertionError("Below-player filtering did not rebuild the vertical range");
            }
            if (!range.tryBuildBox(player, PlayerHeightFilter.ABOVE_PLAYER)
                    || range.getBox().minY != Math.max(full.minY, (int) Math.ceil(player.getY()))
                    || range.getBox().maxY != full.maxY) {
                throw new AssertionError("Above-player filtering did not rebuild the vertical range");
            }
            if (!range.tryBuildBox(player, PlayerHeightFilter.ALL)
                    || !full.equals(range.getBox())) {
                throw new AssertionError("Removing the height filter must restore the original scan range");
            }
        } finally {
            layers.setLayerMode(originalMode);
        }
    }


    private void cell(Block floor) {
        var world = client.getSingleplayerServer().overworld();
        world.setBlockAndUpdate(TARGET.below(2), Blocks.COBBLESTONE.defaultBlockState());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlockAndUpdate(TARGET.below().relative(direction), Blocks.COBBLESTONE.defaultBlockState());
            world.setBlockAndUpdate(TARGET.relative(direction), Blocks.COBBLESTONE.defaultBlockState());
        }
        world.setBlockAndUpdate(TARGET.below(), floor.defaultBlockState());
        world.setBlockAndUpdate(TARGET, scenario >= 3 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }
    private void configure() {
        selectTarget();
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(3);
        Configs.Print.BREAK_COOLDOWN.setIntegerValue(3);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
        Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(false);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }
    private void classifyWater() {
        var guide = new PlacementGuide(client);
        var schematic = SchematicWorldHandler.getSchematicWorld();
        for (int level = 0; level <= 15; level++) {
            var state = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
            check(BlockUtils.isPureWaterSource(state) == state.getFluidState().isSource(), "Water classification differs from vanilla");
            var action = guide.getAction(new SchematicBlockContext(client, client.level, schematic, TARGET, state, Blocks.WATER.defaultBlockState()));
            if (action != null) {
                var items = action.getRequiredItems(Blocks.WATER);
                check(items == null || !java.util.Arrays.asList(items).contains(Items.ICE), "Existing water became an ice placement");
            }
        }
        for (boolean drag : new boolean[]{false,true}) {
            var state = Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, drag);
            var ctx = new SchematicBlockContext(client, client.level, schematic, TARGET, state, Blocks.WATER.defaultBlockState());
            check(BlockUtils.isWaterSource(state) && BlockMatchingType.get(ctx) == BlockMatchingType.CORRECT && guide.getAction(ctx) == null,
                    "Bubble column failed source-water equivalence");
        }
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true);
        check(!BlockUtils.matchesWaterSourceRequest(Blocks.WATER.defaultBlockState(),slab), "Water equivalence hid a wrong solid block");
    }
    private void selectTarget() {
        var area = DataManager.getSimpleArea();
        var box = area.getSubRegionBox(area.getName());
        if (box == null) box = area.getSelectedSubRegionBox();
        check(box != null, "No area selection");
        box.setPos1(TARGET); box.setPos2(TARGET);
    }
    private CompletableFuture<Void> server(Runnable task) {
        return CompletableFuture.runAsync(task, client.getSingleplayerServer());
    }
    private CompletableFuture<Boolean> serverCheck(int ice) {
        return CompletableFuture.supplyAsync(() -> {
            var server = client.getSingleplayerServer();
            return BlockUtils.matchesWaterSourceRequest(Blocks.WATER.defaultBlockState(),server.overworld().getBlockState(TARGET))
                    && countIce(server.getPlayerList().getPlayers().getFirst().getInventory()) == ice;
        },client.getSingleplayerServer());
    }
    private CompletableFuture<Void> assertServer(boolean water, int ice, Block block) {
        return server(() -> {
            var server = client.getSingleplayerServer();
            var state = server.overworld().getBlockState(TARGET);
            check(state.is(block), "Unexpected authoritative water/ice target: " + state);
            check(BlockUtils.matchesWaterSourceRequest(Blocks.WATER.defaultBlockState(),state) == water, "Authoritative source-water mismatch");
            check(countIce(server.getPlayerList().getPlayers().getFirst().getInventory()) == ice, "Authoritative ice count mismatch");
        });
    }
    private static int countIce(net.minecraft.world.entity.player.Inventory inventory) {
        int count=0;
        for(int i=0;i<inventory.getContainerSize();i++) if(inventory.getItem(i).is(Items.ICE)) count+=inventory.getItem(i).getCount();
        return count;
    }
    private static void noWorldWait() {
        check(ModuleManager.PRINT.getScanState()!=ScanState.WAITING || ModuleManager.PRINT.getWaitingReason()!=PrinterWaitReason.WORLD_UPDATE,
                "Ice-to-water entered an indefinite world-update wait");
    }
    private boolean done() { if(work==null) return true; if(!work.isDone()) return false; work.join(); return true; }
    private void next(int value) { phase=value; ticks=0; }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void stop() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
        ActionManager.INSTANCE.clearQueue();
        ModuleManager.PRINT.resetScanState();
    }
}
