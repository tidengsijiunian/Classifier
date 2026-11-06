# Classifier — 项目上下文

## 一句话
一个 Minecraft 1.18.2 Forge 模组，新增生物"分类者"，自动把物品按标签分类并存入箱子。

## 核心实体：分类者（SorterEntity）
- 渲染：玩家模型，默认房主皮肤
- 皮肤：右键 GUI 从在线玩家列表选择，兼容 3D 皮肤模组
- 头顶双层悬浮文字：实体名 / 工作中或休闲中
- 工作模式：从"分类箱"取出物品，按标签走到普通箱子存入
- 扫描间隔：40 tick
- 检测范围：默认 64×64，工作区划定后以工作区为准
- 归属：`/cls add` 的创建者、或把容器绑定到它的玩家（`Owner` 字段），工作区同步只作用于归属者名下的分类者

## 分类规则
- 按 TagKey<Item>：矿物类、羊毛类、植物类、农作物类等
- 模组物品按命名空间单独成类
- 规则后续以 JSON 形式存于 data/classifier/sorter_rules/

## 容器概念
- 分类箱：玩家绑定的工作箱，分类者从中取物
- 普通箱：分类者按标签存入的箱子
- 箱子类别：每只箱子可被自动推断为 `tag:<ns>:<path>` / `mod:<ns>` / `item:<ns>:<path>`，见下文「首物自适应推断」

## GUI
- 在线玩家皮肤列表
- 分类箱绑定按钮
- 工作范围滑块（16 / 32 / 48 / 64）
- 管理器界面（`ManagerScreen`）：分类者列表 + 划定工作区 + 进入仓储雷达
- 工作区界面（`WorkspaceScreen`）：输入点 A / 点 B 坐标
- 仓储雷达界面（`ManagerRadarScreen`）：箱子列表 + 滚轮翻行 + 锁定

## 里程碑
- M1：骨架 + 自定义物品（已完成）
- M2.1：实体注册 + 玩家模型渲染 + 刷怪蛋
- M2.2：右键 GUI + 皮肤选择 + 网络同步
- M2.3：双层悬浮文字 + 状态同步
- M2.4：分类逻辑 + 分类箱绑定 + 40tick 扫描
- M2.5：管理器 GUI + 工作区 AABB 预览 + 界面暗化模糊
- M3.0：工作区接入 AI + 首物自适应推断 + 跨维度防呆 + Alt 滚轮微调 + 仓储雷达（当前）

## 命名对照
- 项目文件夹：classifier
- mod_id：classifier
- 根包：com.tidengyogvin.classifier
- 实体类：SorterEntity
- 渲染器：SorterRenderer
- 刷怪蛋：sorter_spawn_egg
- 资源命名空间：classifier

---

# M3.0 实现说明（交接文档）

## 新增 / 修改的类

### 服务端（`com.tidengyogvin.classifier`）

| 类 | 职责 |
| --- | --- |
| `CategoryRules` | 箱子存放类型的**规则中心**：类别字符串格式、首物推断、类别匹配（含子标签）、同模组命名空间判定、中文显示名与配色 |
| `RadarSync` | 服务端收集当前维度雷达数据并推送给持有管理器的玩家；按坐标去重、按接收者计算锁定状态 |
| `ClassifierRadarState` | 服务端权威的雷达锁定表（`玩家 UUID → 箱子坐标`），内存态，按玩家隔离 |
| `SorterEntity`（修改） | 新增归属、绑定维度、工作区维度、箱子类别表、使用次数、雷达节流同步、分类箱区块 ticket |
| `SorterWorkGoal`（修改） | 打分逻辑重写：标签箱 1000 分、同模组 +30、专用空箱过滤；放入纯净空箱前自动开仓；箱子搬空后撤销自动物品箱 |
| `ManagerData`（修改） | 新增 `RadarEntry` 记录（维度、所属分类者、坐标、类别、空箱、使用次数、锁定） |
| `ContainerUtil`（修改） | 新增 `ContainerSummary` 与 `summarize()`，统一读取容器是空/占用槽位/样本物品 |
| `ClassifierManagerItem`（修改） | 绑定分类箱时写入归属者 |
| `SorterCommand`（修改） | 创建分类者时写入创建者为归属者 |

### 网络包（`com.tidengyogvin.classifier.network`）

| 包 | 方向 | 变化 |
| --- | --- | --- |
| `ManagerSyncPacket` | S→C | 协议扩展：在分类者快照之后追加雷达列表（最多 1024 条，类别字符串最多 128 字符） |
| `ManagerActionPacket` | C→S | Action 新增 `REQUEST_RADAR` / `LOCK_RADAR` / `UNLOCK_RADAR`；`SET_WORKSPACE` 现在会同步附近分类者 |

> **协议版本**：`ModNetwork.PROTOCOL_VERSION` 仍为 `"1"`。服务端/客户端 jar 必须**同时更新**，否则旧客户端会因同步包结构不一致而解析失败。

### 客户端（`com.tidengyogvin.classifier.client`）

| 类 | 职责 |
| --- | --- |
| `ManagerRadarScreen` | 雷达界面：按常用程度排序的箱子列表、滚轮翻行、点击选中、`[锁定]/[解除锁定]` |
| `RadarHighlight` | 雷达选中的箱子坐标（客户端静态状态），退出服务器时清空 |
| `RadarInteractionHandler` | 监听 `PlayerInteractEvent.RightClickBlock`（CLIENT 侧、主手）：右键打开未锁定的高亮箱子时取消高亮 |
| `WorkspaceScrollHandler` | 监听 `InputEvent.MouseScrollEvent`：Alt + 滚轮沿视线主轴拉伸/收缩工作区 |
| `ManagerRenderer`（修改） | 新增雷达透视高亮，使用自建的「无深度测试」线框 RenderType |
| `ManagerSyncCache`（修改） | 同时缓存分类者快照与雷达列表，版本号驱动界面刷新 |
| `ManagerScreen`（修改） | 新增「仓储雷达」入口按钮 |

---

## 模块 1：工作区接入 AI

**写 NBT 的字段**（`SorterEntity`）：
- `WorkspaceMin` / `WorkspaceMax`：`BlockPos.asLong()`
- `WorkspaceDimension`：划定时的维度 ID。实体被搬去其它维度后，`getWorkspaceMin/Max()` 返回 `null`，回退到默认范围
- `Owner` / `OwnerName`：归属玩家

**流程**：`WorkspaceScreen` 保存 → `ManagerActionPacket(SET_WORKSPACE)` →
`saveWorkspace()` 写手持管理器 NBT → 以玩家位置为中心搜索 32 格内同维度分类者 →
管理员可接管归属，普通玩家只影响自己名下的分类者 → `setWorkspace(pos1, pos2)` →
ActionBar 提示「已将工作区同步给附近的 X 个分类者」。
`SorterWorkGoal.findTargetChest()` 始终通过 `getWorkspaceMin/Max()` 读取，因此立即生效。

## 模块 2：首物自适应推断（自动建仓）

**类别格式**（`CategoryRules`）：

| 前缀 | 含义 | 示例 |
| --- | --- | --- |
| `tag:` | 标签箱，后续只接收命中标签的物品 | `tag:forge:ores`、`tag:minecraft:logs` |
| `mod:` | 模组专属箱 | `mod:create` |
| `item:` | 普通物品箱（展示用，不限制） | `item:minecraft:diamond` |

**推断顺序**：内置优先标签（`forge:ores`、`forge:raw_materials`、`forge:ingots`、`forge:gems`、`forge:crops`、`minecraft:logs` … 见 `CategoryRules.PRIORITY_TAGS`）→ 非原版模组命名空间 → 具体物品。
**子标签匹配**：物品只有 `forge:ores/iron` 时也算命中 `forge:ores`。

**写入时机**：`SorterWorkGoal` 状态 2 放置前，若目标箱为空箱则调用 `SorterEntity.assignBoxCategory()`，
存入实体 NBT 的 `BoxCategories` 列表（`{Pos: long, Category: String}`），同时打印调试日志。
`item:` 类别的箱子被搬空后自动撤销记录，允许重新开仓。

**打分**（`SorterWorkGoal.getContainerScore`）：

| 条件 | 分数 |
| --- | --- |
| 箱子是标签箱/模组箱且命中 | **1000** |
| 箱内已有同类物品 | 100 |
| 同 Tag 或同为食物 | 50 |
| 纯净空箱 | 10 |
| 同模组命名空间（非 minecraft） | **+30** |
| 混入不兼容物品 / 满箱 | 0（淘汰） |

专用空箱（标签箱/模组箱）若物品不命中类别，直接跳过，防止“矿物箱”被杂物污染。

## 模块 3：跨维度防呆

- `BoundDimension`：绑定分类箱时的维度。`tick()` 与 `customServerAiStep()` 每 tick 调用
  `validateBindingDimension()`，不一致时清空分类箱、`setWorking(false)`、`setManualPause(false)`，
  并给 16 格内玩家发红字提示。
- 换绑分类箱会同时清空旧仓库的 `BoxCategories`，避免跨仓库沿用类别。
- 工作区的维度校验见模块 1。

## 模块 4：Alt + 滚轮微调工作区

`WorkspaceScrollHandler`：
1. 非界面态、手持管理器、已有 `Pos1/Pos2`、按住 Alt 时触发；`event.setCanceled(true)` 吞掉滚动（防止切快捷栏），
   并做 90ms 防抖。
2. 取视线 `getLookAngle()` 绝对值最大的轴作为主轴，滚动方向决定符号。
3. 移动 **视线正方向那一侧** 的边界：向上滚 = 拉伸 1 格，向下滚 = 收缩 1 格，至少保留 1 格。
4. 立即写本地物品 NBT（手持线框下一帧刷新），同时发送 `ManagerActionPacket(SET_WORKSPACE)` 由服务端写 NBT 并同步附近分类者。
5. ActionBar 显示区域尺寸 `宽×高×深`（格数）。

> 滚轮微调不触发全量快照重推，避免每次滚动都全维度扫描；分类者状态变化由实体节流同步送达。

## 模块 5：仓储雷达与隔墙透视

**数据来源**：`SorterEntity.getManagedBoxes()` 返回当前实体记录的箱子（`BoxCategories` 中仍存在的、未被清空的），
加上正在工作时非空的分类箱（类别为 `null`，界面显示“物品来源箱”）。
未加载区块的箱子保留记录但不显示；箱子被拆掉时顺带清理记录。

**同步策略**：
- 打开雷达 / 管理器时：客户端发送 `REQUEST_RADAR`，服务端回一份当前维度快照。
- 游玩中：分类者每 40 tick 检查一次数据签名（坐标 + 类别集合），变化时才推送；
  `setWorking` / 类别变化会清零签名，使下一次检查立即生效。
- 推送对象：同维度、**手持管理器**的玩家。

**透视渲染原理**（`ManagerRenderer`）：
- 问题的核心：`RenderType.LINES` 在 `setupRenderState()` 里会**主动**应用自身的 `NO_DEPTH_TEST` 状态，
  所以只在外层 `RenderSystem.disableDepthTest()` 是无效的——冲刷时会重新打开深度测试。
- 解决：通过 `RenderStateShard` 子类（`RadarLineType`）访问 protected 状态常量，用
  `RenderType.create("classifier_radar_lines", POSITION_COLOR_NORMAL, LINES, 256, false, false, state)`
  自建一个 **`setDepthTestState(NO_DEPTH_TEST)`** 的线框渲染类型；同时 `setWriteMaskState(COLOR_WRITE)`
  避免透视框写入深度、遮挡后续渲染。
- 冲刷顺序：普通线框（工作区/选中容器）先用 `RenderType.lines()` 正常冲刷，再单独渲染雷达线框，
  避免两者共用同一个 batch 时深度状态互相污染。
- 按需求用 `try-finally` 包裹：
  ```java
  RenderSystem.disableDepthTest();
  try {
      buffers.endBatch(RADAR_LINES);
  } finally {
      RenderSystem.enableDepthTest(); // 任何异常都必须恢复，否则整局画面穿透
  }
  ```
- 线框颜色来自 `CategoryRules.color()`：标签箱金色、模组箱蓝色、普通物品箱灰色。

**交互锁定**：
- 雷达界面点选一行 → `RadarHighlight.select(pos)`，世界中透视高亮，关闭界面仍保留；
- `RadarInteractionHandler`：右键（主手、CLIENT 侧）该箱子且**未锁定** → 取消高亮；
- `[锁定]` → `ManagerActionPacket(LOCK_RADAR)` → `ClassifierRadarState.lock()`（服务端按玩家记录）；
  锁定后右键不再取消，必须在雷达界面点 `[解除锁定]`；
- 锁定状态随快照下发到客户端（`RadarEntry.locked`），按钮文字与行内 `[锁]` 标记同步刷新；
- 退出服务器自动清除选中高亮；锁定表为内存态（重启服务器后清空）。

---

## 已知约束 / 维护提示
- 修改 `ManagerSyncPacket` 结构时务必同步升级 `ModNetwork.PROTOCOL_VERSION`，并确保客户端与服务端 jar 一并更新。
- 雷达渲染类型是静态单例，不要在类加载时就依赖渲染线程之外的状态。
- 分类者工作状态下会为分类箱所在区块额外挂一个 `SORTER_TICKET`，休闲时释放；新增 ticket 时记得在 `remove()` 中一并释放。
- `CategoryRules` 的标签推断只覆盖常见 Tag，整合包特有的标签可按需加入 `PRIORITY_TAGS`。
