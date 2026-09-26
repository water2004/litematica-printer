# Minecraft 1.21.1 适配

此目标从当前 1.1.0 实现移植，保留打印、填充、流体清理、破基岩、异步扫描、缺失材料 HUD、方块高亮、Servux 手持确认及可选模组桥接。原有 `26.1.2`、`26.2`、`26.3` 目标保留。

运行要求：Java 21 或更新版本、Minecraft 1.21.1、Fabric Loader 0.19.3 或更新版本、Fabric API 0.116.17+1.21.1、MaLiLib 0.21.10、Litematica 0.19.61。Mod Menu 可选。

在本仓库根目录执行：

```powershell
.\gradlew.bat :1.21.1:remapJar :core:test --max-workers=2
```

正式产物：`versions/1.21.1/build/libs/litematica-printer-1.1.0-backport.1+1.21.1.jar`。不要把开发 JAR 或下述测试 JAR 安装到日常游戏实例。

ChainVeinFabric 为可选运行依赖；1.21.1 目标缓存反射调用 `queueMineJobs(Minecraft, Collection<BlockPos>)`，不需要其 JAR 才能编译，也不将它打包进 Printer。QuickShulker 直接传输接口同样保持为可选反射边界。

迁移采用 Mojang mappings 与 Loom remap，替换了 26.x 的渲染管线、HUD 回调、容器点击包、潜行包、移动包、NBT 类型、世界高度和注册表接口。Servux 桥接使用 1.21.1 Litematica 的 `EntitiesDataStorage`。纯 Java core 和构建插件兼容 Java 21。

## 验证结果（2026-09-26）

- `:1.21.1:remapJar` 成功，生成正式 intermediary JAR。
- `:core:test` 通过：5 项手持确认、错误响应、重试和超时测试，0 失败。
- 真正的 Fabric 生产客户端（非 Loom 开发命名环境）通过 10 项自动场景，使用 Java 25、Minecraft 1.21.1，共装 Printer、ChainVeinFabric 4.1.0-backport.1、QuickShulker 4.0.1、NetworkChaosFabric 1.0.0-alpha.3 和 EntityCollisionOptimizer 1.0.0-mc1.21.1-alpha.5。ECO 的 FFM 原生碰撞后端初始化成功，10 项场景仍全部通过。
- 场景涵盖配置界面、ChainVein 四个配置页、HUD 和所有加载的 mixin、普通生存填充、真实 Litematica 投影打印、纯发包投影打印及库存交换、潜影盒反射接口和能力握手、补货后打印、连锁挖掘、用沙子清理水。
- 方块变化与物品消耗均读取集成服务端确认；补货用例确认潜影盒留在原槽、盒内石头清空、64 个石头在放置后剩余 63 个。

## 可重复的客户端测试

1.21.1 没有新版 Fabric Client GameTest API，因此增加了独立 `smokeTest` 源集。测试使用真实单人世界、真实 Litematica 投影及服务端库存检查，不屏蔽生产 mixin 或替换打印流程。测试代码不会进入正式 JAR。

```powershell
.\gradlew.bat :1.21.1:remapSmokeJar --max-workers=2
```

把生成的 `*-smoke-test.jar` 与正式 Printer、前置依赖和要验证的可选模组放入一个隔离实例后启动客户端。它会自动打开配置页、创建测试世界并执行场景，完成后关闭客户端，将结果写入游戏目录的 `printer-smoke-result.txt`。可用 `-Dprinter.smoke.result=绝对路径` 指定结果文件，`-Dprinter.smoke.requireMods=id1,id2` 强制要求共装模组。只应在临时游戏目录使用此测试产物。

## 覆盖边界

本轮未验证破基岩漏洞在不同服务端实现中的行为、Servux 服务端实体查询往返、旧版 QuickShulker/TakeItOut/BedrockMiner 的完整联动、长时间压力测试以及真实远程服务器延迟/丢包。NetworkChaosFabric 已共装，但此轮打印场景没有主动注入网络故障。这些功能源码保留，不能由上述通过结果推断其所有运行条件均已覆盖。
