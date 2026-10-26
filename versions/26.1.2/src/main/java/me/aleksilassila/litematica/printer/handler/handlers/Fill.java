package me.aleksilassila.litematica.printer.handler.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.handler.search.SearchSnapshots;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public class Fill extends MaterialPlacementModule {
    public final static String NAME = "fill";
    // 可替换列表缓存，避免每位置都做拼音转换
    private List<String> replaceableCache = new ArrayList<>();
    private final Map<Block, Boolean> replaceableMatchCache = new HashMap<>();

    public Fill() {
        super(NAME, Configs.Fill.ENABLED, Configs.Fill.FILL_SELECTION_TYPE,
                new MaterialSettings(Configs.Fill.FILL_BLOCK_MODE,
                        Configs.Fill.FILL_BLOCK_LIST, Configs.Fill.FILL_HANDHELD_BLACKLIST));
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
            SearchSnapshots.SearchBlockSnapshot block,
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

    protected Action createPlacementAction(BlockPos placementPos) {
        if (ConfigUtils.getFillModeFacing() != null) {
            return new Action()
                    .setLookDirection(ConfigUtils.getFillModeFacing().getOpposite())
                    .queueAction(placementPos, ConfigUtils.getFillModeFacing(), false, player);
        }
        return new Action().queueAction(placementPos, getPlayerPlacementDirection(), false, player);
    }

    private record FillSearchContext(List<String> replaceRules) {
    }
}
