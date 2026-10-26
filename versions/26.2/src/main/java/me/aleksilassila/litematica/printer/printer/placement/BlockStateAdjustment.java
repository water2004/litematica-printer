package me.aleksilassila.litematica.printer.printer.placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ChainBreakAction;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("IfCanBeSwitch")
final class BlockStateAdjustment {
    private BlockStateAdjustment() {}

    private static List<String> compostWhitelistCache = new ArrayList<>();      // 缓存堆肥桶白名单的字符串列表（用于判断是否修改）
    private static Item[] whitelistItemsCache = new Item[0];    // 缓存过滤后的可堆肥物品列表（避免重复计算）

    static @Nullable Action build(SchematicBlockContext ctx, BlockKind requiredType, Minecraft mc) {
        boolean printBreakWrongStateBlock = Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue();

        switch (requiredType) {
            case SLAB -> {
                if (ctx.requiredState.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
                    Direction requiredHalf = ctx.currentState.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.DOWN : Direction.UP;
                    return new Action().setSides(requiredHalf);
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case SNOW -> {
                int layers = ctx.currentState.getValue(SnowLayerBlock.LAYERS);
                if (layers < ctx.requiredState.getValue(SnowLayerBlock.LAYERS)) {
                    Map<Direction, Vec3> sides = new HashMap<>() {{
                        put(Direction.UP, new Vec3(0, (layers / 8d) - 1, 0));
                    }};
                    return new ClickAction().setItem(Items.SNOW).setSides(sides);
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case DOOR, TRAPDOOR -> {
                //判断门是不是铁制的，如果是就直接返回
                if (ctx.requiredState.is(Blocks.IRON_DOOR) || ctx.requiredState.is(Blocks.IRON_TRAPDOOR)) {
                    break;
                }
                if (ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    boolean facingDiff = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            != ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                    boolean alignDiff = false;
                    if (ctx.requiredState.hasProperty(TrapDoorBlock.HALF)) {
                        alignDiff = ctx.requiredState.getValue(TrapDoorBlock.HALF)
                                != ctx.currentState.getValue(TrapDoorBlock.HALF);
                    }
                    if (ctx.requiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                        alignDiff |= ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                                != ctx.currentState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
                    }
                    if (ctx.requiredState.hasProperty(DoorBlock.HINGE)) {
                        alignDiff |= ctx.requiredState.getValue(DoorBlock.HINGE)
                                != ctx.currentState.getValue(DoorBlock.HINGE);
                    }
                    if (facingDiff || alignDiff) {
                        return new ChainBreakAction();
                    }
                }
            }
            case FENCE_GATE -> {
                Direction facing = ctx.requiredState.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (facing.getOpposite() == ctx.currentState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        || ctx.requiredState.getValue(BlockStateProperties.OPEN) != ctx.currentState.getValue(BlockStateProperties.OPEN)
                ) {
                    return new ClickAction().setSides(facing.getOpposite()).setLookDirection(facing);
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case LEVER -> {
                if (ctx.requiredState.getValue(LeverBlock.POWERED) != ctx.currentState.getValue(LeverBlock.POWERED)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case CANDLES -> {
                if (ctx.currentState.getValue(BlockStateProperties.CANDLES) < ctx.requiredState.getValue(BlockStateProperties.CANDLES)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (!ctx.currentState.getValue(CandleBlock.LIT) && ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (ctx.currentState.getValue(CandleBlock.LIT) && !ctx.requiredState.getValue(CandleBlock.LIT)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case PICKLES -> {
                if (ctx.currentState.getValue(SeaPickleBlock.PICKLES) < ctx.requiredState.getValue(SeaPickleBlock.PICKLES)) {
                    return new ClickAction().setItem(Items.SEA_PICKLE);
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case REPEATER -> {
                if (!ctx.requiredState.getValue(RepeaterBlock.DELAY).equals(ctx.currentState.getValue(RepeaterBlock.DELAY))) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock &&
                        ctx.requiredState.getValue(RepeaterBlock.POWERED) == ctx.currentState.getValue(RepeaterBlock.POWERED) &&
                        ctx.requiredState.getValue(RepeaterBlock.LOCKED) == ctx.currentState.getValue(RepeaterBlock.LOCKED)
                ) {
                    return new ChainBreakAction();
                }
            }
            case COMPARATOR -> {
                if (ctx.requiredState.getValue(ComparatorBlock.MODE) != ctx.currentState.getValue(ComparatorBlock.MODE)) {
                    return new ClickAction();
                }
                if (printBreakWrongStateBlock) {
                    Direction requiredFacing = ctx.requiredState.getValue(ComparatorBlock.FACING);
                    Direction currentFacing = ctx.currentState.getValue(ComparatorBlock.FACING);
                    if (requiredFacing == currentFacing) {
                        SchematicBlockContext facingFirstBlockCtx = ctx.offset(requiredFacing);
                        // 检验输出信号
                        if (ctx.getCurrentSignal(ctx.blockPos, requiredFacing)
                                != ctx.getRequiredSignal(ctx.blockPos, requiredFacing)) {
                            // 检验输入端是否为"能输出比较器信号方块"
                            if (facingFirstBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                return null;
                            }
                            // 检验输入端非透明方块
                            if (facingFirstBlockCtx.requiredState.isRedstoneConductor(
                                    facingFirstBlockCtx.getRequiredView(),
                                    facingFirstBlockCtx.blockPos)) {
                                SchematicBlockContext facingSecondBlockCtx = facingFirstBlockCtx.offset(requiredFacing);
                                // 仿照原版检验物品展示框
                                BlockPos blockPos = facingSecondBlockCtx.blockPos;
                                List<ItemFrame> itemFrameList = ctx.isSnapshot()
                                        ? List.of()
                                        : Objects.requireNonNull(facingSecondBlockCtx.schematic)
                                                .getEntitiesOfClass(
                                                        ItemFrame.class,
                                                        new AABB(blockPos),
                                                        itemFrame -> itemFrame.getDirection()
                                                                == requiredFacing);
                                // 隔非透明方块检验容器
                                if (facingSecondBlockCtx.requiredState.hasAnalogOutputSignal()) {
                                    return null;
                                }
                                // 隔非透明方块检验物品展示框
                                if (!itemFrameList.isEmpty()) {
                                    return null;
                                }
                            }
                        }
                    }
                    return new ChainBreakAction();
                }
            }
            case CROPS -> {
                if (!Configs.Print.BONEMEAL_CROPS.getBooleanValue()) {
                    return null;
                }
                Block currentBlock = ctx.currentState.getBlock();
                Block requiredBlock = ctx.requiredState.getBlock();
                if (currentBlock == requiredBlock
                        && (ctx.isSnapshot()
                            || InventoryUtils.playerHasAccessToItem(mc.player, Items.BONE_MEAL))) {
                    int maxAge = requiredBlock instanceof BeetrootBlock ? 3 : 7;
                    int requiredAge = ctx.requiredState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    int currentAge = ctx.currentState.getValue(requiredBlock instanceof BeetrootBlock ? BeetrootBlock.AGE : StemBlock.AGE);
                    if (requiredAge == maxAge && currentAge < maxAge) {
                        return new ClickAction().setItem(Items.BONE_MEAL);
                    }
                }
            }
            case NOTE_BLOCK -> {
                if (Configs.Print.NOTE_BLOCK_TUNING.getBooleanValue() && !Objects.equals(ctx.requiredState.getValue(NoteBlock.NOTE), ctx.currentState.getValue(NoteBlock.NOTE))) {
                    return new ClickAction();
                }
            }
            case CAMPFIRE -> {
                if (!ctx.requiredState.getValue(CampfireBlock.LIT) && ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Reference.SHOVEL_ITEMS).setSides(Direction.UP);
                }
                if (ctx.requiredState.getValue(CampfireBlock.LIT) && !ctx.currentState.getValue(CampfireBlock.LIT)) {
                    return new ClickAction().setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE);
                }
                if (printBreakWrongStateBlock && ctx.requiredState.getValue(CampfireBlock.FACING) != ctx.currentState.getValue(CampfireBlock.FACING)) {
                    return new ChainBreakAction();
                }
            }
            case END_PORTAL_FRAME -> {
                if (ctx.requiredState.getValue(EndPortalFrameBlock.HAS_EYE) && !ctx.currentState.getValue(EndPortalFrameBlock.HAS_EYE)) {
                    return new ClickAction().setItem(Items.ENDER_EYE);
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case FLOWERBED -> {
                if (ctx.currentState.getValue(BlockStateProperties.FLOWER_AMOUNT) <= ctx.requiredState.getValue(BlockStateProperties.FLOWER_AMOUNT)) {
                    return new ClickAction().setItem(ctx.requiredState.getBlock().asItem());
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case RED_STONE_WIRE -> {
                // 在Java版中，对于没有连接到任何红石元件的十字形的红石线，可以按使用键使其变为点状，从而不与任何方向连接，再按一次可以恢复。
                boolean allNoneRequired = ctx.requiredState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.NONE &&
                        ctx.requiredState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.NONE;

                boolean allSideCurrent = ctx.currentState.getValue(RedStoneWireBlock.NORTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.SOUTH) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.EAST) == RedstoneSide.SIDE &&
                        ctx.currentState.getValue(RedStoneWireBlock.WEST) == RedstoneSide.SIDE;

                if (allNoneRequired && allSideCurrent) {
                    return new ClickAction().setItem(Items.AIR);
                }
            }
            case VINES, GLOW_LICHEN -> {
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setLookDirection(direction);
                    }
                }
                if (printBreakWrongStateBlock) {
                    return new ChainBreakAction();
                }
            }
            case CAULDRON -> {
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) > ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL)) {
                    if (ctx.isSnapshot()
                            || InventoryUtils.playerHasAccessToItem(mc.player, Items.GLASS_BOTTLE)) {
                        return new ClickAction().setItem(Items.GLASS_BOTTLE);
                    } else if (!ctx.isSnapshot()) {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_LOWER.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
                }
                if (ctx.currentState.getValue(LayeredCauldronBlock.LEVEL) < ctx.requiredState.getValue(LayeredCauldronBlock.LEVEL))
                    if (ctx.isSnapshot()
                            || InventoryUtils.playerHasAccessToItem(mc.player, Items.POTION)) {
                        return new ClickAction().setItem(Items.POTION);
                    } else if (!ctx.isSnapshot()) {
                        MessageUtils.setOverlayMessage(I18n.BREWINGSTAND_RAISE.getName(getNameFromItem(Items.GLASS_BOTTLE)));
                    }
            }
            case DAYLIGHT_DETECTOR -> {
                if (ctx.currentState.getValue(DaylightDetectorBlock.INVERTED) != ctx.requiredState.getValue(DaylightDetectorBlock.INVERTED)) {
                    return new ClickAction();
                }
            }
            case FIRE -> {
                if (!ctx.requiredState.getValue(FireBlock.AGE).equals(ctx.currentState.getValue(FireBlock.AGE))) {
                    return null;
                }
                if (ctx.requiredState.getBlock() instanceof SoulFireBlock) return null;
                for (Direction direction : Direction.values()) {
                    if (direction == Direction.DOWN) continue;
                    if ((Boolean) BlockUtils.getPropertyByName(ctx.requiredState, direction.name())) {
                        return new Action().setSides(direction).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
                    }
                }
                return new Action().setSides(Direction.DOWN).setItems(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE).setRequiresSupport();
            }
            case COMPOSTER -> {
                if (!Configs.Print.FILL_COMPOSTER.getBooleanValue()) {
                    return null;
                }
                if (ctx.currentState.getValue(ComposterBlock.LEVEL) >= ctx.requiredState.getValue(ComposterBlock.LEVEL)) {
                    return null;
                }
                List<String> whitelist = List.copyOf(
                        Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings());
                Item[] finalItems;
                synchronized (BlockStateAdjustment.class) {
                    if (!whitelist.equals(compostWhitelistCache)) {
                        compostWhitelistCache = new ArrayList<>(whitelist);
                        List<Item> whitelistItems = new ArrayList<>();
                        for (Item item : Reference.COMPOSTABLE_ITEMS) {
                            for (String rule : whitelist) {
                                if (PinYinSearchUtils.matchName(rule, new ItemStack(item))) {
                                    whitelistItems.add(item);
                                    break;
                                }
                            }
                        }
                        whitelistItemsCache = whitelistItems.toArray(Item[]::new);
                    }
                    finalItems = whitelistItemsCache.length > 0
                            ? whitelistItemsCache.clone()
                            : Reference.COMPOSTABLE_ITEMS;
                }
                if (finalItems.length > 0) {
                    return new ClickAction().setItems(finalItems);
                }
            }
            case STAIR -> {
                if (printBreakWrongStateBlock &&
                        (ctx.requiredState.getValue(StairBlock.FACING) != ctx.currentState.getValue(StairBlock.FACING) ||
                                ctx.requiredState.getValue(StairBlock.HALF) != ctx.currentState.getValue(StairBlock.HALF))) {
                    return new ChainBreakAction();
                }
            }
            case DEFAULT -> {
                Class<?>[] ignored = new Class<?>[]
                        {FenceBlock.class,
                                WallBlock.class,
                                IronBarsBlock.class,
                                PressurePlateBlock.class,
                                StainedGlassPaneBlock.class
                        };
                if (printBreakWrongStateBlock && !Arrays.asList(ignored).contains(ctx.requiredState.getBlock().getClass())) {
                    return new ChainBreakAction();
                }
            }
        }
        return null;
    }

    private static Component getNameFromItem(Item item) {
        return item.getName(item.getDefaultInstance());
    }
}
