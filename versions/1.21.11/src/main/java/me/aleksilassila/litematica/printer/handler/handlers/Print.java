package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.status.PrinterWaitReason;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ChainBreakAction;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.interfaces.compat.ChainVeinCompat;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class Print extends Module {
    public final static String NAME = "print";

    private final PlacementGuide guide;

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;

    private SchematicBlockContext ctx;

    // canProcessPos 缓存
    private List<String> lastSkipConfig = Collections.emptyList();
    private Set<String> skipSet = Collections.emptySet();
    private Block lastSkipBlock = null;
    private boolean lastSkipResult = false;

    public Print() {
        super(NAME, Configs.Print.ENABLED,
                () -> ((SelectionType) Configs.Print.PRINT_SELECTION_TYPE.getOptionListValue()).heightFilter(),
                true);
        this.guide = new PlacementGuide(client);
        this.needSchematic = true;
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
    protected int snapshotHalo() {
        // PlacementGuide reads direct neighbors and, for comparator/door safety,
        // positions up to two blocks away. Keep the full semantic boundary here.
        return 2;
    }

    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;

        BlockState required = schematic.getBlockState(blockPos);
        BlockState current = level.getBlockState(blockPos);

        // 如果原理图为空气且不破坏多余方块，则无法处理
        if (required.isAir()) {
            if (!Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue()) return false;
        }

        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos, current, required);

        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            List<String> currentConfig = Configs.Print.PRINT_SKIP_LIST.getStrings();
            if (!currentConfig.equals(lastSkipConfig)) {
                skipSet = new HashSet<>(currentConfig);
                lastSkipConfig = currentConfig;
                lastSkipBlock = null;
            }

            Block block = ctx.requiredState.getBlock();
            if (block != lastSkipBlock) {
                lastSkipBlock = block;
                lastSkipResult = false;
                for (String s : skipSet) {
                    if (PinYinSearchUtils.matchName(s, ctx.requiredState)) {
                        lastSkipResult = true;
                        break;
                    }
                }
            }
            if (lastSkipResult) return false;
        }

        Action action = guide.getAction(ctx);
        if (action == null) return false;
        if (action instanceof ChainBreakAction && !ChainVeinCompat.canBreakBlock(blockPos)) {
            return false;
        }
        this.action = action;
        return true;
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        BlockState required = LitematicaUtils.getBlockState(pos);
        BlockState current = level.getBlockState(pos);
        return BlockMatchingType.get(required, current) == BlockMatchingType.CORRECT;
    }

    @Override
    protected Object captureSearchContext() {
        return new PrintSearchContext(
                Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue(),
                Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue(),
                Configs.Print.PRINT_REPLACE.getBooleanValue(),
                List.copyOf(Configs.Print.REPLACEABLE_LIST.getStrings()),
                Configs.Print.PRINT_SKIP.getBooleanValue(),
                List.copyOf(Configs.Print.PRINT_SKIP_LIST.getStrings()),
                Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue(),
                ChainVeinCompat.isAvailable());
    }

    @Override
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block,
            Object searchContext) {
        PrintSearchContext search = (PrintSearchContext) searchContext;
        BlockState required = block.requiredState();
        if (required == null) return null;
        BlockState current = block.currentState();

        if (search.skipEnabled()) {
            for (String rule : search.skipRules()) {
                if (PinYinSearchUtils.matchName(rule, required)) return null;
            }
        }

        BlockMatchingType matching = getSnapshotMatchingType(required, current, search);
        if (matching == BlockMatchingType.CORRECT) return null;
        if (required.isAir() && !search.breakExtra()) return null;

        SchematicBlockContext snapshotContext = new SchematicBlockContext(
                client,
                block.currentView(),
                block.requiredView(),
                block.pos());
        Action snapshotAction = guide.getAction(snapshotContext);
        if (snapshotAction != null) {
            if (snapshotAction instanceof ChainBreakAction
                    && !search.chainVeinAvailable()) {
                return null;
            }
            return transactionKeyFor(snapshotAction, snapshotContext);
        }

        /*
         * 小快照边界外的依赖（例如较长观察者链）可能让快照版 guide 保守返回 null。
         * 缺失方块仍可用目标物品生成准确的放置事务键；消费者会做最终实时校验。
         */
        if (matching == BlockMatchingType.MISSING_BLOCK) {
            return new TransactionKey(
                    TransactionKey.Category.PLACE,
                    required.getBlock().asItem());
        }
        return null;
    }

    private static BlockMatchingType getSnapshotMatchingType(
            BlockState required,
            BlockState current,
            PrintSearchContext search) {
        if (search.iceForWater() && BlockUtils.matchesWaterSourceRequest(required, current)) {
            return BlockMatchingType.CORRECT;
        }
        if (required.equals(current)) return BlockMatchingType.CORRECT;
        if (required.getBlock().equals(current.getBlock())) {
            return BlockUtils.statesEqualIgnoreProperties(required, current)
                    ? BlockMatchingType.CORRECT
                    : BlockMatchingType.ERROR_BLOCK_STATE;
        }
        if (!required.isAir() && current.isAir()) {
            return BlockMatchingType.MISSING_BLOCK;
        }
        if (search.replaceEnabled()) {
            for (String rule : search.replaceRules()) {
                if (!PinYinSearchUtils.matchName(rule, required)
                        && PinYinSearchUtils.matchName(rule, current)) {
                    return BlockMatchingType.MISSING_BLOCK;
                }
            }
        }
        return BlockMatchingType.ERROR_BLOCK;
    }

    @Override
    protected TransactionKey getPreparedTransactionKey(BlockPos pos) {
        return transactionKeyFor(action, ctx);
    }

    private static TransactionKey transactionKeyFor(
            Action action,
            SchematicBlockContext context) {
        if (action instanceof ChainBreakAction) {
            return new TransactionKey(TransactionKey.Category.CHAIN_BREAK, null);
        }
        TransactionKey.Category category = action instanceof ClickAction
                ? TransactionKey.Category.CLICK
                : TransactionKey.Category.PLACE;
        Item[] items = action.getRequiredItems(context.requiredState.getBlock());
        Item primary = items != null && items.length > 0 ? items[0] : null;
        return new TransactionKey(category, primary);
    }

    /**
     * 搜索键只是快照提示；每个坐标在主线程重建真实 Action 后统一执行。
     * 这样快照过时或特殊方块分类变化也不会走错语义。
     */
    @Override
    protected int executeJobTransaction(
            TransactionKey key,
            ArrayDeque<BlockPos> bucket,
            int maxExecs,
            AtomicReference<Boolean> skipIteration) {
        List<BlockPos> readyBreaks = new ArrayList<>(Math.min(maxExecs, 64));
        int attempted = 0;

        while (attempted < maxExecs
                && !bucket.isEmpty()
                && !shouldStopJobTransaction(skipIteration)) {
            BlockPos pos = jobPool.pollFromBucket(key, bucket);
            if (pos == null) break;
            if (!prepareMatchingPooledJob(pos, key)) {
                reportPooledJob(pos, false);
                continue;
            }
            attempted++;
            if (action instanceof ChainBreakAction) {
                readyBreaks.add(pos);
            } else {
                executePreparedPooledJob(pos, key);
            }
        }

        int queued = ChainVeinCompat.queueBreaks(readyBreaks);
        for (int i = 0; i < readyBreaks.size(); i++) {
            BlockPos pos = readyBreaks.get(i);
            boolean accepted = i < queued;
            if (accepted) {
                addHighlight(pos, HighlightType.BREAK);
                setCooldown(pos, ConfigUtils.getBreakCooldown());
            } else {
                addHighlight(pos, HighlightType.FAILED);
            }
            reportPooledJob(pos, accepted);
        }

        if (queued > 0) {
            // 本 tick 不继续依赖这批破坏的动作；后续作业由持续扫描重新产生。
            skipIteration.set(true);
        }
        return attempted;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        // 下落检查
        if (Configs.Placement.FALLING_CHECK.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();

            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_NO_SUPPORT.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                return;
            } else if (level.getBlockState(downPos) != ctx.schematic.getBlockState(downPos)) {
                MessageUtils.setOverlayMessage(
                        I18n.BLOCK_MISMATCH.getName(ctx.getRequiredBlockName().getString()));
                addHighlight(blockPos, HighlightType.FAILED);
                return;
            }
        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) {
            addHighlight(blockPos, HighlightType.FAILED);
            return;
        }
        InventoryUtils.ItemSwitchResult switchResult = InventoryUtils.switchToItemsResult(player, reqItems);
        if (switchResult != InventoryUtils.ItemSwitchResult.READY) {
            if (switchResult.isWaiting()) {
                enterWaiting(blockPos, switchResult.waitingReason());
                skipIteration.set(true);
                return;
            }

            // 所有材料来源都确认无法提供物品：丢弃当前作业，等待持续扫描以后再发现。
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            recordMissingMaterial(reqItems);
            addHighlight(blockPos, HighlightType.FAILED);
            return;
        }
        boolean useShift;
        if (action.getShift() == null) {
            useShift =
                    (Implementation.isInteractive(
                                            level.getBlockState(blockPos.relative(side)).getBlock())
                                    && !(action instanceof ClickAction))
                            || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        } else {
            useShift = action.getShift();
        }
        action.queueAction(blockPos, side, useShift, player);
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        boolean needWait = ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook;
        if (needWait) {
            skipIteration.set(true);
        }
        setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        if (reqItems != null)
            addHighlight(blockPos, HighlightType.PLACE);
        else
            addHighlight(blockPos, HighlightType.ADJUST);
    }

    private void recordMissingMaterial(Item[] reqItems) {
        if (reqItems != null && reqItems.length > 0 && reqItems[0] != null) {
            MissingMaterialTracker.getInstance()
                    .recordMissing(reqItems[0], ctx.getRequiredBlockName());
        }
    }

    private record PrintSearchContext(
            boolean breakExtra,
            boolean breakWrong,
            boolean replaceEnabled,
            List<String> replaceRules,
            boolean skipEnabled,
            List<String> skipRules,
            boolean iceForWater,
            boolean chainVeinAvailable) {
    }

}
