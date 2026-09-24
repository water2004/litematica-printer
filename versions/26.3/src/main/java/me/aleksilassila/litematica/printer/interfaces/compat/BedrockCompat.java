package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Optional adapters for bunnyi116's Bedrock Miner ({@code bedrockminer}),
 * BlockMiner ({@code blockminer}), and the original Fabric-Bedrock-Miner
 * ({@code bedrock-miner}). These are distinct projects with distinct APIs.
 * <p>
 * Resolves the installed mod's client API reflectively so the printer also
 * loads when no bedrock-breaking mod is installed.
 */
public class BedrockCompat {
    private static boolean resolved = false;
    @Nullable private static Kind kind;

    private enum Kind {
        BLOCK_MINER, BEDROCK_MINER, FABRIC_BEDROCK_MINER
    }

    @Nullable private static Object minerInstance;
    @Nullable private static Method addBlockTaskMethod;
    @Nullable private static Method clearTaskMethod;
    @Nullable private static Method isRunningMethod;
    @Nullable private static Method setRunningMethod;
    @Nullable private static Method isFeatureEnableMethod;
    @Nullable private static Method setFeatureEnableMethod;
    @Nullable private static Method enableMethod;
    @Nullable private static Method disableMethod;

    private static boolean isResolved() {
        if (!resolved) resolve();
        return minerInstance != null;
    }

    /**
     * Detect the available miner mod and resolve reflection handles.
     */
    private static void resolve() {
        if (resolved) return;
        resolved = true;

        if (ModUtils.isBlockMinerLoaded()) {
            resolveBlockMiner();
            if (minerInstance != null) return;
        }
        if (ModUtils.isBedrockMinerLoaded()) {
            resolveBedrockMiner();
            if (minerInstance != null) return;
        }
        if (ModUtils.isFabricBedrockMinerLoaded()) {
            resolveFabricBedrockMiner();
        }
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static void resolveBlockMiner() {
        try {
            Class<?> modClass = Class.forName("me.z7087.blockminer.BlockMinerMod");
            Method getInstance = modClass.getDeclaredMethod("getInstance");
            Object modContainer = getInstance.invoke(null);
            Method getTaskManager = modContainer.getClass().getDeclaredMethod("getTaskManager");
            minerInstance = getTaskManager.invoke(modContainer);

            Class<?> tmClass = Class.forName("me.z7087.blockminer.task.TaskManager");
            addBlockTaskMethod = tmClass.getDeclaredMethod("handleAttackBlock", BlockPos.class);
            clearTaskMethod    = tmClass.getDeclaredMethod("clearTasks");
            clearTaskMethod.setAccessible(true);
            isRunningMethod    = tmClass.getDeclaredMethod("isEnabled");
            Method enable      = tmClass.getDeclaredMethod("onEnable");
            Method disable     = tmClass.getDeclaredMethod("onDisable");
            enable.setAccessible(true);
            disable.setAccessible(true);
            enableMethod = enable;
            disableMethod = disable;
            kind = Kind.BLOCK_MINER;
        } catch (ReflectiveOperationException | LinkageError error) {
            Reference.LOGGER.warn("Cannot initialize blockminer integration", error);
            clear();
        }
    }

    private static void resolveBedrockMiner() {
        try {
            Class<?> tmClass = Class.forName("com.github.bunnyi116.bedrockminer.task.TaskManager");
            Method getInstance = tmClass.getDeclaredMethod("getInstance");
            minerInstance = getInstance.invoke(null);

            addBlockTaskMethod     = tmClass.getDeclaredMethod("addBlockTask", ClientLevel.class, BlockPos.class, Block.class);
            clearTaskMethod        = tmClass.getDeclaredMethod("clearTask");
            isRunningMethod        = tmClass.getDeclaredMethod("isRunning");
            setRunningMethod       = tmClass.getDeclaredMethod("setRunning", boolean.class, boolean.class);
            isFeatureEnableMethod  = tmClass.getDeclaredMethod("isBedrockMinerFeatureEnable");
            setFeatureEnableMethod = tmClass.getDeclaredMethod("setBedrockMinerFeatureEnable", boolean.class);
            kind = Kind.BEDROCK_MINER;
        } catch (ReflectiveOperationException | LinkageError error) {
            Reference.LOGGER.warn("Cannot initialize bedrockminer integration", error);
            clear();
        }
    }

    private static void resolveFabricBedrockMiner() {
        try {
            Class<?> controller = Class.forName(
                    "com.github.lxyan2333.bedrockminer.client.breaking.BreakingFlowController");
            minerInstance = controller.getField("INSTANCE").get(null);
            addBlockTaskMethod = controller.getMethod("tryEnqueueBlock", BlockPos.class);
            clearTaskMethod = controller.getMethod("cancelAllFlows");
            isRunningMethod = controller.getMethod("getEnabled");
            enableMethod = controller.getMethod("enable");
            disableMethod = controller.getMethod("disable");
            kind = Kind.FABRIC_BEDROCK_MINER;
        } catch (ReflectiveOperationException | LinkageError error) {
            Reference.LOGGER.warn("Cannot initialize fabric-bedrock-miner integration", error);
            clear();
        }
    }

    private static void clear() {
        kind = null;
        minerInstance = null;
        addBlockTaskMethod = null;
        clearTaskMethod = null;
        isRunningMethod = null;
        setRunningMethod = null;
        isFeatureEnableMethod = null;
        setFeatureEnableMethod = null;
        enableMethod = null;
        disableMethod = null;
    }

    /** Whether a supported miner was loaded and its API was resolved. */
    public static boolean isAvailable() {
        return isResolved();
    }

    /** Add a block position to the miner's break queue. */
    public static void addToBreakList(BlockPos pos, ClientLevel world) {
        if (!isResolved()) return;
        try {
            if (kind != Kind.BEDROCK_MINER) {
                addBlockTaskMethod.invoke(minerInstance, pos);
            } else {
                Block block = world.getBlockState(pos).getBlock();
                addBlockTaskMethod.invoke(minerInstance, world, pos, block);
            }
        } catch (ReflectiveOperationException error) {
            Reference.LOGGER.warn("Cannot submit bedrock-breaking task", error);
        }
    }

    /** Clear all pending break tasks. */
    public static void clearTasks() {
        if (!isResolved()) return;
        try {
            clearTaskMethod.invoke(minerInstance);
        } catch (ReflectiveOperationException error) {
            Reference.LOGGER.warn("Cannot clear bedrock-breaking tasks", error);
        }
    }

    /** Check whether the miner is currently running. */
    public static boolean isWorking() {
        if (!isResolved()) return false;
        try {
            return (boolean) isRunningMethod.invoke(minerInstance);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Start or stop the miner. */
    public static void setWorking(boolean running) {
        setWorking(running, false);
    }

    /** Start or stop the miner, with optional message. */
    public static void setWorking(boolean running, boolean showMessage) {
        if (!isResolved()) return;
        try {
            if (kind != Kind.BEDROCK_MINER) {
                (running ? enableMethod : disableMethod).invoke(minerInstance);
            } else {
                setRunningMethod.invoke(minerInstance, running, showMessage);
                if (!running) clearTasks();
            }
        } catch (ReflectiveOperationException error) {
            Reference.LOGGER.warn("Cannot change bedrock miner running state", error);
        }
    }

    /** BedrockMiner-specific: check whether its feature toggle is enabled. */
    public static boolean isFeatureEnable() {
        if (!isResolved() || isFeatureEnableMethod == null) return false;
        try {
            return (boolean) isFeatureEnableMethod.invoke(minerInstance);
        } catch (Exception e) {
            return true;
        }
    }

    /** BedrockMiner-specific: toggle its feature switch. */
    public static void setFeatureEnable(boolean enabled) {
        if (!isResolved() || setFeatureEnableMethod == null) return;
        try {
            setFeatureEnableMethod.invoke(minerInstance, enabled);
        } catch (ReflectiveOperationException error) {
            Reference.LOGGER.warn("Cannot change bedrock miner feature state", error);
        }
    }
}
