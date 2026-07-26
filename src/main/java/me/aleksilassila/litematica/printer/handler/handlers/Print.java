package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
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

    // 等待水产生队列
    private final List<BlockPos> watingForWaterList = new ArrayList<>();

    public Print() {
        super(NAME, Configs.Print.ENABLED, Configs.Print.PRINT_SELECTION_TYPE, true);
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
    protected boolean canIterate() {
        return !ChainVeinCompat.hasPendingMineJobs();
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
    protected TransactionKey getTransactionKey(BlockPos pos) {
        // canProcessPos 已设置 this.action / this.ctx，据此计算事务签名。
        if (isIceBreakAndWaitJob()) {
            return new TransactionKey(TransactionKey.Category.ICE_WATER, null);
        }
        if (action instanceof ChainBreakAction) {
            return new TransactionKey(TransactionKey.Category.CHAIN_BREAK, null);
        }
        TransactionKey.Category category = action instanceof ClickAction
                ? TransactionKey.Category.CLICK
                : TransactionKey.Category.PLACE;
        Item[] items = action.getRequiredItems(ctx.requiredState.getBlock());
        Item primary = (items != null && items.length > 0) ? items[0] : null;
        return new TransactionKey(category, primary);
    }

    @Override
    protected int executeJobTransaction(TransactionKey key, ArrayDeque<BlockPos> bucket,
                                        int maxExecs,
                                        AtomicReference<Boolean> skipIteration) {
        if (key.category() == TransactionKey.Category.CHAIN_BREAK) {
            return executeBreakTransaction(bucket, maxExecs, skipIteration);
        }
        return executeBucketDefault(bucket, maxExecs, skipIteration);
    }

    private int executeBreakTransaction(ArrayDeque<BlockPos> bucket, int maxExecs,
                                        AtomicReference<Boolean> skipIteration) {
        List<BlockPos> readyBreaks = new ArrayList<>(Math.min(maxExecs, 64));

        while (readyBreaks.size() < maxExecs
                && !bucket.isEmpty()
                && !shouldStopJobTransaction(skipIteration)) {
            BlockPos pos = bucket.peek();
            if (!preparePooledJob(pos) || !(action instanceof ChainBreakAction)) {
                jobPool.pollFromBucket(bucket);
                reportPooledJob(pos, false);
                continue;
            }
            jobPool.pollFromBucket(bucket);
            readyBreaks.add(pos);
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
            // 整批破坏是后续放置/调整的前置条件，等待客户端世界状态确认。
            skipIteration.set(true);
        }
        return readyBreaks.size();
    }

    private boolean isIceBreakAndWaitJob() {
        return Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.isNeedsWater(ctx.requiredState)
                && ctx.currentState.getBlock() instanceof IceBlock;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
            && BlockUtils.isNeedsWater(ctx.requiredState)) {
            // 破冰后等水生成
            if (watingForWaterList.contains(blockPos)) {
                if (BlockUtils.isPureWaterSource(ctx.currentState))
                    watingForWaterList.remove(blockPos);
                else {
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    return;
                }
            }
            // 单步阻塞式破冰放水：目标是水且当前是冰才触发
            if (ctx.currentState.getBlock() instanceof IceBlock) {
                if (ChainVeinCompat.queueBreaks(List.of(blockPos)) > 0) {
                    watingForWaterList.add(blockPos);
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                } else {
                    setCooldown(blockPos, ConfigUtils.getBreakCooldown());
                    addHighlight(blockPos, HighlightType.FAILED);
                }
                return;
            }
        }
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
            if (switchResult == InventoryUtils.ItemSwitchResult.WAITING) {
                enterWaiting(blockPos);
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
        if (needWait || hitModifier != null) {
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

}
