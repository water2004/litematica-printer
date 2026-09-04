# Litematica Printer 四改版

[English](README.md)

[![GitHub release](https://img.shields.io/github/v/release/water2004/litematica-printer?include_prereleases)](https://github.com/water2004/litematica-printer/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2-blue)](#下载)
[![License](https://img.shields.io/badge/license-AGPL--3.0-green)](LICENSE.md)

> [!IMPORTANT]
> 本项目是 [BiliXWhite/litematica-printer](https://github.com/BiliXWhite/litematica-printer) 三改版的四改分支。本仓库拥有独立的发布、兼容范围、问题追踪和文档；请只从 [water2004/litematica-printer Releases](https://github.com/water2004/litematica-printer/releases) 下载本四改版。

Litematica Printer 四改版是 [Litematica](https://modrinth.com/mod/litematica) 的客户端 Fabric 扩展。它持续发现尚未完成的投影位置，并通过有界、多线程的生产者—消费者调度器处理作业。打印、填充、排流体和破基岩是彼此独立的工作模式。

## 下载

请在 [GitHub Releases](https://github.com/water2004/litematica-printer/releases) 下载与 Minecraft 版本对应的 jar：

| Minecraft | 发布文件 |
| --- | --- |
| 26.1.2 | `litematica-printer-<版本>+26.1.2.jar` |
| 26.2 | `litematica-printer-<版本>+26.2.jar` |

目前只构建和测试这两个版本。上游项目与本项目使用相同的模组 ID `litematica-printer`，请勿同时安装。

使用自动放置或破坏功能前，请备份重要世界和物品。

## 必需前置

请在客户端安装与 Minecraft 版本匹配的：

- [Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

Minecraft 26.1.2 的测试基线是 MaLiLib `0.28.8` 与 Litematica `0.27.9`；Minecraft 26.2 的测试基线是 MaLiLib `0.29.2` 与 Litematica `0.28.3`。在声明的 Minecraft 范围内，兼容的较新 Litematica 版本也可能正常工作。

## 可选集成

基础放置、填充和排流体功能不依赖下列集成。

| 集成 | 提供的能力 |
| --- | --- |
| [ChainVeinFabric](https://github.com/water2004/ChainVeinFabric) | 破坏错误或多余方块，以及执行破冰放水作业。基础破坏只需客户端安装；ChainVein 的服务端专用功能仍需服务端支持。 |
| [Quick Shulker](https://github.com/water2004/quickshulker) | 从玩家携带的潜影盒中取出所需材料。支持时使用不打开界面的直接协议；兼容旧版时使用被隔离的旧界面路径。Quick Shulker 客户端与服务端应使用相互匹配的版本。 |
| AxShulkers 或 TakeItOut | 可在打印机设置中选择的服务端潜影盒材料来源。 |
| [Servux](https://modrinth.com/mod/servux) | Litematica 轻松放置协议，以及可选的服务端权威手持物品确认。 |
| [Fabric-Bedrock-Miner](https://github.com/bunnyi116/fabric-bedrock-miner) 或 [Block-Miner](https://github.com/z7087/blockminer) | 执行破基岩模式发现的作业。 |

所有集成都通过能力探测启用。缺少集成时，对应功能会保持关闭或跳过，普通打印不受影响。

## 四改版的主要变化

### 持续生产者—消费者调度

- 生产者持续扫描不可变的世界与投影快照，不再交替进行“一轮完整搜索”和“一轮打印”。
- 搜索范围被切分为小任务，由可配置线程池并发处理；工作线程不会直接读取实时世界。
- 有界作业池按事务种类组织放置、状态调整、使用和破坏作业。
- 消费者公平遍历作业桶并批量处理兼容作业，减少频繁换手，也不会让一个跳过位置阻塞其他作业。
- 已处理、失败、跳过、过期和缺少材料的作业都视为已经消费；位置若仍未完成，会被后续扫描重新发现。
- 消费者执行前会再次校验实时世界，因此过时快照不会强制执行已经失效的动作。

### 更低的扫描开销

- 将工作范围形状、投影范围和选区编译为可复用的空间掩码。
- GUI 统计与打印生产者复用兼容的搜索计划和共享快照页。
- 搜索游标直接读取紧凑的分页数据，只为真正成为作业的位置创建 `BlockPos`。
- HUD 只发布完整统计结果，不会跟随生产者尚未完成的本轮进度来回跳动。

在 Minecraft 26.2 上重新进行的同条件 A/B 基准中，使用相同的 531,441 坐标夹具、4 个搜索线程、2 轮预热和 7 轮测量，完整 GUI + 打印扫描的中位耗时从 1.0.0 的 `457.45 ms` 降至 1.1.0 的 `45.59 ms`，在开发机器上约快 `10.0 倍`。测试方法、正确性校验与注意事项见 [1.1.0 Release Note](https://github.com/water2004/litematica-printer/releases/tag/v1.1.0)。

### 可预期的物品与动作处理

- 所有物品栏或快捷栏切换都会等待所配置的确认路径完成，再继续打印。
- 远程潜影盒取料拥有明确的等待状态，不会让消费者抢在材料到达前继续执行。
- 同种作业会在每 tick 配置上限内批量处理。
- 关闭全局工作开关只会暂停，不会清空生产者和消费者状态。
- 轮换模式只改变当前模式，绝不会自动打开全局工作开关。

## 功能

### 工作模式

- **打印：**放置缺失方块、调整受支持的方块状态，并可选破坏错误或多余方块。
- **填充：**根据方块与方向过滤设置，在当前 Litematica 选区内填充。
- **排流体：**清除所选工作范围内配置的静止或流动流体。
- **破基岩：**发现破基岩作业并提交给受支持的破基岩模组。

旧的通用挖掘模式和内置远程大仓已经移除。

### 特殊打印动作

已有的具体方块操作语义继续保留，包括：

- 需要去皮原木时，先放置原木再去皮；
- 放置冰并破冰生成水；
- 直接放水和含水方块处理；
- 侦测器、活塞、楼梯、门、活板门、告示牌、头颅、旗帜、红石元件等受支持方块的方向与状态放置；
- 音符盒调音、侦测器安全放置、珊瑚替换、作物催熟和堆肥桶填充；
- 可配置的跳过列表和覆盖列表；
- 普通交互放置和数据包放置路径。

### 反馈与控制

- 稳定的完成比例与缺失材料 HUD。
- 当前消费者动作、等待原因、作业位置和作业池长度。
- 生产者当前轮扫描进度。
- 可分别配置放置、调整、破坏和失败动作的高亮。
- 工作范围形状、遍历顺序、坐标轴方向、搜索线程数和每 tick 动作上限。
- 生产者发现与消费者执行都会遵循 Litematica 可见层设置。

## 快速开始

1. 安装正确版本的打印机 jar 和必需前置。
2. 使用 Litematica 加载并放置一个投影。
3. 默认依次按下 `Z`、`Y` 打开打印机设置。
4. 启用**打印**模块，或启用需要使用的其他工作模式。
5. 移动到目标方块的交互范围内。
6. 按 `Caps Lock` 打开全局**工作开关**。

全局工作开关与具体工作模式开关必须同时启用。默认工作范围 `0` 表示自动使用当前可用交互距离。

## 重要设置

- **每 tick 方块数 / 工作间隔：**控制吞吐量。存在限速或反作弊的服务器可能需要降低数量或增加间隔。
- **凭空放置：**服务端允许时，无需已有相邻支撑方块也可尝试放置。
- **数据包放置：**使用另一条直接发送数据包的放置路径；它不会绕过服务端校验，也不能消除网络丢包。
- **Servux 手持确认：**换手后等待服务端权威主手与目标一致。若开启但服务器没有兼容 Servux，打印机会主动暂停并显示等待原因。
- **破坏错误/多余方块：**需要 ChainVeinFabric；未安装时不会启用破坏类打印作业。
- **潜影盒来源：**应根据实际环境选择 Quick Shulker、AxShulkers 或 TakeItOut。

## 常见问题

### 打印机完全没有动作

请确认：

- jar 与 Minecraft 版本完全对应；
- Fabric API、MaLiLib 和 Litematica 均已加载；
- 投影已经放置，且相关图层可见；
- 具体工作模式和全局工作开关都已启用；
- 玩家位于配置的工作范围内；
- 所需物品可用，HUD 没有显示正在等待换手或潜影盒；
- 服务器反作弊或放置限速没有拒绝当前速度。

### 破坏类作业始终不执行

错误方块、多余方块和破冰作业需要 ChainVeinFabric。破基岩作业还需要一个受支持的破基岩模组。

### HUD 显示正在等待 Servux

你开启了服务端权威手持确认，但当前服务器没有提供兼容的 Servux 通道。请在服务端安装并配置 Servux，或关闭该确认选项。

### 快捷潜影盒一直等待或不可用

确认设置中的潜影盒来源与实际安装的集成一致。使用 Quick Shulker 直接协议时，客户端与服务端应使用同一个兼容版本。HUD 会区分正在等待容器操作还是等待物品切换。

遇到可复现问题时，请在[本仓库 Issues](https://github.com/water2004/litematica-printer/issues)提交，并尽量附上客户端与服务端日志、Minecraft/模组版本、相关设置以及最小复现投影。

## 从源码构建

项目需要 JDK 25，并已包含 Gradle Wrapper。

```bash
git clone https://github.com/water2004/litematica-printer.git
cd litematica-printer
./gradlew build
```

Windows 使用 `gradlew.bat build`。各版本 jar 输出到：

- `versions/26.1.2/build/libs/`
- `versions/26.2/build/libs/`

与 Minecraft 无关的调度代码位于 `core/`；`versions/` 下各目录包含独立的 Minecraft 适配。`main` 上带标签的提交会自动运行 GameTest、构建两个 jar 并发布对应 GitHub Release。

## 许可证

本项目使用 [GNU Affero General Public License v3.0](LICENSE.md) 发布。
