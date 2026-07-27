package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class FluidRemoval extends Module {
    public final static String NAME = "fluid";

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();

    private List<String> fluidBlocks = new ArrayList<>();
    private List<net.minecraft.world.level.material.Fluid> fluids = List.of(new net.minecraft.world.level.material.Fluid[0]);

    public FluidRemoval() {
        super(NAME, Configs.Fluid.ENABLED, Configs.Fluid.FLUID_SELECTION_TYPE, true);
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
        // 填充方块
        List<String> fileBlocks = Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings();
        if (!fileBlocks.equals(fillBlocks)) {
            fillBlocks = new ArrayList<>(fileBlocks);
            if (!fileBlocks.isEmpty()) {
                fillItems = new ArrayList<>();
                for (String itemName : fillBlocks) {
                    List<Item> list = BuiltInRegistries.ITEM.stream().filter(item -> PinYinSearchUtils.matchName(itemName, new ItemStack(item))).toList();
                    fillItems.addAll(list);
                }
            }
        }
        // 流体方块
        List<String> fluidBlocks = Configs.Fluid.FLUID_LIST.getStrings();
        if (!fluidBlocks.equals(this.fluidBlocks)) {
            this.fluidBlocks = new ArrayList<>(fluidBlocks);
            if (!fluidBlocks.isEmpty()) {
                fluids = new ArrayList<>();
                for (String itemName : this.fluidBlocks) {
                    List<net.minecraft.world.level.material.Fluid> list = BuiltInRegistries.FLUID.stream().filter(item -> PinYinSearchUtils.matchName(itemName, item.defaultFluidState().createLegacyBlock())).toList();
                    fluids.addAll(list);
                }
            }
        }
    }

    @Override
    protected boolean canIterate() {
        return !fillItems.isEmpty() && !fluidBlocks.isEmpty();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        FluidState fluidState = level.getBlockState(pos).getFluidState();
        return fluids.contains(fluidState.getType());
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        FluidState fluidState = level.getBlockState(pos).getFluidState();
        return !fluids.contains(fluidState.getType());
    }

    @Override
    protected Object captureSearchContext() {
        return List.copyOf(fluids);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block,
            Object searchContext) {
        List<net.minecraft.world.level.material.Fluid> searchedFluids =
                (List<net.minecraft.world.level.material.Fluid>) searchContext;
        return searchedFluids.contains(block.currentState().getFluidState().getType())
                ? TransactionKey.HOMOGENEOUS : null;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (fluids.contains(fluidState.getType())) {
            if (!Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() && !fluidState.isSource()) {
                return;
            }
            InventoryUtils.ItemSwitchResult switchResult =
                    InventoryUtils.switchToItemsResult(player, fillItems.toArray(new Item[0]));
            if (switchResult != InventoryUtils.ItemSwitchResult.READY) {
                if (switchResult == InventoryUtils.ItemSwitchResult.WAITING) {
                    enterWaiting(blockPos);
                    skipIteration.set(true);
                    return;
                }
                if (!fillItems.isEmpty() && fillItems.get(0) != null) {
                    MissingMaterialTracker.getInstance().recordMissing(fillItems.get(0),
                            fillItems.get(0).getName(fillItems.get(0).getDefaultInstance())
                    );
                }
                return;
            }
            Action action = new Action().queueAction(blockPos, Direction.UP, false, player);
            addHighlight(blockPos, HighlightType.PLACE);
            ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
            if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook) {
                skipIteration.set(true);
            } else {
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            }
        }
    }
}
