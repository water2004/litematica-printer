package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ChainVein 的可选客户端入口。
 *
 * <p>外层只引用 Minecraft/Fabric 类；只有确认客户端安装了 ChainVein 后，
 * 才会加载缓存解析其公共 API 的内部桥接。</p>
 */
public final class ChainVeinCompat {
    private static final Minecraft MC = Minecraft.getInstance();

    private ChainVeinCompat() {
    }

    public static boolean isAvailable() {
        return ModUtils.isChainVeinLoaded();
    }

    public static boolean canBreakBlock(BlockPos pos) {
        if (!isAvailable() || MC.level == null || MC.player == null || MC.gameMode == null || pos == null) {
            return false;
        }

        BlockState state = MC.level.getBlockState(pos);
        return MC.level.getWorldBorder().isWithinBounds(pos)
                && !state.isAir()
                && !(state.getBlock() instanceof LiquidBlock)
                && state.getDestroySpeed(MC.level, pos) >= 0.0F
                && !MC.player.blockActionRestricted(MC.level, pos, MC.gameMode.getPlayerMode());
    }

    public static int queueBreaks(Collection<BlockPos> positions) {
        if (!isAvailable() || positions == null || positions.isEmpty()) return 0;

        List<BlockPos> validPositions = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (canBreakBlock(pos)) validPositions.add(pos.immutable());
        }
        if (validPositions.isEmpty()) return 0;
        return Loaded.queueBreaks(validPositions);
    }

    private static final class Loaded {
        private static final Method QUEUE_MINE_JOBS = resolveQueueMethod();

        private static Method resolveQueueMethod() {
            try {
                return Class.forName("org.edtp.chainveinfabric.client.api.ChainVeinClientApi")
                        .getMethod("queueMineJobs", Minecraft.class, Collection.class);
            } catch (ReflectiveOperationException exception) {
                Reference.LOGGER.warn("ChainVein client API is unavailable", exception);
                return null;
            }
        }

        private static int queueBreaks(List<BlockPos> positions) {
            if (QUEUE_MINE_JOBS == null) return 0;
            try {
                return ((Number) QUEUE_MINE_JOBS.invoke(null, MC, positions)).intValue();
            } catch (ReflectiveOperationException exception) {
                Reference.LOGGER.warn("Could not queue ChainVein mining jobs", exception);
                return 0;
            }
        }
    }
}
