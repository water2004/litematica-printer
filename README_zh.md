# Litematica Printer 四改版

[English](README.md)

![GitHub stars](https://img.shields.io/github/stars/water2004/litematica-printer)
![GitHub release](https://img.shields.io/github/v/release/water2004/litematica-printer)
![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2-blue)

> [!IMPORTANT]
> **本仓库 fork 自 [BiliXWhite/litematica-printer](https://github.com/BiliXWhite/litematica-printer)，即投影打印机三改版。** 本项目在其基础上作为**投影打印机四改版**继续维护。
>
> 项目演进关系：[aleksilassila 原版](https://github.com/aleksilassila/litematica-printer) → [zhaixianyu 二改版](https://github.com/zhaixianyu/litematica-printer) → [BiliXWhite 三改版](https://github.com/BiliXWhite/litematica-printer) → 本项目四改版。
>
> 四改版当前正式版本为 [1.0.0](https://github.com/water2004/litematica-printer/releases/tag/v1.0.0)，**仅支持 Minecraft 26.1.2 和 26.2**。下方保留的版本支持和下载信息属于上游项目，不代表本 fork 的当前情况。

## 相比三改版的变化

- 用持续扫描的生产者和有界作业池消费者替换旧的往返搜索调度。
- 消费者公平遍历事务桶，并批量处理同类的放置、使用/调整和破坏作业。跳过、失败、过期及已经提交的作业都会立即消费；尚未完成的位置由后续扫描重新发现。
- 搜索基于已经切分好的世界快照异步并发执行，避免阻塞客户端主线程，同时遵循 Litematica 可见层设置。
- 所有物品切换（包括远程容器取物）都会等待客户端确认完成后再继续打印。
- 错误方块、多余方块和破冰统一通过可选的 [ChainVeinFabric 3.0.0](https://github.com/water2004/ChainVeinFabric/releases/tag/v3.0.0) 客户端作业 API 处理；未安装时不启用这些破坏功能。
- 调试 HUD 增加生产者扫描进度、作业池长度、消费者状态和当前作业；关闭打印开关不再清空调度状态。
- 保留已有的具体方块操作语义，包括原木放置后去皮、放水、破冰、方块状态调整、填充和破基岩。
- 删除旧挖掘模式、内置远程大仓和多版本包装器；发布物改为 Minecraft 26.1.2 与 26.2 的独立 jar。
- 工程调整为纯 Java、与 Minecraft 无关的 `core` 调度层和彼此隔离的版本适配层，不再使用源码预处理或版本 mapping。

---

## 上游原 README

> 以下内容原样保留自直接上游仓库。

# Litematica Printer

![GitHub stars](https://img.shields.io/github/stars/BiliXWhite/litematica-printer)
![GitHub release](https://img.shields.io/github/v/release/BiliXWhite/litematica-printer)
![Minecraft](https://img.shields.io/badge/Minecraft-1.18.2%20~%2026.2-blue)

为 [Litematica](https://modrinth.com/mod/litematica) 投影添加自动建造功能的 Minecraft Fabric 模组。支持 1.18.2 ~ 26.2 版本。

该分支基于[宅咸鱼二改版](https://github.com/zhaixianyu/litematica-printer)修改，添加了更多实用功能。

如果你觉得好用，欢迎给项目点个 Star ⭐️

> [!TIP]
> 该分支始终保持开源免费，不会存在任何收费内容。条件允许的话可以给作者[买瓶脉动](https://ifdian.net/a/BlinkWhite)支持一下！

---

## 下载

| 渠道              | 链接                                                                |
|-----------------|-------------------------------------------------------------------|
| GitHub Releases | [点击下载](https://github.com/BiliXWhite/litematica-printer/releases) |
| 蓝奏云分流（密码: cgxw） | [点击下载](https://xeno.lanzoue.com/b00l1v20vi)                       |

---

## 支持的游戏版本

| 版本支持                                                |
|-----------------------------------------------------|
| 1.18.2 · 1.19.4 · 1.20.1 · 1.20.2 · 1.20.4 · 1.20.6 |
| 1.21.1 ~ 1.21.11 · 26.1 · 26.2                      |

> [!NOTE]
> 1.18.2 以下版本暂不接受更新，小版本是否可用请自行尝试

---

## 前置模组

### 必需
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [MaLiLib](https://modrinth.com/mod/malilib)
- [Litematica](https://modrinth.com/mod/litematica)

### 可选
- [ChainVeinFabric](https://github.com/water2004/ChainVeinFabric) - 打印时破坏错误、多余方块及破冰所需；未安装时这些破坏功能不会启用
- [Quick Shulker](https://modrinth.com/mod/quick-shulker) 或 [AxShulkers](https://modrinth.com/mod/axshulkers) - 快捷潜影盒（双模式兼容）
- [Fabric-Bedrock-Miner](https://github.com/bunnyi116/fabric-bedrock-miner) - 破基岩所需前置

---

## 特性

### 性能优化
- 更流畅的打印体验
- 数据包打印模式（速度更快，避免幽灵方块）
- 延迟卡顿检测，防止因延迟导致的大量方块放置错误

### 新功能
- 可视化工作进度条 - 一目明了范围内是否完工
- 区域内缺失材料显示 - 快速感知缺失材料，方便及时补充
- 高亮处理中方块 — 多种高亮类型与样式，支持自定义颜色、透明度
- 双兼容快捷潜影盒 — 重写支持Mod/服务器插件双模式
- 填充功能（使用投影选区范围）
- 珊瑚替换（用活珊瑚打印投影内的死珊瑚）
- 48 种范围迭代逻辑
- 破坏错误额外方块和错误状态方块
- 农作物催熟 - 方便打印大片稻田类原理图
- 有界作业池 - 以最早作业为锚点，向后选择同类放置、使用或破坏作业批量处理
- ChainVein 作业接口 - 错误方块、多余方块和破冰统一批量提交给 ChainVein 客户端作业队列
- 多语言支持 - **中文（简体）** · **中文（繁体）** · **文言文** · **English** · **Русский**

### 方块放置修复
- 合成器、拉杆、红石粉（非连接模式）
- 枯叶、各种花簇的方向
- 发光浆果、带花的花盆
- 楼梯、藤蔓、缠怨藤、垂泪藤
- 砂轮、门、活版门、漏斗、箱子
- 旗帜、头颅（16 朝向支持）
- 告示牌悬挂状态修正
- 以及更多

---

## 使用方法

1. 在世界中加载一个原理图
2. 移动到可以接触到原理图方块的位置
3. 按下 `Caps Lock` 键开启打印机
4. 等待自动建造完成 🎉

> [!TIP]
> 大部分功能都含有游戏内注释可供参考使用

---

## 未支持方块

以下方块由于特殊原因暂未实现，打印时会自动跳过或呈现错误状态：

- 装有液体的炼药锅
- 睡莲
- 实体方块（物品展示框、盔甲架、画等）
- 非原版游戏内容

> [!TIP]
> 如发现其他方块放置错误，请尝试降低建造速度。若问题依旧存在，请提交 [Issue](https://github.com/BiliXWhite/litematica-printer/issues)

---

## 🔨 编译

> [!WARNING]
> 部分模组使用 Github Maven 源，从 pkg.github.com 下载需要认证。本地构建时需要在系统环境中设置 `GH_USERNAME` 和 `GH_TOKEN`，否则会构建失败。

### 命令行编译

```bash
git clone https://github.com/BiliXWhite/litematica-printer.git
cd litematica-printer
./gradlew build
```

### IDEA 编译

1. 用 IDEA 打开项目
2. 在 Gradle 面板中找到 `Tasks → build`，双击 `build`
3. 等待编译完成

### 构建产物位置

| 类型      | 位置                                                |
|---------|---------------------------------------------------|
| 多版本 jar | `./fabricWrapper/build/libs/`                     |
| 单版本 jar | `./fabricWrapper/build/tmp/submods/META-INF/jars` |

---

## ❓ 常见问题

### 加入QQ群（适用于中国大陆用户）

如果你喜欢跟进体验最新的功能，持续提供可复现的Bug，那么推荐你加入QQ群聊以便直接和开发者沟通！

[点击加入 QQ 群聊](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=ttinzrJB3jYRLSTJM8R2YfwYdCm4Zo90&authKey=vfwF)

---

### Q: 开启打印后，打印机不工作？

**可能原因：**
1. 服务器反作弊检测 — 投影打印机基于静默看向方式放置方块，可能被检测
2. 打印机工作间隔设置过小 — 有放置速率限制的服务器（如 Luminol）无法及时响应

**解决方案：**
- 请求你的服主关掉反作弊或者是换一个服务器玩
- 开启「使用数据包打印」模式
- 调大「打印机工作间隔」

如仍无法解决，请提交 [Issue](https://github.com/BiliXWhite/litematica-printer/issues/new?template=bug%E6%8A%A5%E5%91%8A.yml)

---

### Q: 打印机放置的方块是错的？

**可能原因：**
1. 服务器反作弊插件干扰
2. 打印机工作间隔过小，服务器响应不及时
3. 识别算法未考虑该方块特性

**解决方案：**
- 增大「打印机工作间隔」
- 降低建造速度

如问题持续，请提交 [Issue](https://github.com/BiliXWhite/litematica-printer/issues/new?template=%E6%89%93%E5%8D%A0%E6%96%B9%E5%9D%97%E8%AF%B7%E6%B1%82.yml)

---

### Q: 快捷潜影盒功能无法使用？

**可能原因：**
1. 服务器未安装 AxShulkers 等支持在背包右键打开潜影盒的插件
2. 投影打印机设置与实际支持模式不符
3. 预选栏位被潜影盒填满

**解决方案：**
- 在 Litematica 设置中调整 `pickBlockableSlots`（快捷选择栏位）值
- 确认所选择的工作模式是正确的

> [!NOTE]
> 快捷潜影盒功能现已重写。如遇问题请提交 [Issue](https://github.com/BiliXWhite/litematica-printer/issues)

---

## 🙏 感谢

- [bunny_i](https://github.com/bunnyi116) - 开发者之一
- [aleksilassila](https://github.com/aleksilassila/litematica-printer) - 原创基础
- [zhaixianyu](https://github.com/zhaixianyu/litematica-printer) - 二改版本
- [MoRanpcy](https://github.com/MoRanpcy/quickshulker) - 快捷潜影盒支持
- [bunnyi116](https://github.com/bunnyi116/fabric-bedrock-miner) - 新的破基岩
- [Rofumer](https://github.com/Rofumer) - 俄语本地化、性能优化、Bug 修复
- [Cjsah](https://github.com/Cjsah) - 选区内容器材料识别功能
- [EnderPhantomWing](https://github.com/EnderPhantomWing-Fork) 适配新版本、Bug 修复

以及所有支持开发的朋友，包括你！💖
