package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

public class ConfigUtils {
    @NotNull
    public static final Minecraft client = Minecraft.getInstance();

    public static boolean isPrinterEnable() {
        return Configs.Core.WORK_SWITCH.getBooleanValue();
    }

    public static boolean isPrintEnabled() {
        return Configs.Print.ENABLED.getBooleanValue();
    }

    public static boolean isFillEnabled() {
        return Configs.Fill.ENABLED.getBooleanValue();
    }

    public static boolean isFluidEnabled() {
        return Configs.Fluid.ENABLED.getBooleanValue();
    }

    public static boolean isBedrockEnabled() {
        return Configs.Bedrock.ENABLED.getBooleanValue();
    }

    public static int getPlaceCooldown() {
        return Configs.Placement.PLACE_COOLDOWN.getIntegerValue();
    }

    public static int getBreakCooldown() {
        return Configs.Print.BREAK_COOLDOWN.getIntegerValue();
    }

    public static int getWorkRange() {
        return (int) Configs.Core.WORK_RANGE.getDoubleValue();
    }

    public static double getEffectiveRange() {
        double configRange = Configs.Core.WORK_RANGE.getDoubleValue();
        if (configRange <= 0) {
            return PlayerUtils.getInteractionRange(4.5);
        }
        return configRange;
    }

    public static Direction getFillModeFacing() {
        if (Configs.Fill.FILL_BLOCK_FACING.getOptionListValue() instanceof FillModeFacingType fillModeFacingType) {
            return switch (fillModeFacingType) {
                case DOWN -> Direction.DOWN;
                case UP -> Direction.UP;
                case WEST -> Direction.WEST;
                case EAST -> Direction.EAST;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                default -> null;
            };
        }
        return null;
    }
}
