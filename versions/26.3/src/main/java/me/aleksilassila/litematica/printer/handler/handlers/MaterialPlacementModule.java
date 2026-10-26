package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.AreaSelectionType;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.inventory.MaterialPreparation;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;

/** Shared material selection and consumer execution; subclasses define candidate geometry. */
abstract class MaterialPlacementModule extends Module {
    protected final MaterialSettings materialSettings;

    private List<String> materialListCache = new ArrayList<>();
    @Getter
    private Item[] materialItems = new Item[0];

    protected MaterialPlacementModule(String name, ConfigBoolean enabled, ConfigOptionList selectionType,
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
                if (!strings.equals(materialListCache)) {
                    materialListCache = new ArrayList<>(strings);
                    if (strings.isEmpty()) {
                        return;
                    }
                    List<Item> items = new ArrayList<>();
                    for (String itemName : materialListCache) {
                        items.addAll(BuiltInRegistries.ITEM
                                .stream()
                                .filter(item -> PinYinSearchUtils.matchName(itemName, new ItemStack(item)))
                                .toList()
                        );
                    }
                    materialItems = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:  // 手持物品
                if (materialSettings.mode().getOptionListValue() == FillBlockModeType.HANDHELD) {
                    ItemStack heldStack = player.getMainHandItem();
                    if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                        Item heldItem = heldStack.getItem();
                        List<String> blacklist = materialSettings.handheldBlacklist().getStrings();
                        if (!blacklist.isEmpty() && blacklist.stream().anyMatch(s -> PinYinSearchUtils.matchName(s, heldStack))) {
                            materialItems = new Item[0];
                        } else {
                            materialItems = new Item[]{heldItem};
                        }
                    } else {
                        materialItems = new Item[0];
                    }
                }
                break;
        }
    }

    @Override
    protected boolean canIterate() {
        return materialItems.length > 0;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (canProcessPos(blockPos)) {
            BlockPos placementPos = getPlacementPos(blockPos);
            MaterialPreparation.Result switchResult =
                    MaterialPreparation.prepare(player, this.materialItems);
            if (switchResult != MaterialPreparation.Result.READY) {
                if (switchResult.isWaiting()) {
                    enterWaiting(blockPos, switchResult.waitingReason());
                    skipIteration.set(true);
                    return;
                }
                if (this.materialItems != null && this.materialItems.length > 0 && this.materialItems[0] != null) {
                    MissingMaterialTracker.getInstance().recordMissing(this.materialItems[0],
                            this.materialItems[0].getName(this.materialItems[0].getDefaultInstance())
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

    protected abstract Action createPlacementAction(BlockPos placementPos);

    protected record MaterialSettings(ConfigOptionList mode, ConfigStringList blocks,
                                      ConfigStringList handheldBlacklist) {
    }
}
