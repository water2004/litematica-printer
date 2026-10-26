package me.aleksilassila.litematica.printer.printer.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import static me.aleksilassila.litematica.printer.printer.placement.LogTransforms.STRIPPED_LOGS;

@SuppressWarnings("IfCanBeSwitch")
final class MissingBlockPlacement {
    private MissingBlockPlacement() {}

    static @Nullable Action build(SchematicBlockContext ctx, BlockKind requiredType) {
        switch (requiredType) {
            case TORCH -> {
                Direction lookDirection = ctx.getRequiredStateProperty(WallTorchBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setLookDirection(lookDirection).setRequiresSupport();
            }
            case AMETHYST -> {
                Direction lookDirection = ctx.getRequiredStateProperty(AmethystClusterBlock.FACING).orElse(Direction.UP).getOpposite();
                return new Action().setSides(lookDirection).setRequiresSupport();
            }
            case SLAB -> {
                Map<Direction, Vec3> slabSides = BlockUtils.getSlabSides(
                        ctx.getCurrentView(),
                        ctx.blockPos,
                        ctx.requiredState.getValue(SlabBlock.TYPE));
                return new Action().setSides(slabSides);
            }
            case STAIR -> {
                Direction facing = ctx.requiredState.getValue(StairBlock.FACING);
                Half half = ctx.requiredState.getValue(StairBlock.HALF);
                Map<Direction, Vec3> sides = new HashMap<>();
                if (half == Half.BOTTOM) {
                    sides.put(Direction.DOWN, new Vec3(0, 0, 0));
                    sides.put(facing, new Vec3(0, 0, 0));
                } else {
                    sides.put(Direction.UP, new Vec3(0, 0.75, 0));
                    sides.put(facing.getOpposite(), new Vec3(0, 0.75, 0));
                }
                return new Action().setSides(sides).setLookDirection(facing);
            }
            case TRAPDOOR -> {
                Half half = ctx.requiredState.getValue(TrapDoorBlock.HALF);
                Direction side = half == Half.TOP ? Direction.UP : Direction.DOWN;
                Direction facing = ctx.requiredState.getValue(TrapDoorBlock.FACING);
                return new Action()
                        .setSides(side)
                        .setLookDirection(facing.getOpposite());
            }
            case STRIP_LOG -> {
                Action action = new Action().setSides(ctx.requiredState.getValue(RotatedPillarBlock.AXIS));
                Item[] items = {ctx.requiredState.getBlock().asItem()};
                if (Configs.Print.STRIP_LOGS.getBooleanValue()) {
                    for (Map.Entry<Block, Block> entry : STRIPPED_LOGS.entrySet()) {
                        if (ctx.requiredState.getBlock() == entry.getValue()) {
                            items = new Item[]{entry.getValue().asItem(), entry.getKey().asItem()};
                            break;
                        }
                    }
                }
                action.setItems(items);
                return action;
            }
            case ANVIL -> {
                return new Action().setLookDirection(ctx.requiredState.getValue(AnvilBlock.FACING).getCounterClockWise());
            }
            case HOPPER -> {
                Direction facing = ctx.requiredState.getValue(HopperBlock.FACING);
                return new Action().setSides(facing);
            }
            case NETHER_PORTAL -> {
                boolean canCreatePortal = ctx.isSnapshot()
                        || PortalShape.findEmptyPortalShape(
                                Objects.requireNonNull(ctx.level),
                                ctx.blockPos,
                                Direction.Axis.X).isPresent();
                if (canCreatePortal) {
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                }
            }
            case COCOA -> {
                return new Action().setSides(ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING));
            }
            case CRAFTER -> {
                FrontAndTop frontAndTop = ctx.requiredState.getValue(BlockStateProperties.ORIENTATION);
                Direction facing = frontAndTop.front().getOpposite();
                Direction rotation = frontAndTop.top().getOpposite();
                if (facing == Direction.UP) {
                    return new Action().setLookDirection(rotation, Direction.UP).setNeedWaitModifyLook(true);
                } else if (facing == Direction.DOWN) {
                    return new Action().setLookDirection(rotation.getOpposite(), Direction.DOWN).setNeedWaitModifyLook(true);
                } else {
                    return new Action().setLookDirection(facing, facing).setNeedWaitModifyLook(true);
                }
            }
            case CHEST -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
                ChestType type = ctx.requiredState.getValue(BlockStateProperties.CHEST_TYPE);
                Map<Direction, Vec3> noChestSides = new HashMap<>();

                for (Direction side : Direction.values()) {
                    if (ctx.getCurrentState(ctx.blockPos.relative(side)).getBlock() instanceof ChestBlock) {
                        continue;
                    }
                    noChestSides.put(side, Vec3.ZERO);
                }

                if (type == ChestType.SINGLE) {
                    for (Direction side : BlockStateProperties.HORIZONTAL_FACING.getPossibleValues()) {
                        if (!noChestSides.containsKey(side)) {
                            return new Action().setLookDirection(facing).setShift();
                        }
                        return new Action().setSides(noChestSides).setLookDirection(facing);
                    }
                } else {
                    Direction chestFacing = facing;
                    if (type == ChestType.LEFT) {
                        chestFacing = facing.getCounterClockWise();
                    } else if (type == ChestType.RIGHT) {
                        chestFacing = facing.getClockWise();
                    }
                    if (ctx.getCurrentState(ctx.blockPos.relative(chestFacing)).getBlock() instanceof ChestBlock) {
                        return new Action().setSides(Map.of(chestFacing, Vec3.ZERO)).setLookDirection(facing).setShift(false);
                    } else {
                        return new Action().setSides(noChestSides).setLookDirection(facing).setShift();
                    }
                }
            }
            case BED -> {
                if (ctx.requiredState.getValue(BedBlock.PART) == BedPart.FOOT)
                    return new Action().setLookDirection(ctx.requiredState.getValue(BedBlock.FACING));
            }
            case BELL -> {
                Direction side;
                switch (ctx.requiredState.getValue(BellBlock.ATTACHMENT)) {
                    case FLOOR -> side = Direction.DOWN;
                    case CEILING -> side = Direction.UP;
                    default -> side = ctx.requiredState.getValue(BellBlock.FACING);
                }

                Direction look = ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.SINGLE_WALL && ctx.requiredState.getValue(BellBlock.ATTACHMENT) != BellAttachType.DOUBLE_WALL ? ctx.requiredState.getValue(BellBlock.FACING) : null;

                return new Action().setSides(side).setLookDirection(look);
            }
            case DOOR -> {
                Direction facing = ctx.requiredState.getValue(DoorBlock.FACING);
                DoorHingeSide hinge = ctx.requiredState.getValue(DoorBlock.HINGE);
                BlockPos upperPos = ctx.blockPos.above();

                // 获取门铰链方向
                Direction hingeSide = facing.getCounterClockWise();

                double offset = hinge == DoorHingeSide.RIGHT ? 0.25 : -0.25;
                Vec3 hingeVec = facing.getAxis() == Direction.Axis.X ? new Vec3(0, 0, offset) : new Vec3(offset, 0, 0);

                Map<Direction, Vec3> sides = new HashMap<>();
                sides.put(hingeSide, Vec3.ZERO); // 靠墙方向需要支撑
                sides.put(Direction.DOWN, hingeVec); // 底部点击偏移
                sides.put(facing, hingeVec); // 正面点击偏移

                // 获取左右方块状态
                Direction left = facing.getCounterClockWise();
                Direction right = facing.getCounterClockWise();
                BlockState leftState = ctx.getCurrentState(ctx.blockPos.relative(left));
                BlockState leftUpperState = ctx.getCurrentState(upperPos.relative(left));
                BlockState rightState = ctx.getCurrentState(ctx.blockPos.relative(right));
                BlockState rightUpperState = ctx.getCurrentState(upperPos.relative(right));

                int occupancy =
                        (ctx.isCurrentCollisionShapeFull(leftState, ctx.blockPos.relative(left)) ? -1 : 0)
                        + (ctx.isCurrentCollisionShapeFull(leftUpperState, upperPos.relative(left)) ? -1 : 0)
                        + (ctx.isCurrentCollisionShapeFull(rightState, ctx.blockPos.relative(right)) ? 1 : 0)
                        + (ctx.isCurrentCollisionShapeFull(rightUpperState, upperPos.relative(right)) ? 1 : 0);

                boolean isLeftDoor = leftState.getBlock() instanceof DoorBlock && leftState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
                boolean isRightDoor = rightState.getBlock() instanceof DoorBlock && rightState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;

                boolean condition = (hinge == DoorHingeSide.RIGHT && ((isLeftDoor && !isRightDoor) || occupancy > 0)) || (hinge == DoorHingeSide.LEFT && ((isRightDoor && !isLeftDoor) || occupancy < 0)) || (occupancy == 0 && (isLeftDoor == isRightDoor));
                if (condition) return new Action().setSides(sides).setLookDirection(facing).setRequiresSupport();
            }
            case DIRT_PATH, FARMLAND -> {
                return new Action().setItems(Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.MYCELIUM, Items.PODZOL);
            }
            case BIG_DRIPLEAF_STEM -> {
                return new Action().setItem(Items.BIG_DRIPLEAF);
            }
            case CAVE_VINES -> {
                return new Action().setItem(Items.GLOW_BERRIES).setRequiresSupport();
            }
            case WEEPING_VINES -> {
                return new Action().setItem(Items.WEEPING_VINES).setRequiresSupport();
            }
            case TWISTING_VINES -> {
                return new Action().setItem(Items.TWISTING_VINES).setRequiresSupport();
            }
            case FLOWER_POT -> {
                return new Action().setItem(Items.FLOWER_POT);
            }
            case VINES, GLOW_LICHEN -> {
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN && ctx.requiredState.getBlock() == Blocks.VINE) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction);
                    }
                }
            }
            case FIRE -> {
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock)
                    return new Action().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case OBSERVER -> {
                Direction facing = ctx.requiredState.getValue(ObserverBlock.FACING);
                return new Action().setLookDirection(facing).setNeedWaitModifyLook(true);
            }
            case LADDER -> {
                Direction facing = ctx.requiredState.getValue(LadderBlock.FACING);
                return new Action()
                        .setSides(facing)
                        .setLookDirection(facing.getOpposite())
                        .setNeedWaitModifyLook();
            }
            case LANTERN -> {
                if (ctx.requiredState.getValue(LanternBlock.HANGING))
                    return new Action().setLookDirection(Direction.UP);
                return new Action().setLookDirection(Direction.DOWN);
            }
            case ROD -> {
                Block requiredBlock = ctx.requiredState.getBlock();
                Direction facing = ctx.requiredState.getValue(EndRodBlock.FACING);

                // 如果前面朝向自己的末地烛，而放置方式相反，那么反向放置
                if (requiredBlock instanceof EndRodBlock) {
                    BlockState forwardState = ctx.getCurrentState(ctx.blockPos.relative(facing));
                    BlockState forwardStateSchematic = ctx.getCurrentState(ctx.blockPos.relative(facing));
                    if (forwardState.is(requiredBlock) && forwardState.getValue(EndRodBlock.FACING) == facing.getOpposite()) {
                        return new Action().setSides(facing);
                    }
                    // 如果投影中后面有相同朝向的末地烛，则先跳过放置
                    if (forwardStateSchematic.is(requiredBlock) && forwardStateSchematic.getValue(EndRodBlock.FACING) == facing) {
                        // 但是这个投影已经被正确填装时可以打印
                        if (forwardStateSchematic == forwardState) return new Action().setSides(facing.getOpposite());
                        return null;
                    }
                }
                return new Action().setSides(facing.getOpposite());
            }
            case TRIPWIRE_HOOK -> {
                Direction facing = ctx.requiredState.getValue(TripWireHookBlock.FACING);
                return new Action().setSides(facing);
            }
            case RAIL -> {
                Action action = new Action();
                RailShape shape;
                if (ctx.requiredState.getBlock() instanceof RailBlock)
                    shape = ctx.requiredState.getValue(RailBlock.SHAPE);
                else shape = ctx.requiredState.getValue(BlockStateProperties.RAIL_SHAPE_STRAIGHT);

                switch (shape) {
                    case EAST_WEST, ASCENDING_EAST -> action.setLookDirection(Direction.EAST);
                    case NORTH_SOUTH, ASCENDING_NORTH -> action.setLookDirection(Direction.NORTH);
                    case ASCENDING_WEST -> action.setLookDirection(Direction.WEST);
                    case ASCENDING_SOUTH -> action.setLookDirection(Direction.SOUTH);
                }
                if (ctx.requiredState.getBlock() instanceof RailBlock) {
                    if (shape == RailShape.SOUTH_EAST) {
                        return action;
                    }
                    // TODO)) 完成这非常恶心的铁轨算法
                }
                return action;
            }
            case PISTON -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                return new Action().setLookDirection(facing.getOpposite()).setNeedWaitModifyLook();
            }
            case SIGN -> {
                Block signBlock = ctx.requiredState.getBlock();
                // 站立告示牌：处理0-15的16方向旋转值
                if (signBlock instanceof StandingSignBlock) {
                    int rotation = ctx.requiredState.getValue(StandingSignBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                // 墙告示牌：保留原有4方向逻辑
                if (signBlock instanceof WallSignBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSignBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
                // 天花板悬挂告示牌处理逻辑
                if (signBlock instanceof WallHangingSignBlock) {
                    //TODO: 视乎方向还是有点问题, 待处理
                    Direction facing = ctx.requiredState.getValue(WallHangingSignBlock.FACING);
                    List<Direction> sides = new ArrayList<>();
                    if (facing.getAxis() == Direction.Axis.X) {
                        sides.add(Direction.NORTH);
                        sides.add(Direction.SOUTH);
                    } else if (facing.getAxis() == Direction.Axis.Z) {
                        sides.add(Direction.EAST);
                        sides.add(Direction.WEST);
                    }
                    return new Action()
                            .setSides(sides.toArray(new Direction[0]))
                            .setLookDirection(facing.getOpposite()).setRequiresSupport();
                }
                if (signBlock instanceof CeilingHangingSignBlock) {
                    int rotation = ctx.requiredState.getValue(CeilingHangingSignBlock.ROTATION);
                    boolean attachFace = ctx.requiredState.getValue(CeilingHangingSignBlock.ATTACHED);
                    return new Action()
                            .setShift(attachFace)
                            .setSides(Direction.UP)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                }
                return null;
            }
            case BANNER -> {
                if (ctx.requiredState.getBlock() instanceof BannerBlock) {
                    int rotation = ctx.requiredState.getValue(BannerBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(rotation)
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallBannerBlock) {
                    Direction facing = ctx.requiredState.getValue(WallBannerBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case SKULL -> {
                if (ctx.requiredState.getBlock() instanceof SkullBlock) {
                    int rotation = ctx.requiredState.getValue(SkullBlock.ROTATION);
                    return new Action()
                            .setSides(Direction.DOWN)
                            .setLookRotation(BlockUtils.getOppositeRotation(rotation))
                            .setRequiresSupport();
                } else if (ctx.requiredState.getBlock() instanceof WallSkullBlock) {
                    Direction facing = ctx.requiredState.getValue(WallSkullBlock.FACING);
                    return new Action()
                            .setSides(facing.getOpposite())
                            .setLookDirection(facing.getOpposite())
                            .setRequiresSupport();
                }
            }
            case CROPS -> {
                String blockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                if (blockKey.contains("pumpkin")) {
                    return new Action()
                            .setItem(Items.PUMPKIN_SEEDS)
                            .setRequiresSupport();
                }
                if (blockKey.contains("melon")) {
                    return new Action()
                            .setItem(Items.MELON_SEEDS)
                            .setRequiresSupport();
                }
                return new Action();
            }
            case SKIP -> {
                return null;
            }
            default -> {
                Block block = ctx.requiredState.getBlock();
                Identifier blockId1 = BlockUtils.getKey(block);
                if (blockId1.toString().contains("coral")) {
                    Identifier blockId2 = of(blockId1.toString().replace("dead_", ""));
                    boolean isBlock = blockId1.toString().contains("block");
                    List<Item> items = new ArrayList<>();
                    items.add(block.asItem());
                    if (Configs.Print.REPLACE_CORAL.getBooleanValue()) {
                        if (!blockId1.equals(blockId2)) {
                            items.add(BlockUtils.getBlock(blockId2).asItem());
                        }
                    }
                    Action action = new Action().setItems(items.toArray(new Item[0]));
                    if (!isBlock) {
                        boolean isWallFan = block instanceof BaseCoralWallFanBlock;
                        Direction facing = isWallFan ? ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite() : Direction.DOWN;
                        action.setSides(facing).setRequiresSupport();
                    }
                    return action;
                }
                Action action = new Action();
                if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
                    Direction side = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    AttachFace face = ctx.requiredState.getValue(BlockStateProperties.ATTACH_FACE);
                    // 简化方向判断逻辑 三元运算符 Direction.UP那报错？ 应该可以正常运行但是还是换了switch格式
                    //Direction sidePitch = face == AttachFace.CEILING ? Direction.UP : face == AttachFace.FLOOR ? Direction.DOWN : side;
                    Direction sidePitch = switch (face) {
                        case CEILING -> Direction.UP;
                        case FLOOR   -> Direction.DOWN;
                        default      -> side;
                    };
                    if (face != AttachFace.WALL) {
                        side = side.getOpposite();
                    }
                    return new Action().setSides(side).setLookDirection(side.getOpposite(), sidePitch).setNeedWaitModifyLook();
                }
                if (block instanceof HorizontalDirectionalBlock || block instanceof StonecutterBlock
                        // @formatter:off
                        || block instanceof
                            FlowerBedBlock
                        // @formatter:on
                ) {
                    Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    if (block instanceof FenceGateBlock) // 栅栏门
                        facing = facing.getOpposite();
                    action.setLookDirection(facing.getOpposite());
                }
                if (block instanceof BaseEntityBlock) {
                    Direction facing;
                    if (ctx.requiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                        if (
                        block instanceof DecoratedPotBlock ||
                        block instanceof CampfireBlock) facing = facing.getOpposite();
                        action.setSides(facing).setLookDirection(facing.getOpposite());
                    }
                    if (ctx.requiredState.hasProperty(BlockStateProperties.FACING)) {
                        facing = ctx.requiredState.getValue(BlockStateProperties.FACING);
                        if (ctx.requiredState.getBlock() instanceof ShulkerBoxBlock) {
                            facing = facing.getOpposite();
                            action.setShift();
                        }
                        if (ctx.requiredState.getBlock() instanceof BarrelBlock)
                            action.setNeedWaitModifyLook();
                        action.setSides(facing).setLookDirection(facing.getOpposite());
                        if (block instanceof DispenserBlock)
                            action.setNeedWaitModifyLook();
                    }
                }
                //方块型珊瑚的替换
                if (Configs.Print.REPLACE_CORAL.getBooleanValue() && block.getDescriptionId().endsWith("_coral_block")) {
                    //例子：block.minecraft.dead_tube_coral
                    String type = block.getDescriptionId().replace("block.minecraft.dead_", "").replace("_coral_block", "");
                    switch (type) {
                        case "tube" -> action.setItem(Items.TUBE_CORAL_BLOCK);
                        case "brain" -> action.setItem(Items.BRAIN_CORAL_BLOCK);
                        case "bubble" -> action.setItem(Items.BUBBLE_CORAL_BLOCK);
                        case "fire" -> action.setItem(Items.FIRE_CORAL_BLOCK);
                        case "horn" -> action.setItem(Items.HORN_CORAL_BLOCK);
                    }
                    action.setRequiresSupport();
                }
                return action;
            }
        }
        return null;
    }

    private static Identifier of(String string) {
        return Identifier.parse(string);
    }
}
