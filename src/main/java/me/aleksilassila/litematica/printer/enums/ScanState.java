package me.aleksilassila.litematica.printer.enums;

/**
 * 扫描状态机：控制迭代扫描的阶段。
 * <p>
 * COLLECT — 收集全坐标，按材料分组以最小化物品切换
 * PROCESS — 按材料分组依次处理坐标
 * WAITING — 暂停迭代，等待异步条件满足后继续
 * </p>
 */
public enum ScanState {
    COLLECT,
    PROCESS,
    WAITING
}