package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.utils.RenderUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ScanState;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.GuiBlockInfo;
import me.aleksilassila.litematica.printer.handler.handlers.GUI;
import me.aleksilassila.litematica.printer.printer.RegionTracker;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

//#if MC <= 11904
//$$import com.mojang.blaze3d.vertex.PoseStack;
//#elseif MC > 12006
import net.minecraft.client.DeltaTracker;
//#endif

/**
 * HUD渲染Mixin，负责打印器调试信息和进度条的绘制
 */
@Mixin(Gui.class)
public abstract class MixinGui {
    @Unique
    private static final int DEBUG_PADDING = 4;
    @Unique
    private static final int DEBUG_LINE_HEIGHT = 12;
    @Unique
    private static final int MIN_COLUMN_WIDTH = 120;
    @Unique
    private static final int SIDE_MARGIN = 10;
    @Unique
    private static final int COLUMN_SPACING = DEBUG_PADDING * 3;
    @Unique
    private static final int COMMON_INFO_OFFSET_Y = 10;

    @Unique
    private static String booleanToColoredString(boolean value) {
        return value ? "§atrue" : "§cfalse";
    }

    @Unique
    private static String formatAlignedNumber(int current, int total) {
        int totalDigits = total == 0 ? 1 : String.valueOf(total).length();
        DecimalFormat formatter = new DecimalFormat(String.format("%0" + totalDigits + "d", 0));
        return formatter.format(current);
    }

    @Unique
    private List<String> buildHandlerDebugLines(Module module, GuiBlockInfo guiInfo) {
        List<String> lines = new ArrayList<>();
        lines.add("处理类型: " + module.getId());
        ScanState state = module.getScanState();
        String stateColor = state == ScanState.LAZY ? "§b" : state == ScanState.PARTIAL ? "§e" : "§a";
        lines.add("扫描状态: " + stateColor + state);
        lines.add("当前位置: " + guiInfo.pos.toShortString());
        if (guiInfo.requiredState != null) {
            lines.add("投影方块: " + guiInfo.requiredState.getBlock().getName().getString());
        }
        lines.add("当前方块: " + guiInfo.currentState.getBlock().getName().getString());
        lines.add("交互范围: " + booleanToColoredString(guiInfo.interacted));
        lines.add("选区类型: " + booleanToColoredString(guiInfo.posInSelectionRange));
        lines.add("已经执行: " + booleanToColoredString(guiInfo.execute));

        int renderIndex = module.getRenderIndex();
        int queueSize = module.getGuiQueueSize();
        lines.add("同刻迭代(GUI): " + formatAlignedNumber(renderIndex, queueSize) + "/" + queueSize);

        return lines;
    }

    @Unique
    private void drawDebugLine(String text, int x, int y) {
        RenderUtils.drawString(text, x, y, new Color(0, 255, 255, 255), true);
    }

    // @formatter:off
    //#if MC>= 260200
    //#elseif MC >= 260100
    //$$ @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    //#else
    @Inject(method = "renderItemHotbar", at = @At("TAIL"))
    //#endif

    //#if MC > 12006
    private void hookRenderItemHotbar(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
    //#elseif MC >= 12006
    //$$ private void hookRenderItemHotbar(GuiGraphics guiGraphics, float f, CallbackInfo ci) {
    //#elseif MC > 11904 && MC < 12006
    //$$ private void hookRenderItemHotbar(float f, GuiGraphics guiGraphics, CallbackInfo ci) {
    //#else
    //$$ private void hookRenderItemHotbar(float f, PoseStack poseStack, CallbackInfo ci) {
    //#endif
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator() || !ConfigUtils.isPrinterEnable()) {
            return;
        }

        float scaledWidth = mc.getWindow().getGuiScaledWidth();
        float scaledHeight = mc.getWindow().getGuiScaledHeight();

        //#if MC > 11904
        RenderUtils.initGuiGraphics(guiGraphics);
        //#else
        //$$ RenderUtils.initMatrix(poseStack);
        //#endif

        if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
            drawDebugInfo(scaledWidth, scaledHeight);
        }

        if (Configs.Core.RENDER_HUD.getBooleanValue()) {
            drawHudInfo(scaledWidth, scaledHeight);
        }
    }
    // @formatter:on

    @Unique
    private void drawDebugInfo(float scaledWidth, float scaledHeight) {
        Minecraft mc = Minecraft.getInstance();
        List<Module> validModules = new ArrayList<>();
        int globalMaxTextWidth = MIN_COLUMN_WIDTH;

        for (Module module : ModuleManager.VALUES) {
            GuiBlockInfo guiInfo = module.nextGuiInfo();
            if (guiInfo == null) continue;

            validModules.add(module);
            List<String> lines = buildHandlerDebugLines(module, guiInfo);
            for (String line : lines) {
                String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
                globalMaxTextWidth = Math.max(globalMaxTextWidth, mc.font.width(cleanLine));
            }
        }

        if (validModules.isEmpty()) return;

        int commonInfoBottomY = drawCommonDebugInfo(SIDE_MARGIN, SIDE_MARGIN);

        int columnWidth = globalMaxTextWidth + DEBUG_PADDING * 2;
        int maxColumnsPerSide = calculateMaxColumnsPerSide(scaledWidth, columnWidth);
        int availableHeight = (int) (scaledHeight - commonInfoBottomY - COMMON_INFO_OFFSET_Y - SIDE_MARGIN);

        int drawnModules = drawModulePanels(
                validModules, 0,
                SIDE_MARGIN, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                columnWidth, maxColumnsPerSide, availableHeight,
                scaledHeight
        );

        if (drawnModules < validModules.size()) {
            int rightStartX = (int) (scaledWidth - SIDE_MARGIN - columnWidth);
            drawModulePanels(
                    validModules, drawnModules,
                    rightStartX, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                    columnWidth, maxColumnsPerSide, availableHeight,
                    scaledHeight
            );
        }
    }

    @Unique
    private int calculateMaxColumnsPerSide(float scaledWidth, int columnWidth) {
        float centerAreaWidth = scaledWidth * 0.5f;
        float sideAvailableWidth = (scaledWidth - centerAreaWidth) / 2 - SIDE_MARGIN * 2;

        int maxColumns = Math.max(1, (int) (sideAvailableWidth / (columnWidth + COLUMN_SPACING)));
        return Math.min(maxColumns, 3);
    }

    @Unique
    private int drawModulePanels(List<Module> modules, int startIndex,
                                  int startX, int startY, int columnWidth,
                                  int maxColumns, int availableHeight, float scaledHeight) {
        int drawnCount = 0;
        int currentColumn = 0;
        int currentX = startX;
        int currentY = startY;

        for (int i = startIndex; i < modules.size(); i++) {
            Module module = modules.get(i);
            GuiBlockInfo guiInfo = module.nextGuiInfo();
            if (guiInfo == null) continue;

            List<String> debugLines = buildHandlerDebugLines(module, guiInfo);
            int panelHeight = debugLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

            if (currentColumn >= maxColumns) {
                currentColumn = 0;
                currentX = startX;
                currentY += panelHeight + DEBUG_PADDING * 2;

                if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                    break;
                }
            }

            RenderUtils.fill(
                    currentX, currentY,
                    currentX + columnWidth, currentY + panelHeight,
                    new Color(0, 0, 0, 50)
            );

            int lineY = currentY + DEBUG_PADDING;
            for (String line : debugLines) {
                drawDebugLine(line, currentX + DEBUG_PADDING, lineY);
                lineY += DEBUG_LINE_HEIGHT;
            }

            drawnCount++;
            currentColumn++;
            currentX += columnWidth + COLUMN_SPACING;

            if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                break;
            }
        }

        return drawnCount;
    }

    @Unique
    private int drawCommonDebugInfo(int startX, int startY) {
        List<String> commonLines = new ArrayList<>();
        commonLines.add("全局Tick: " + ModuleManager.getCurrentHandlerTime());
        commonLines.add("活跃模块数: " + ModuleManager.VALUES.size());

        boolean allLazy = true;
        ScanState dominantState = ScanState.FULL;
        for (Module m : ModuleManager.VALUES) {
            ScanState s = m.getScanState();
            if (s != ScanState.LAZY) allLazy = false;
            dominantState = s;
        }
        StringBuilder scanLine = new StringBuilder("扫描模式: ");
        if (allLazy) {
            scanLine.append("§bLAZY");
        } else {
            scanLine.append(dominantState == ScanState.PARTIAL ? "§ePARTIAL" : "§aFULL");
        }
        int dirtyTotal = RegionTracker.INSTANCE.getDirtyCount();
        if (dirtyTotal > 0) {
            scanLine.append("§r | 脏区域: §c").append(dirtyTotal);
        }
        int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        if (lazyThreshold > 0) {
            scanLine.append("§r | 惰性阈值: ").append(lazyThreshold).append("tick");
        } else {
            scanLine.append("§r | §7惰性已禁用");
        }
        commonLines.add(scanLine.toString());

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = 0;
        for (String line : commonLines) {
            String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
            maxWidth = Math.max(maxWidth, mc.font.width(cleanLine));
        }

        int bgWidth = maxWidth + DEBUG_PADDING * 2;
        int bgHeight = commonLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

        RenderUtils.fill(
                startX, startY,
                startX + bgWidth, startY + bgHeight,
                new Color(0, 0, 0, 50)
        );

        int lineY = startY + DEBUG_PADDING;
        for (String line : commonLines) {
            drawDebugLine(line, startX + DEBUG_PADDING, lineY);
            lineY += DEBUG_LINE_HEIGHT;
        }

        return startY + bgHeight;
    }

    @Unique
    private void drawHudInfo(float scaledWidth, float scaledHeight) {
        int centerX = (int) (scaledWidth / 2);
        int centerY = (int) (scaledHeight / 2);
        GUI guiHandler = ModuleManager.GUI;

        if (Configs.Core.LAG_CHECK.getBooleanValue() && ModuleManager.getPacketTick() > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            RenderUtils.drawString("延迟过大，已暂停运行", centerX, centerY - 22, Color.ORANGE, true, true);
        }

        double progress = guiHandler.getTotalProgress().getProgress();
        RenderUtils.drawString((int) (progress * 100) + "%", centerX, centerY + 22, Color.WHITE, true, true);
        drawProgressBar(centerX, centerY + 36, 40, 6, progress, new Color(0, 0, 0, 150), new Color(0, 255, 0, 255));

        boolean anyLazy = false;
        boolean anyPartial = false;
        for (Module m : ModuleManager.VALUES) {
            ScanState s = m.getScanState();
            if (s == ScanState.LAZY) anyLazy = true;
            else if (s == ScanState.PARTIAL) anyPartial = true;
        }
        String scanLabel;
        Color scanColor;
        if (anyLazy && !anyPartial) {
            scanLabel = "扫描: LAZY";
            scanColor = new Color(100, 200, 255);
        } else if (anyPartial) {
            scanLabel = "扫描: PARTIAL";
            scanColor = new Color(255, 200, 50);
        } else {
            scanLabel = "扫描: FULL";
            scanColor = new Color(100, 255, 100);
        }
        RenderUtils.drawString(scanLabel, centerX, centerY + 52, scanColor, true, true);

        int infoY = centerY + 64;

        HashSet<String> modeNames = new HashSet<>();
        for (Module module : ModuleManager.VALUES) {
            if (module.getId().equals(GUI.NAME) || module.getEnableConfig() == null || !module.getEnableConfig().getBooleanValue()) {
                continue;
            }
            modeNames.add(module.getEnableConfig().getPrettyName());
        }
        RenderUtils.drawString(String.join(", ", modeNames), centerX, infoY, Color.WHITE, true, true);
    }

    @Unique
    private void drawProgressBar(int x, int y, int barWidth, int barHeight, double progress, Color bgColor, Color fgColor) {
        double clampedProgress = Math.max(0.0, Math.min(1.0, progress));
        int barXStart = x - (barWidth / 2);
        int barXEnd = x + (barWidth / 2);
        int barYEnd = y + barHeight;
        int filledWidth = (int) (clampedProgress * barWidth);

        RenderUtils.fill(barXStart, y, barXEnd, barYEnd, bgColor);
        if (filledWidth > 0) {
            RenderUtils.fill(barXStart, y, barXStart + filledWidth, barYEnd, fgColor);
        }
    }
}