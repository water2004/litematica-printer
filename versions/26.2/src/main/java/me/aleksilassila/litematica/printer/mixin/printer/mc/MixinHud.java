package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.handler.GuiBlockInfo;
import me.aleksilassila.litematica.printer.handler.handlers.GUI;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.RenderUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Mixin(Hud.class)
public abstract class MixinHud {
    @Unique
    private static final int DEBUG_PADDING = 4;
    @Unique
    private static final int DEBUG_LINE_HEIGHT = 12;
    @Unique
    private static final int MIN_COLUMN_WIDTH = 120;
    @Unique
    private static final int SIDE_MARGIN = 10; // 屏幕左右边距
    @Unique
    private static final int COLUMN_SPACING = DEBUG_PADDING * 3; // 列之间的间距
    @Unique
    private static final int COMMON_INFO_OFFSET_Y = 10;

    @Unique
    private static String booleanToColoredString(boolean value) {
        return value ? "§atrue" : "§cfalse";
    }

    @Unique
    private List<String> buildHandlerDebugLines(Module handler, GuiBlockInfo guiInfo) {
        List<String> lines = new ArrayList<>();
        lines.add("处理类型: " + handler.getId());
        lines.add("当前位置: " + guiInfo.pos.toShortString());
        if (guiInfo.requiredState != null) {
            lines.add("投影方块: " + guiInfo.requiredState.getBlock().getName().getString());
        }
        lines.add("当前方块: " + guiInfo.currentState.getBlock().getName().getString());
        lines.add("交互范围: " + booleanToColoredString(guiInfo.interacted));
        lines.add("选区类型: " + booleanToColoredString(guiInfo.posInSelectionRange));
        lines.add("已经执行: " + booleanToColoredString(guiInfo.execute));

        return lines;
    }

    @Unique
    private void drawDebugLine(String text, int x, int y) {
        RenderUtils.drawString(text, x, y, new Color(0, 255, 255, 255), true);
    }

    // @formatter:off
    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))

    private void hookRenderItemHotbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator() || !ConfigUtils.isPrinterEnable()) {
            return;
        }

        float scaledWidth = mc.getWindow().getGuiScaledWidth();
        float scaledHeight = mc.getWindow().getGuiScaledHeight();

        // 初始化渲染矩阵
        RenderUtils.initGuiGraphics(graphics);

        if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
            drawDebugInfo(scaledWidth, scaledHeight);
        }

        if (Configs.Core.RENDER_HUD.getBooleanValue()) {
            drawHudInfo(scaledWidth, scaledHeight);
        }
    }
    // @formatter:on

    // 调试信息绘制
    @Unique
    private void drawDebugInfo(float scaledWidth, float scaledHeight) {
        Minecraft mc = Minecraft.getInstance();
        List<Module> validHandlers = new ArrayList<>();
        Map<Module, GuiBlockInfo> guiInfoMap = new HashMap<>();
        int globalMaxTextWidth = MIN_COLUMN_WIDTH;

        for (Module handler : ModuleManager.VALUES) {
            GuiBlockInfo guiInfo = handler.getGuiInfo();
            if (guiInfo == null) continue;

            validHandlers.add(handler);
            guiInfoMap.put(handler, guiInfo);
            List<String> lines = buildHandlerDebugLines(handler, guiInfo);
            for (String line : lines) {
                String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
                globalMaxTextWidth = Math.max(globalMaxTextWidth, mc.font.width(cleanLine));
            }
        }

        if (validHandlers.isEmpty()) return;

        int commonInfoBottomY = drawCommonDebugInfo(SIDE_MARGIN, SIDE_MARGIN);

        int columnWidth = globalMaxTextWidth + DEBUG_PADDING * 2;
        int maxColumnsPerSide = calculateMaxColumnsPerSide(scaledWidth, columnWidth);

        int drawnHandlers = drawHandlerPanels(
                validHandlers, guiInfoMap, 0,
                SIDE_MARGIN, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                columnWidth, maxColumnsPerSide,
                scaledHeight
        );

        if (drawnHandlers < validHandlers.size()) {
            int rightStartX = (int) (scaledWidth - SIDE_MARGIN - columnWidth);
            drawHandlerPanels(
                    validHandlers, guiInfoMap, drawnHandlers,
                    rightStartX, commonInfoBottomY + COMMON_INFO_OFFSET_Y,
                    columnWidth, maxColumnsPerSide,
                    scaledHeight
            );
        }
    }

    /**
     * 计算单侧边最多能显示的列数（根据屏幕宽度动态调整）
     */
    @Unique
    private int calculateMaxColumnsPerSide(float scaledWidth, int columnWidth) {
        // 屏幕中间预留核心游戏区域（占总宽度的50%）
        float centerAreaWidth = scaledWidth * 0.5f;
        float sideAvailableWidth = (scaledWidth - centerAreaWidth) / 2 - SIDE_MARGIN * 2;

        // 计算单侧边能容纳的列数（至少1列）
        int maxColumns = Math.max(1, (int) (sideAvailableWidth / (columnWidth + COLUMN_SPACING)));
        return Math.min(maxColumns, 3); // 最多3列，避免过于拥挤
    }

    /**
     * 绘制指定范围的Handler面板
     *
     * @param startIndex 起始Handler索引
     * @return 实际绘制的Handler数量
     */
    @Unique
    private int drawHandlerPanels(List<Module> handlers, Map<Module, GuiBlockInfo> guiInfoMap, int startIndex,
                                  int startX, int startY, int columnWidth,
                                  int maxColumns, float scaledHeight) {
        int drawnCount = 0;
        int currentColumn = 0;
        int currentX = startX;
        int currentY = startY;

        for (int i = startIndex; i < handlers.size(); i++) {
            Module handler = handlers.get(i);
            GuiBlockInfo guiInfo = guiInfoMap.get(handler);
            if (guiInfo == null) continue;

            // 构建调试文本并计算面板高度
            List<String> debugLines = buildHandlerDebugLines(handler, guiInfo);
            int panelHeight = debugLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

            // 列数满了，换行
            if (currentColumn >= maxColumns) {
                currentColumn = 0;
                currentX = startX;
                currentY += panelHeight + DEBUG_PADDING * 2;

                // 超出屏幕高度，停止绘制
                if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                    break;
                }
            }

            // 绘制面板背景
            RenderUtils.fill(
                    currentX, currentY,
                    currentX + columnWidth, currentY + panelHeight,
                    new Color(0, 0, 0, 50)
            );

            // 绘制文本
            int lineY = currentY + DEBUG_PADDING;
            for (String line : debugLines) {
                drawDebugLine(line, currentX + DEBUG_PADDING, lineY);
                lineY += DEBUG_LINE_HEIGHT;
            }

            // 更新位置和计数
            drawnCount++;
            currentColumn++;
            currentX += columnWidth + COLUMN_SPACING;

            // 检查是否超出当前列的高度限制
            if (currentY + panelHeight > scaledHeight - SIDE_MARGIN) {
                break;
            }
        }

        return drawnCount;
    }

    /**
     * 绘制公共调试信息（左上角）
     */
    @Unique
    private int drawCommonDebugInfo(int startX, int startY) {
        List<String> commonLines = new ArrayList<>();
        commonLines.add("全局Tick: " + ModuleManager.getCurrentHandlerTime());
        commonLines.add("活跃Module数: " + ModuleManager.VALUES.size());

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = 0;
        for (String line : commonLines) {
            String cleanLine = line.replaceAll("§[0-9a-fA-Fklmnor]", "");
            maxWidth = Math.max(maxWidth, mc.font.width(cleanLine));
        }

        int bgWidth = maxWidth + DEBUG_PADDING * 2;
        int bgHeight = commonLines.size() * DEBUG_LINE_HEIGHT + DEBUG_PADDING * 2;

        // 绘制公共信息背景
        RenderUtils.fill(
                startX, startY,
                startX + bgWidth, startY + bgHeight,
                new Color(0, 0, 0, 50)
        );

        // 绘制文本
        int lineY = startY + DEBUG_PADDING;
        for (String line : commonLines) {
            drawDebugLine(line, startX + DEBUG_PADDING, lineY);
            lineY += DEBUG_LINE_HEIGHT;
        }

        return startY + bgHeight;
    }

    // ========== HUD进度条等信息绘制 ==========
    @Unique
    private void drawHudInfo(float scaledWidth, float scaledHeight) {
        int centerX = (int) (scaledWidth / 2);
        int centerY = (int) (scaledHeight / 2);
        GUI guiHandler = ModuleManager.GUI;

        // 延迟过大警告
        if (Configs.Core.LAG_CHECK.getBooleanValue() && ModuleManager.getPacketTick() > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            RenderUtils.drawString("延迟过大，已暂停运行", centerX, centerY - 22, Color.ORANGE, true, true);
        }

        // 进度条显示
        double progress = guiHandler.getTotalProgress().getProgress();
        RenderUtils.drawString((int) (progress * 100) + "%", centerX, centerY + 22, Color.WHITE, true, true);
        drawProgressBar(centerX, centerY + 36, 40, 6, progress, new Color(0, 0, 0, 150), new Color(0, 255, 0, 255));

        // 已启用模块名称显示
        HashSet<String> modeNames = new HashSet<>();
        for (Module module : ModuleManager.VALUES) {
            if (module.getId().equals(GUI.NAME) || module.getEnableConfig() == null || !module.getEnableConfig().getBooleanValue()) {
                continue;
            }
            modeNames.add(module.getEnableConfig().getPrettyName());
        }
        if (!modeNames.isEmpty()) {
            RenderUtils.drawString(String.join(", ", modeNames), centerX, centerY + 52, Color.WHITE, true, true);
        }
    }

    @Unique
    private void drawProgressBar(int x, int y, int barWidth, int barHeight, double progress, Color bgColor, Color fgColor) {
        double clampedProgress = Math.clamp(progress, 0.0, 1.0);
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