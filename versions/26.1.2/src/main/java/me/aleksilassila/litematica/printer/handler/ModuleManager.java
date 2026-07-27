package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GUI GUI = new GUI();
    public static final Print PRINT = new Print();
    public static final Fill FILL = new Fill();
    public static final FluidRemoval FLUID_REMOVAL = new FluidRemoval();
    public static final Bedrock BEDROCK = new Bedrock();

    @Getter
    @Setter
    private static int packetTick;
    @Getter
    private static long currentHandlerTime;

    public static final ImmutableList<Module> VALUES = ImmutableList.of(
            GUI, PRINT, FILL, FLUID_REMOVAL, BEDROCK
    );

    private static boolean lastPrinterEnabled = false;

    public static void tick() {
        /*
         * 搜索描述捕获与业务动作门控分离。即使换手、容器或服务器延迟让消费者暂停，
         * 独立调度线程仍可完成当前扫描轮次；工作池忙时 tryStartRound 不会排队新轮次。
         */
        List<AsyncSearchCoordinator.SearchRequest> searchRequests = new ArrayList<>();
        for (Module module : VALUES) {
            module.prepareAsyncSearch();
            AsyncSearchCoordinator.SearchRequest request = module.captureSearchRequest();
            if (request != null) searchRequests.add(request);
        }
        AsyncSearchCoordinator.INSTANCE.tryStartRound(searchRequests);

        // If TakeItOut is waiting for a server-side shulker extraction, skip
        // all processing so the printer does not interfere.
        if (TakeItOutCompat.isAwaitingItem()) return;

        QuickShulkerUtils.tick();
        // 任意容器界面尚未真正关闭时，暂停整个调度器。否则换手会用玩家背包
        // containerId=0 操作仍处于打开状态的容器，客户端会忽略点击并在随后同步时回滚库存。
        if (mc.player == null || mc.player.containerMenu != mc.player.inventoryMenu) {
            return;
        }

        boolean printerEnabled = ConfigUtils.isPrinterEnable();
        if (printerEnabled && !lastPrinterEnabled) {
            MissingMaterialTracker.getInstance().reset();
        }
        lastPrinterEnabled = printerEnabled;

        MissingMaterialTracker.getInstance().startCycle();

        if (ActionManager.INSTANCE.sendQueue(mc.player).needWaitModifyLook) {
            return;
        }

        if (Configs.Core.LAG_CHECK.getBooleanValue()) {
            if (packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
                return;
            }
            packetTick++;
        }

        for (Module module : VALUES) {
            if (!(module instanceof GUI)) {
                if (ActionManager.INSTANCE.needWaitModifyLook) {
                    return;
                }
            }
            module.tick();
        }
    }

    public static void updateTickHandlerTime() {
        currentHandlerTime++;
    }
}
