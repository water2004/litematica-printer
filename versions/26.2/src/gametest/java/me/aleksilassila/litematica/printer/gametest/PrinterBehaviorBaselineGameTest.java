package me.aleksilassila.litematica.printer.gametest;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.PlacementGuideTestAccess;
import me.aleksilassila.litematica.printer.interfaces.compat.ChainVeinCompat;
import me.aleksilassila.litematica.printer.printer.PlacementGuide;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ChainBreakAction;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locks the complete set of printer action mechanisms before scanner changes.
 * This deliberately fingerprints the full Action contract, not only its bucket key.
 */
@SuppressWarnings("UnstableApiUsage")
public final class PrinterBehaviorBaselineGameTest implements FabricClientGameTest {
    static final BlockPos ORIGIN = new BlockPos(0, 128, 0);
    private static final String EXPECTED_DIGEST =
            "c54af93a098a120feb2993f2464da1d3d7419be425d5aeaa1318a4c6cfc24944";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!GameTestMode.isScanPerformance()) return;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(2);
            context.waitFor(client -> client.player != null && client.level != null);
            context.runOnClient(client -> {
                if (!ChainVeinCompat.isAvailable()) {
                    throw new AssertionError(
                            "The complete printer behavior baseline requires ChainVeinFabric");
                }
                configureBehaviorFeatures();
                List<BehaviorCase> cases = cases();
                String canonical = evaluate(client, cases);
                String digest = sha256(canonical);
                System.out.println("[PrinterBehaviorBaseline] cases=" + cases.size()
                        + " sha256=" + digest);
                if (!EXPECTED_DIGEST.equals(digest)) {
                    throw new AssertionError("Printer behavior changed: expected "
                            + EXPECTED_DIGEST + " but got " + digest);
                }
            });
        }
    }

    static void configureBehaviorFeatures() {
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
        Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(false);
        Configs.Print.STRIP_LOGS.setBooleanValue(true);
        Configs.Print.SAFELY_OBSERVER.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(true);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(true);
        Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(true);
        Configs.Print.REPLACE_CORAL.setBooleanValue(true);
        Configs.Print.BONEMEAL_CROPS.setBooleanValue(true);
        Configs.Print.NOTE_BLOCK_TUNING.setBooleanValue(true);
        Configs.Print.FILL_COMPOSTER.setBooleanValue(true);
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("minecraft:wheat_seeds"));
    }

    static List<BehaviorCase> cases() {
        List<BehaviorCase> cases = new ArrayList<>();

        // Missing-block placement families and their orientation/support semantics.
        cases.add(missing("missing.generic", Blocks.STONE.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.wall_torch", Blocks.WALL_TORCH.defaultBlockState()
                .setValue(WallTorchBlock.FACING, Direction.NORTH), Kind.ACTION));
        cases.add(missing("missing.amethyst", Blocks.SMALL_AMETHYST_BUD.defaultBlockState()
                .setValue(AmethystClusterBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.slab_top", Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP), Kind.ACTION));
        cases.add(missing("missing.stair_top", Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST)
                .setValue(StairBlock.HALF, Half.TOP), Kind.ACTION));
        cases.add(missing("missing.trapdoor_top", Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.FACING, Direction.SOUTH)
                .setValue(TrapDoorBlock.HALF, Half.TOP), Kind.ACTION));
        cases.add(missing("missing.stripped_log", Blocks.STRIPPED_OAK_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X), Kind.ACTION));
        cases.add(missing("missing.anvil", Blocks.ANVIL.defaultBlockState()
                .setValue(AnvilBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.hopper", Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.nether_portal", Blocks.NETHER_PORTAL.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.cocoa", Blocks.COCOA.defaultBlockState()
                .setValue(CocoaBlock.FACING, Direction.NORTH), Kind.ACTION));
        cases.add(missing("missing.crafter", Blocks.CRAFTER.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.chest", Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.SINGLE), Kind.ACTION));
        cases.add(missing("missing.bed_foot", Blocks.BED.red().defaultBlockState()
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.bell_ceiling", Blocks.BELL.defaultBlockState()
                .setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                .setValue(BellBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.door", Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), Kind.ACTION));
        cases.add(missing("missing.dirt_path_seed", Blocks.DIRT_PATH.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.farmland_seed", Blocks.FARMLAND.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.dripleaf_stem", Blocks.BIG_DRIPLEAF_STEM.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.cave_vines", Blocks.CAVE_VINES.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.weeping_vines", Blocks.WEEPING_VINES.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.twisting_vines", Blocks.TWISTING_VINES.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.flower_pot", Blocks.POTTED_DANDELION.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.vine_face", Blocks.VINE.defaultBlockState()
                .setValue(VineBlock.NORTH, true), Kind.ACTION));
        cases.add(missing("missing.glow_lichen_face", Blocks.GLOW_LICHEN.defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true), Kind.ACTION));
        cases.add(missing("missing.fire", Blocks.FIRE.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.observer", Blocks.OBSERVER.defaultBlockState()
                .setValue(ObserverBlock.FACING, Direction.SOUTH), Kind.ACTION));
        cases.add(missing("missing.ladder", Blocks.LADDER.defaultBlockState()
                .setValue(LadderBlock.FACING, Direction.WEST), Kind.ACTION));
        cases.add(missing("missing.lantern_hanging", Blocks.LANTERN.defaultBlockState()
                .setValue(LanternBlock.HANGING, true), Kind.ACTION));
        cases.add(missing("missing.end_rod", Blocks.END_ROD.defaultBlockState()
                .setValue(EndRodBlock.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.tripwire_hook", Blocks.TRIPWIRE_HOOK.defaultBlockState()
                .setValue(TripWireHookBlock.FACING, Direction.NORTH), Kind.ACTION));
        cases.add(missing("missing.rail", Blocks.RAIL.defaultBlockState()
                .setValue(RailBlock.SHAPE, RailShape.ASCENDING_WEST), Kind.ACTION));
        cases.add(missing("missing.piston", Blocks.PISTON.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST), Kind.ACTION));
        cases.add(missing("missing.standing_sign", Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, 7), Kind.ACTION));
        cases.add(missing("missing.wall_sign", Blocks.OAK_WALL_SIGN.defaultBlockState()
                .setValue(WallSignBlock.FACING, Direction.SOUTH), Kind.ACTION));
        cases.add(missing("missing.banner", Blocks.BANNER.white().defaultBlockState()
                .setValue(BannerBlock.ROTATION, 5), Kind.ACTION));
        cases.add(missing("missing.skull", Blocks.SKELETON_SKULL.defaultBlockState()
                .setValue(SkullBlock.ROTATION, 11), Kind.ACTION));
        cases.add(missing("missing.crop_seed", Blocks.PUMPKIN_STEM.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.liquid_skip", Blocks.WATER.defaultBlockState(), Kind.NONE));
        cases.add(missing("missing.double_plant_upper", Blocks.ROSE_BUSH.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Kind.NONE));
        cases.add(missing("missing.coral_replacement", Blocks.DEAD_TUBE_CORAL.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.face_attached", Blocks.STONE_BUTTON.defaultBlockState(), Kind.ACTION));
        cases.add(missing("missing.entity_direction", Blocks.DISPENSER.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.UP), Kind.ACTION));

        // State-correction mechanisms.
        cases.add(state("state.double_slab", Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(SlabBlock.TYPE, SlabType.DOUBLE),
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Kind.ACTION));
        cases.add(state("state.snow_layers", Blocks.SNOW.defaultBlockState()
                        .setValue(SnowLayerBlock.LAYERS, 4),
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1), Kind.CLICK));
        cases.add(state("state.door_open", Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(BlockStateProperties.OPEN, true),
                Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false), Kind.CLICK));
        cases.add(state("state.trapdoor_open", Blocks.OAK_TRAPDOOR.defaultBlockState()
                        .setValue(BlockStateProperties.OPEN, true),
                Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false), Kind.CLICK));
        cases.add(state("state.fence_gate", Blocks.OAK_FENCE_GATE.defaultBlockState()
                        .setValue(BlockStateProperties.OPEN, true),
                Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.OPEN, false), Kind.CLICK));
        cases.add(state("state.lever", Blocks.LEVER.defaultBlockState()
                        .setValue(LeverBlock.POWERED, true),
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.POWERED, false), Kind.CLICK));
        cases.add(state("state.candle_count", Blocks.CANDLE.defaultBlockState()
                        .setValue(CandleBlock.CANDLES, 3),
                Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1), Kind.CLICK));
        cases.add(state("state.candle_light", Blocks.CANDLE.defaultBlockState()
                        .setValue(CandleBlock.LIT, true),
                Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, false), Kind.CLICK));
        cases.add(state("state.candle_extinguish", Blocks.CANDLE.defaultBlockState()
                        .setValue(CandleBlock.LIT, false),
                Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true), Kind.CLICK));
        cases.add(state("state.pickle_count", Blocks.SEA_PICKLE.defaultBlockState()
                        .setValue(SeaPickleBlock.PICKLES, 4),
                Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 1), Kind.CLICK));
        cases.add(state("state.repeater_delay", Blocks.REPEATER.defaultBlockState()
                        .setValue(RepeaterBlock.DELAY, 4),
                Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 1), Kind.CLICK));
        cases.add(state("state.comparator_mode", Blocks.COMPARATOR.defaultBlockState()
                        .setValue(ComparatorBlock.MODE, ComparatorMode.SUBTRACT),
                Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.MODE, ComparatorMode.COMPARE), Kind.CLICK));
        cases.add(state("state.crop_bonemeal", Blocks.WHEAT.defaultBlockState()
                        .setValue(BlockStateProperties.AGE_7, 7),
                Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 2), Kind.CLICK));
        cases.add(state("state.note_tuning", Blocks.NOTE_BLOCK.defaultBlockState()
                        .setValue(NoteBlock.NOTE, 12),
                Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 2), Kind.CLICK));
        cases.add(state("state.campfire_extinguish", Blocks.CAMPFIRE.defaultBlockState()
                        .setValue(CampfireBlock.LIT, false),
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), Kind.CLICK));
        cases.add(state("state.campfire_light", Blocks.CAMPFIRE.defaultBlockState()
                        .setValue(CampfireBlock.LIT, true),
                Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), Kind.CLICK));
        cases.add(state("state.portal_eye", Blocks.END_PORTAL_FRAME.defaultBlockState()
                        .setValue(EndPortalFrameBlock.HAS_EYE, true),
                Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.HAS_EYE, false), Kind.CLICK));
        cases.add(state("state.flowerbed_amount", Blocks.PINK_PETALS.defaultBlockState()
                        .setValue(BlockStateProperties.FLOWER_AMOUNT, 4),
                Blocks.PINK_PETALS.defaultBlockState().setValue(BlockStateProperties.FLOWER_AMOUNT, 1), Kind.CLICK));
        cases.add(state("state.redstone_dot", redstoneDot(), redstoneCross(), Kind.CLICK));
        cases.add(state("state.vine_face", Blocks.VINE.defaultBlockState()
                        .setValue(VineBlock.NORTH, true),
                Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true), Kind.ACTION));
        cases.add(state("state.cauldron_raise", Blocks.WATER_CAULDRON.defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, 3),
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 1), Kind.CLICK));
        cases.add(state("state.cauldron_lower", Blocks.WATER_CAULDRON.defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, 1),
                Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), Kind.CLICK));
        cases.add(state("state.daylight_detector", Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                        .setValue(DaylightDetectorBlock.INVERTED, true),
                Blocks.DAYLIGHT_DETECTOR.defaultBlockState().setValue(DaylightDetectorBlock.INVERTED, false), Kind.CLICK));
        cases.add(state("state.fire_age_ignored", Blocks.FIRE.defaultBlockState()
                        .setValue(FireBlock.AGE, 5),
                Blocks.FIRE.defaultBlockState().setValue(FireBlock.AGE, 1), Kind.NONE));
        cases.add(state("state.composter_fill", Blocks.COMPOSTER.defaultBlockState()
                        .setValue(ComposterBlock.LEVEL, 5),
                Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 1), Kind.CLICK));
        cases.add(state("state.stair_break", Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, Direction.NORTH),
                Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH), Kind.BREAK));
        cases.add(state("state.default_break", Blocks.FURNACE.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), Kind.BREAK));
        cases.add(state("state.connective_ignored", Blocks.OAK_FENCE.defaultBlockState()
                        .setValue(FenceBlock.NORTH, true),
                Blocks.OAK_FENCE.defaultBlockState(), Kind.NONE));

        // Wrong/extra-block conversions and break jobs.
        cases.add(wrong("wrong.farmland_hoe", Blocks.DIRT.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(), Kind.CLICK));
        cases.add(wrong("wrong.dirt_path_shovel", Blocks.DIRT.defaultBlockState(),
                Blocks.DIRT_PATH.defaultBlockState(), Kind.CLICK));
        cases.add(wrong("wrong.potted_content", Blocks.FLOWER_POT.defaultBlockState(),
                Blocks.POTTED_DANDELION.defaultBlockState(), Kind.CLICK));
        cases.add(wrong("wrong.strip_log", Blocks.OAK_LOG.defaultBlockState(),
                Blocks.STRIPPED_OAK_LOG.defaultBlockState(), Kind.CLICK));
        cases.add(wrong("wrong.generic_break", Blocks.DIRT.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Kind.BREAK));
        cases.add(wrong("wrong.extra_break", Blocks.STONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState(), Kind.BREAK));
        cases.add(new BehaviorCase("wrong.replaceable_as_missing",
                Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Map.of(), Map.of(), Kind.ACTION, () -> {
                    Configs.Print.PRINT_REPLACE.setBooleanValue(true);
                    Configs.Print.REPLACEABLE_LIST.setStrings(List.of("minecraft:dirt"));
                }));

        // Top-level skip/ice switches are part of search semantics too.
        cases.add(new BehaviorCase("toggle.skip_waterlogged",
                Blocks.AIR.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true),
                Map.of(), Map.of(), Kind.NONE,
                () -> Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(true)));
        cases.add(new BehaviorCase("toggle.ice_for_water_place",
                Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Map.of(ORIGIN.below(), Blocks.COBWEB.defaultBlockState()), Map.of(), Kind.ACTION,
                () -> Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true)));
        // PlacementGuide intentionally skips liquid blocks here; Print scheduling owns
        // the ICE_WATER phase marker and the consumer owns the actual break/wait action.
        cases.add(new BehaviorCase("scheduler.ice_for_water_break",
                Blocks.ICE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Map.of(), Map.of(), Kind.BREAK,
                () -> Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true)));
        cases.add(state("correct.no_action", Blocks.STONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Kind.NONE));
        return List.copyOf(cases);
    }

    private static BehaviorCase missing(String name, BlockState required, Kind kind) {
        return new BehaviorCase(name, Blocks.AIR.defaultBlockState(), required,
                Map.of(), Map.of(), kind, () -> { });
    }

    private static BehaviorCase state(
            String name, BlockState required, BlockState current, Kind kind) {
        return new BehaviorCase(name, current, required,
                Map.of(), Map.of(), kind, () -> { });
    }

    private static BehaviorCase wrong(
            String name, BlockState current, BlockState required, Kind kind) {
        return new BehaviorCase(name, current, required,
                Map.of(), Map.of(), kind, () -> { });
    }

    private static BlockState redstoneDot() {
        return Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.NONE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.NONE);
    }

    private static BlockState redstoneCross() {
        return Blocks.REDSTONE_WIRE.defaultBlockState()
                .setValue(RedStoneWireBlock.NORTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.SOUTH, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.EAST, RedstoneSide.SIDE)
                .setValue(RedStoneWireBlock.WEST, RedstoneSide.SIDE);
    }

    private static String evaluate(Minecraft client, List<BehaviorCase> cases) {
        PlacementGuide guide = new PlacementGuide(client);
        StringBuilder canonical = new StringBuilder();
        for (BehaviorCase behavior : cases) {
            configureBehaviorFeatures();
            behavior.configure().run();
            Action action = guide.getAction(PlacementGuideTestAccess.snapshotContext(
                    client, ORIGIN, behavior.current(), behavior.required(),
                    behavior.currentNeighbors(), behavior.requiredNeighbors()));
            Kind actual = Kind.of(action);
            if (actual != behavior.expectedKind()) {
                throw new AssertionError(behavior.name() + " expected "
                        + behavior.expectedKind() + " but got " + actual);
            }
            canonical.append(behavior.name()).append('=')
                    .append(describe(action, behavior.required())).append('\n');
        }
        return canonical.toString();
    }

    private static String describe(Action action, BlockState required) {
        if (action == null) return "NONE";
        StringBuilder value = new StringBuilder(action.getClass().getSimpleName());
        Item[] items = action.getRequiredItems(required.getBlock());
        value.append("|items=");
        if (items == null) {
            value.append("empty-hand");
        } else {
            for (int i = 0; i < items.length; i++) {
                if (i > 0) value.append(',');
                value.append(BuiltInRegistries.ITEM.getKey(items[i]));
            }
        }
        value.append("|sides=");
        action.getSides().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Direction::ordinal)))
                .forEach(entry -> appendSide(value, entry.getKey(), entry.getValue()));
        value.append("|look=").append(action.getPlayerLook());
        value.append("|shift=").append(action.getShift());
        value.append("|waitLook=").append(action.getNeedWaitModifyLook());
        value.append("|support=").append(requiresSupport(action));
        return value.toString();
    }

    private static void appendSide(StringBuilder value, Direction side, Vec3 offset) {
        value.append(side.getSerializedName()).append('(')
                .append(Double.doubleToLongBits(offset.x)).append(',')
                .append(Double.doubleToLongBits(offset.y)).append(',')
                .append(Double.doubleToLongBits(offset.z)).append(");");
    }

    private static boolean requiresSupport(Action action) {
        try {
            Field field = Action.class.getDeclaredField("requiresSupport");
            field.setAccessible(true);
            return field.getBoolean(action);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect Action support contract", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private enum Kind {
        NONE,
        ACTION,
        CLICK,
        BREAK;

        static Kind of(Action action) {
            if (action == null) return NONE;
            if (action instanceof ChainBreakAction) return BREAK;
            if (action instanceof ClickAction) return CLICK;
            return ACTION;
        }
    }

    record BehaviorCase(
            String name,
            BlockState current,
            BlockState required,
            Map<BlockPos, BlockState> currentNeighbors,
            Map<BlockPos, BlockState> requiredNeighbors,
            Kind expectedKind,
            Runnable configure) {
    }
}
