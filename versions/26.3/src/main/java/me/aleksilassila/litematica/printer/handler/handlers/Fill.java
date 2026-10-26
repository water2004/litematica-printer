package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.AreaSelectionType;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class Fill extends Module {
    public final static String NAME = "fill";
    private final MaterialSettings materialSettings;

    private List<String> fillCacheBlocklist = new ArrayList<>();
    @Getter
    private Item[] fillModeItemList = new Item[0];

    // 可替换列表缓存，避免每位置都做拼音转换
    private List<String> replaceableCache = new ArrayList<>();
    private final Map<Block, Boolean> replaceableMatchCache = new HashMap<>();

    public Fill() {
        this(NAME, Configs.Fill.ENABLED, Configs.Fill.FILL_SELECTION_TYPE,
                new MaterialSettings(Configs.Fill.FILL_BLOCK_MODE,
                        Configs.Fill.FILL_BLOCK_LIST, Configs.Fill.FILL_HANDHELD_BLACKLIST));
    }

    protected Fill(String name, ConfigBoolean enabled, ConfigOptionList selectionType,
                   MaterialSettings materialSettings) {
        super(name, enabled,
                () -> ((AreaSelectionType) selectionType.getOptionListValue()).heightFilter(), true);
        this.materialSettings = materialSettings;
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean usesJobPool() {
        return true;
    }

    @Override
    protected void preprocess() {
        FillBlockModeType fillMode = (FillBlockModeType) materialSettings.mode().getOptionListValue();
        switch (fillMode) {
            case BLOCKLIST:
                // 每次去MC注册表中获取会造成大量卡顿, 所以仅在玩家修改了填充列表, 再去读取以便注册表
                List<String> strings = materialSettings.blocks().getStrings();
                if (!strings.equals(fillCacheBlocklist)) {
                    fillCacheBlocklist = new ArrayList<>(strings);
                    if (strings.isEmpty()) {
                        return;
                    }
                    List<Item> items = new ArrayList<>();
                    for (String itemName : fillCacheBlocklist) {
                        items.addAll(BuiltInRegistries.ITEM
                                .stream()
                                .filter(item -> PinYinSearchUtils.matchName(itemName, new ItemStack(item)))
                                .toList()
                        );
                    }
                    fillModeItemList = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:  // 手持物品
                if (materialSettings.mode().getOptionListValue() == FillBlockModeType.HANDHELD) {
                    ItemStack heldStack = player.getMainHandItem();
                    if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                        Item heldItem = heldStack.getItem();
                        List<String> blacklist = materialSettings.handheldBlacklist().getStrings();
                        if (!blacklist.isEmpty() && blacklist.stream().anyMatch(s -> PinYinSearchUtils.matchName(s, heldStack))) {
                            fillModeItemList = new Item[0];
                        } else {
                            fillModeItemList = new Item[]{heldItem};
                        }
                    } else {
                        fillModeItemList = new Item[0];
                    }
                }
                break;
        }
    }

    /**
     * 缓存版可替换判断：同种方块只做一次拼音匹配
     */
    private boolean isReplaceable(BlockState state) {
        List<String> current = Configs.Print.REPLACEABLE_LIST.getStrings();
        if (!current.equals(replaceableCache)) {
            replaceableCache = new ArrayList<>(current);
            replaceableMatchCache.clear();
        }
        if (current.isEmpty()) return false;
        return replaceableMatchCache.computeIfAbsent(state.getBlock(),
                block -> current.stream().anyMatch(s -> PinYinSearchUtils.matchName(s, state)));
    }

    @Override
    protected boolean canIterate() {
        return fillModeItemList.length > 0;
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        if (materialSettings.mode().getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem();
            if (heldStack.isEmpty() || heldStack.getCount() <= 0) return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getBlock() instanceof LiquidBlock) return true;
        if (isReplaceable(state)) return true;
        return false;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getBlock() instanceof LiquidBlock) return false;
        if (isReplaceable(state)) return false;
        return true;
    }

    @Override
    protected Object captureSearchContext() {
        return new FillSearchContext(List.copyOf(Configs.Print.REPLACEABLE_LIST.getStrings()));
    }

    @Override
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block,
            Object searchContext) {
        BlockState state = block.currentState();
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return TransactionKey.HOMOGENEOUS;
        }
        FillSearchContext context = (FillSearchContext) searchContext;
        for (String rule : context.replaceRules()) {
            if (PinYinSearchUtils.matchName(rule, state)) {
                return TransactionKey.HOMOGENEOUS;
            }
        }
        return null;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (canProcessPos(blockPos)) {
            BlockPos placementPos = getPlacementPos(blockPos);
            InventoryUtils.ItemSwitchResult switchResult =
                    InventoryUtils.switchToItemsResult(player, this.fillModeItemList);
            if (switchResult != InventoryUtils.ItemSwitchResult.READY) {
                if (switchResult.isWaiting()) {
                    enterWaiting(blockPos, switchResult.waitingReason());
                    skipIteration.set(true);
                    return;
                }
                if (this.fillModeItemList != null && this.fillModeItemList.length > 0 && this.fillModeItemList[0] != null) {
                    MissingMaterialTracker.getInstance().recordMissing(this.fillModeItemList[0],
                            this.fillModeItemList[0].getName(this.fillModeItemList[0].getDefaultInstance())
                    );
                }
                return;
            }
            if (Configs.Placement.FALLING_CHECK.getBooleanValue() &&
                player.getMainHandItem().getItem() instanceof BlockItem item &&
                item.getBlock() instanceof FallingBlock block &&
                FallingBlock.isFree(level.getBlockState(placementPos.below()))
            ) {
                MessageUtils.setOverlayMessage(I18n.BLOCK_NO_SUPPORT.getName(block.getName().getString()));
                return;
            }

            Action action = createPlacementAction(placementPos);
            addHighlight(placementPos, HighlightType.PLACE);
            ActionManager.INSTANCE.setLook(action.getPlayerLook());
            ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
            if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook){
                skipIteration.set(true);
            } else {
                this.setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            }
        }
    }

    protected BlockPos getPlacementPos(BlockPos jobPos) {
        return jobPos;
    }

    protected Action createPlacementAction(BlockPos placementPos) {
        if (ConfigUtils.getFillModeFacing() != null) {
            return new Action()
                    .setLookDirection(ConfigUtils.getFillModeFacing().getOpposite())
                    .queueAction(placementPos, ConfigUtils.getFillModeFacing(), false, player);
        }
        return new Action().queueAction(placementPos, getPlayerPlacementDirection(), false, player);
    }

    protected record MaterialSettings(ConfigOptionList mode, ConfigStringList blocks,
                                      ConfigStringList handheldBlacklist) {
    }

    private record FillSearchContext(List<String> replaceRules) {
    }
}
