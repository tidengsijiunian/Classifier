# Classifier · 分类者

一个 Minecraft 1.18.2 (Forge) 的**自动物品分类**模组。核心是一个叫「分类者」的生物实体：它会自己走到箱子旁边，把物品按标签、模组、具体物品三类规则分门别类地放进对应的箱子里。

> 版本 `0.1.0-m2`　|　Minecraft `1.18.2`　|　Forge `40.3.12`　|　JDK `17`
> 作者 TidengYogvin　|　License: All Rights Reserved

---

## 它能做什么

| 能力 | 说明 |
|---|---|
| 自动分类 | 分类者实体在 64×64 范围内扫描掉落物，按规则投放到对应的箱子里 |
| 三级类别 | `tag:`（物品标签，如矿物/羊毛/植物/农作物）、`mod:`（按所属模组）、`item:`（具体物品） |
| 首物自适应 | 一个空箱子遇到第一件物品时，按「内置标签 → 非原版模组 → 具体物品」的顺序自动判断它该归哪一类 |
| 子标签匹配 | `forge:ores/iron` 也能命中 `forge:ores` 这个父类别 |
| 箱子打分 | 优先投进专用类别箱，不会把「矿物箱」污染成杂物箱（详见下文） |
| 跨维度防呆 | 实体每 tick 校验自己是否还在绑定的维度里；维度不符就停工并提示附近玩家 |
| 仓储雷达 | 手持管理器打开界面即可看到全仓库的容器分布，支持隔墙透视与工作区高亮 |
| 可视化交互 | 工作区边界可用 Alt + 滚轮沿视线方向微调；分类者头顶显示名字与「工作中 / 休闲中」状态 |

---

## 快速开始

1. 安装 Minecraft 1.18.2 与 Forge 40.3.12
2. 把构建产物 `classifier-0.1.0-m2.jar` 放进 `mods/` 目录
3. **客户端与服务端必须使用同一个 jar**（网络协议版本见 `ModNetwork.PROTOCOL_VERSION`）

### 从源码构建

```bash
# 需要 JDK 17
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows

# 产物在 build/libs/
```

`gradle.properties` 里的 `org.gradle.java.home` 强制指定了 JDK 17 路径，换机器编译时记得改这一行。

---

## 分类是怎么判断的

### 类别格式

```
tag:<namespace>:<path>   例：tag:forge:ores
mod:<namespace>          例：mod:thermal
item:<namespace>:<path>  例：item:minecraft:diamond
```

### 首件物品的自适应推断

一个还没被分配类别的空箱子收到第一件物品时，按以下顺序判断：

1. **内置优先标签**：`forge:ores`、`forge:raw_materials`、`forge:ingots`、`forge:gems`、`forge:crops`、`minecraft:logs`
2. **非原版命名空间**：属于某个模组（不是 `minecraft`）的物品，按该模组建类
3. **具体物品**：以上都不满足，就按单个物品 ID 建类

### 容器打分（选哪个箱子放）

| 情况 | 分数 |
|---|---|
| 箱子类别命中物品类别 | **1000** |
| 箱子里已有同类物品 | **100** |
| 同 Tag 命中，或同为食物 | **50** |
| 纯净空箱 | **10** |
| 同模组命名空间（非 minecraft） | **+30** |
| 混入不兼容物品 / 已满 | **0（淘汰）** |

专用空箱如果类别对不上，直接跳过——这是防止「矿物箱」被塞进木头的关键。

---

## 架构概览

```
src/main/java/com/tidengyogvin/classifier/
├── SorterEntity.java          分类者实体：扫描、寻路、投递、NBT 持久化
├── SorterWorkGoal.java        容器打分与投放决策
├── CategoryRules.java         类别解析、首物自适应推断、子标签匹配
├── ClassifierRadarState.java  服务端雷达锁表（玩家 → 容器坐标）
├── RadarSync.java             雷达快照的采集与增量推送
├── WorkspaceTable.java        工作区真值表（服务端权威，客户端只读）
├── ContainerUtil.java         容器物品摘要
├── ModNetwork.java            网络通道注册（SimpleChannel）
├── network/                   各类 C→S / S→C 数据包
└── client/                    界面、渲染、雷达高亮、滚轮微调
```

### 客户端 / 服务端边界

- **类别与工作区的真值只在服务端**，客户端拿到的都是下发副本
- 打开管理器界面时走一次全量快照（`REQUEST_RADAR`）
- 游玩中每 40 tick 检查一次签名（容器坐标 + 类别集合），**只有变化时才增量推送**
- 推送对象 = 同一维度且手持管理器的玩家

### 一个渲染上的坑（记录在此，免得后人再踩）

`RenderType.LINES` 的 `setupRenderState()` 会**主动应用自己携带的 `NO_DEPTH_TEST`**，所以在外面调 `RenderSystem.disableDepthTest()` 是无效的。

解法：子类化 `RenderStateShard` 访问它 protected 的深度测试常量，自建一个 `RenderType`：

```java
RenderType.create(
    "classifier_radar_lines",
    DefaultVertexFormat.POSITION_COLOR_NORMAL,
    VertexFormat.Mode.LINES,
    256, false, false,
    state   // setDepthTestState(NO_DEPTH_TEST) + setWriteMaskState(COLOR_WRITE)
);
```

工作区线框和选中容器线框用普通 `RenderType.lines()` 单独先后冲刷，避免 batch 的深度状态互相污染。

---

## 开发环境

| 项 | 值 |
|---|---|
| 根包 | `com.tidengyogvin.classifier` |
| mod_id | `classifier` |
| 构建 | `gradlew.bat build`（Gradle 7.2 wrapper，MDK 自带，**不要升级**） |
| 每次改动后 | 至少跑一次 `gradlew.bat compileJava` 确认能过 |
| 运行测试服 | `gradlew.bat runServer` |

---

## 当前状态

`0.1.0-m2` 是开发中的里程碑版本：

- 分类、打分、跨维度防呆、雷达同步、工作区微调等核心功能已经跑通
- **已知待修**：一轮静态审查（区块加载 / 卡死 / 性能方向）标记了若干问题，尚未全部处理
- 分类规则后续计划改为由 JSON 数据文件驱动（`data/classifier/sorter_rules/`），方便整合包作者自定义

---

## 许可

All Rights Reserved。本仓库中的 Forge MDK 模板部分（`gradlew`、`build.gradle` 骨架、`LICENSE.txt`、`changelog.txt`）版权归 MinecraftForge 所有，遵循随附的 LGPL 2.1 条款。
