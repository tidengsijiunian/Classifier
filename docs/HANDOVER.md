# Classifier 交接文档

> 写给下一个接手这个模组的 AI（或人）。
> 生成时间：2026-10-31 01:50（UTC+8）
> 对应代码状态：`src/main/java` 当前快照，`build/libs/classifier-1.18.2-0.1.0-m2.jar` 为 2026-10-30 21:12 构建的产物
> **本文档只描述代码，不改代码。** 文档里的行号对应写作时的文件内容；动手前请重新读一遍原文件。

---

## 0. 先读这一节（三分钟版）

1. 这是一个 Minecraft **1.18.2 + Forge 40.3.12** 的模组，新增生物「分类者」（`SorterEntity`），它工作时的行为是：从绑定的**分类箱**里取物品，按标签/模组找到**目标箱**放进去。
2. 项目根 `D:\Develop\1.18.2Forge Mod\classifier`，根包 `com.tidengyogvin.classifier`，47 个 Java 文件，客户端/服务端**同一个 jar**。
3. 构建：`.\gradlew.bat build`（PowerShell），产物在 `build\libs\`。JDK 17 由 `gradle.properties` 的 `org.gradle.java.home=D:/Java/Java17` 强制指定。
4. 部署：手工把 jar 复制到 `E:\ModdedWorld\.minecraft\versions\分类者\mods\`，旧 jar 先改名成 `.bak-<时间戳>` 留底；**复制前必须确认游戏已关闭**。
5. **当前最要紧的事**：2026-10-30 晚，两个审查 AI（Claude Code、Codex）做了一轮「区块加载 / 卡死 / 性能」静态审查，查出 4 个确定的 bug（见第 6 节），**代码一行没改，全部待修**。项目作者（TidengYogvin）还没拍板先修哪几条。
6. 规则以项目根 `AGENTS.md` 为准：先读后写、一次一个任务、最小改动、中文注释解释业务逻辑、报告 DONE/FILES/VERIFY 三行。**本文档不覆盖 AGENTS.md**，冲突时以 AGENTS.md 和项目作者的指令为准。

---

## 1. 项目基本信息

| 项 | 值 |
|---|---|
| 项目目录 | `D:\Develop\1.18.2Forge Mod\classifier` |
| mod_id / 显示名 | `classifier` / Classifier（中文：分类者） |
| 根包 | `com.tidengyogvin.classifier` |
| 版本 | `0.1.0-m2`（`gradle.properties` 的 `mod_version`） |
| MC / Forge / Gradle / JDK | 1.18.2 / 40.3.12 / 7.2（MDK wrapper）/ 17 |
| mappings | official（MDK 默认） |
| 本地测试环境 | `run\`（`gradlew runClient` / `runServer` 用） |
| 实机部署位置 | `E:\ModdedWorld\.minecraft\versions\分类者\mods\classifier-1.18.2-0.1.0-m2.jar` |
| 版本历史 | `docs/PROJECT.md` 里程碑 M1 → M3.0；M3.x（工作区对象化、GUI 改版、命名）没有文档记录，见本文第 7 节 |

### 1.1 硬性约束（来自 AGENTS.md，务必遵守）

- 不升级 MC / Forge / Gradle / JDK，不加第三方依赖，不改包名 / mod_id / 实体注册名。
- 所有注册走 `DeferredRegister`，所有网络包走 `SimpleChannel`，客户端专属代码放 `client` 子包。
- 改代码后跑 `.\gradlew.bat compileJava`（或 `build`），失败贴完整日志，不猜着改。
- 不执行 `git push`；不删除 `gradlew*`、`gradle/wrapper`、`settings.gradle`。
- 项目里有若干 `.bak` 文件（如 `client/ManagerScreen.java.bak`、`build.gradle.fg6.bak`），是人工备份，不要当源码编译或拿去替换现用文件。

---

## 2. 代码地图（按职责分组）

路径前缀均为 `src/main/java/com/tidengyogvin/classifier/`。

### 2.1 服务端核心

| 文件 | 职责 | 备注 |
|---|---|---|
| `Classifier.java`（61 行） | `@Mod` 入口：注册 DeferredRegister、命令、`PlayerLoggedInEvent`（登录时推工作区表 + 管理器快照） | 没有开服/进世界钩子，这是「重启后 ticket 丢失」的原因之一 |
| `SorterEntity.java`（994 行） | **核心实体**。区块 ticket、工作状态、分类箱绑定、工作区 UUID 引用、旧存档迁移、箱子类别表、雷达数据收集、NBT 读写 | 半个模组的逻辑都在这里，改动前必须全文读 |
| `SorterWorkGoal.java`（448 行） | 搬运 AI 状态机（0 待机 / 1 去分类箱 / 2 去目标箱），目标箱打分、开盖动画、放不回去时掉落 | **第 6.3 节卡死 bug 就在这里** |
| `WarehouseSorterTask.java`（475 行） | `/cls sort` 一键整理：分批扫描 → 清空目标箱 → 重新分配 → 放不下回分类箱 → 再放不下掉落 | 按 tick 预算推进，单 tick 最多 2 个容器 / 32 次搬运 |
| `WorkspaceTable.java`（267 行） | **世界级 SavedData** `classifier_workspaces`：工作区（UUID、名字、维度、两角）独立存档 | 存于主世界 `data/classifier_workspaces.dat` |
| `SorterOccupancy.java`（132 行） | 绑定时的占用扫描：分类箱坐标、工作区角点，严格独占 | 只扫**已加载**实体，见 6.5 |
| `ManagerData.java`（192 行） | 管理器界面数据：`SorterInfo` 快照、`RadarEntry`、存活过滤 `isLiveSorter`、**重名校验** | 重名只覆盖已加载实体，是已知限制 |
| `ContainerUtil.java`（198 行） | 容器统一访问：双箱归一化（LEFT→RIGHT 主半）、`getContainer`、`summarize` | **`:121` 的 `getBlockState` 没有 `hasChunkAt` 守卫**，见 6.4 |
| `CategoryRules.java` | 类别字符串格式 `tag:` / `mod:` / `item:`、首物推断、打分权重（1000 / 100 / 50 / 10 / +30）、颜色与中文名 | 改打分只改这里 + `SorterWorkGoal.getContainerScore` |
| `ClassifierManagerItem.java`（364 行） | 管理器物品：右键容器选中、Shift+右键分类者绑定（含占用校验）、右键空气开界面、工作区角点存物品 NBT | 绑定时写 `Owner` |
| `SorterCommand.java`（453 行） | `/cls`（别名 `/classification`）命令树：add / remove / list / allremove / lock / unlock / sort / bounds / tp… | 自定义 `NameArgument` 支持中文名；权限 2 |
| `ClassifierRadarState.java`（66 行） | 雷达锁定表（服务端内存态：玩家 UUID → 箱子 asLong → 分类者 UUID） | 重启清空，箱子被拆后自然失效 |
| `RadarSync.java`（64 行） | 收集当前维度雷达条目并推给「手持管理器」的玩家；单次最多 64 台分类者 | 用 `hasChunkAt` 跳过未加载箱子 |
| `ModEntities` / `ModMenuTypes` / `ItemInit` / `SorterMenu` | 注册与容器界面 | 实体注册名、刷怪蛋名不要动 |

### 2.2 网络层（`network/`，全部走 `ModNetwork.INSTANCE`）

`ModNetwork.PROTOCOL_VERSION = "2"`（`:19-20` 注释说「工作区对象化之后包格式变了」，但下面 9 个包**全部没变**；改包结构时版本号必须跟着加，且客户端/服务端 jar 必须同时更新）。

| 包 | 方向 | 用途 |
|---|---|---|
| `SelectSkinPacket` | C→S | 皮肤选玩家 |
| `ToggleWorkingPacket` | C→S | 工作 / 暂停切换 |
| `RenameSorterPacket` | C→S | 分类者改名（服务端做重名校验，名字上限 16） |
| `ManagerSyncPacket` | S→C | 分类者快照 + 雷达列表 |
| `ManagerActionPacket` | C→S | 管理器按钮：TELEPORT / TOGGLE_WORKING / UNBIND / SET_WORKSPACE / REQUEST_RADAR / LOCK_RADAR / UNLOCK_RADAR |
| `ManagerRejectPacket` | S→C | 绑定被拒时让对应行闪红 |
| `WorkspaceSyncPacket` | S→C | 全量工作区表（含绑定的分类者 UUID + 名字） |
| `WorkspaceActionPacket` | C→S | 工作区 RENAME / BIND / UNBIND |
| `CreateSorterPacket` | C→S | 创建分类者（留空走默认名顺延） |

包 ID 顺序在 `ModNetwork.register()`，**新增包只能追加在末尾**，否则新旧 jar 混用会解析错位。

### 2.3 客户端（`client/`，只在客户端加载）

| 文件 | 职责 |
|---|---|
| `ManagerScreen.java` | 管理器主界面：一级菜单「工作区 / 创建分类者 / 仓储雷达 / 关闭」，列表每行三个按钮「传送 / 工作·暂停 / 解绑」，状态灯，悬停详情 |
| `WorkspaceMenuScreen.java` | 「工作区」二级菜单：划定工作区 / 工作区管理 |
| `WorkspaceScreen.java` | 划定工作区：两角坐标、名字、选分类者（`SorterPickerScreen`）；草稿存在静态字段里，关闭不丢 |
| `WorkspaceManageScreen.java` | 工作区列表：新建 / 改名 / 解除绑定 |
| `RenameWorkspaceScreen.java` / `SorterRenameScreen.java` / `CreateSorterScreen.java` | 改名 / 创建输入框（名字上限 16，过滤器挡 `§`） |
| `SorterPickerScreen.java` | 选分类者：列表每行一个「选中」按钮 + 「用最近的一台」 |
| `SorterScreen.java` | 右键分类者打开的皮肤 / 状态界面 |
| `ManagerRenderer.java` | 世界内线框：选中容器、工作区 AABB、雷达透视高亮（自建 `NO_DEPTH_TEST` RenderType） |
| `ManagerRadarScreen.java` / `RadarHighlight.java` / `RadarInteractionHandler.java` | 雷达界面与高亮的锁定 / 取消 |
| `WorkspaceScrollHandler.java` | Alt+滚轮沿视线主轴拉伸 / 收缩工作区（90ms 防抖 → `SET_WORKSPACE`） |
| `ManagerUi` / `ManagerSyncCache` / `WorkspaceSyncCache` / `ManagerRejectCache` / `ClientSetup` | 配色与字号、三份客户端缓存、客户端初始化 |

### 2.4 资源

`src/main/resources/assets/classifier/`：`lang/zh_cn.json`、`lang/en_us.json`、管理器图标与刷怪蛋模型。**新加界面文案要同时补两个语言文件。**

---

## 3. 构建、运行与部署

```powershell
cd "D:\Develop\1.18.2Forge Mod\classifier"
.\gradlew.bat compileJava     # 每次改代码后的最小验证
.\gradlew.bat build           # 出 jar：build\libs\classifier-1.18.2-0.1.0-m2.jar
.\gradlew.bat runClient       # 在 run\ 里起客户端（调试用）
```

- 控制台中文可能乱码，不影响判断 `BUILD SUCCESSFUL`。
- 部署步骤（项目里没有脚本，手工做）：
  1. 确认游戏与启动器都没占用 mods 目录（有 java 进程时先关游戏）；
  2. 把 `E:\ModdedWorld\.minecraft\versions\分类者\mods\classifier-1.18.2-0.1.0-m2.jar` 改名为 `classifier-1.18.2-0.1.0-m2.jar.bak-<yyyyMMdd-HHmmss>`；
  3. 复制新 jar 进去；
  4. 报告里写清楚备份文件名。
- 客户端 / 服务端 jar 必须同步更新（协议版本校验会拒绝不一致的客户端）。

---

## 4. 数据结构与 NBT 契约

### 4.1 `SorterEntity` 的持久字段（`addAdditionalSaveData` / `readAdditionalSaveData`）

| 键 | 类型 | 含义 |
|---|---|---|
| `SkinPlayer` | String | 皮肤用的在线玩家名 |
| `Working` | Boolean | 实体自身的「工作中」标记（**不是**决定 tick 的唯一条件，见 5.3） |
| `ManualPause` | Boolean | 手动开关后的锁；为 true 时 `customServerAiStep` 不会自动唤醒工作 |
| `SorterBoxPos` | Long | 分类箱（双箱已归一化的主半） |
| `BoundDimension` | String | 绑定分类箱时的维度，跨维度自动解绑 |
| `Owner` / `OwnerName` | String | 归属玩家（UUID + 名字快照），创建者或绑箱者 |
| `WorkspaceId` | String(UUID) | 引用 `WorkspaceTable` 的工作区对象 |
| `WorkspaceMin` / `WorkspaceMax` / `WorkspaceDimension` | Long / Long / String | **仅旧存档**：未迁移时原样写回，首个服务端 tick 由 `migrateLegacyWorkspace`（`:719-746`）转成工作区对象，UUID 由实体 UUID 派生，重复执行不会产生第二条 |
| `BoxCategories` | List<{Pos:Long, Category:String}> | 箱子 → 类别（`tag:…` / `mod:…` / `item:…`），读档超过 10000 条直接清空 |
| `CarriedItem` | CompoundTag | 手里正在搬的物品，**重启会带回来**——这是 6.3 卡死能跨重启的原因 |

读档路径全部 try/catch，任何字段坏掉只退化为默认值，绝不让世界加载崩掉。**新增字段必须走同样的防御式写法。**

### 4.2 工作区对象（`WorkspaceTable.Workspace`）

`record Workspace(UUID id, String name, ResourceLocation dimension, BlockPos min, BlockPos max)`
- `name` 可为 null / 空 = 未命名；未命名**不参与重名比较**。
- 存于 `overworld().getDataStorage()` 的 `classifier_workspaces`，条目上限 4096。
- 实体只存 UUID 引用；跨维度时 `getValidWorkspace()` 返回 null（工作区在别的维度不生效）。

### 4.3 字段上限（改一处必须同步改另一处）

`ManagerData.MAX_NAME_LENGTH = 128`、`WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH = 32`、分类者名字在输入框 / 命令侧限 **16** 个字；`ManagerSyncPacket` 雷达最多 1024 条、`WorkspaceSyncPacket` 最多 4096 条、`SorterEntity.MAX_RADAR_ENTRIES = 512`。`encode` / `decode` 里的 `writeUtf` / `readUtf` 上限必须成对。

---

## 5. 运行时行为（接手前必须理解的部分）

### 5.1 分类者的一生

1. **创建**：管理器「创建分类者」→ `CreateSorterPacket`（生成在玩家正前方 2 格）或 `/cls add <名字>`。写入 `setOwner(玩家)`，名字走重名校验；无名字自动顺延「分类者 / 分类者-2 …」（上限 999）。
2. **绑定分类箱**：管理器右键容器（写物品 NBT `SelectedPos`）→ Shift+右键分类者 → `SorterOccupancy.scan` 校验独占 → `setSorterBoxPos` → `refreshWorkingStateFromSorterBox()`（箱里有东西就工作）。
3. **工作循环**：`SorterWorkGoal`，见 5.2；`customServerAiStep` 每 1 秒复查分类箱，空箱自动休眠。
4. **暂停 / 解绑**：管理器行内「暂停」= `toggleWorking()`（同时上 `ManualPause` 锁）；「解绑」= `setSorterBoxPos(null)`；`/cls lock|unlock` 效果相同。
5. **死亡 / 移除**：`die()` 和 `remove()` 会把手里的物品掉落；**暂停不会**（暂停后滞留在实体身上，直到重新工作或移除）。

### 5.2 状态机 `SorterWorkGoal`

`canUse()`：`isWorking() && getSorterBoxPos() != null && !isManualPause()`。

| state | 行为 |
|---|---|
| 0 待机 | 读分类箱；箱空 → 手里也为空才 `setWorking(false)`；箱非空且**手里为空** → 找目标箱，找到就取物进 1，找不到冷却 40 tick + 提示「请扩充箱子」 |
| 1 去分类箱 | 走到 2.5 格内或寻路结束 → 开盖动画 → 进 2，走向目标箱 |
| 2 去目标箱 | 到达 → `insertItemStacked` 放入；空箱先 `assignBoxCategory` 推断类别；`item:` 箱被搬空后撤销类别；然后状态归 0、冷却 20 tick |

**注意**：state 2 无论是否放进去（`targetInv == null` 或放不下），都会把 `state` 归 0 并把 `carriedItem` 留着 —— 这是 6.3 的根因。只有 `stop()` 才调 `returnCarriedItem()`（`:436-447`）。

### 5.3 区块加载（ticket）——**当前最脆弱的一块**

- 常量：`SorterEntity.SORTER_TICKET = TicketType.create("sorter_ticket", (a, b) -> 0)`（`:62-63`）。
- 只有 `isWorking() && getSorterBoxPos() != null` 时才在 `tick()` 里补票（`:126-149`）：
  - 实体所在区块：`addRegionTicket(SORTER_TICKET, chunkPos, 1, this)`（`:135`）；
  - 分类箱所在区块：`updateBoxTicket` → `:170` 同样 distance 1。
- 切回休闲、`setWorking(false)`（`:340-342`）、`remove()`（`:766-779`）、读档后第一个 tick（`pendingLoadTicketReset`，`:119-124`）都会释放 ticket。
- **ticket 不持久化**：重启后没人去过的远处区块不会加载，实体不 tick → 不会补票 → 要等玩家走近一次才恢复。
- **全项目没有使用 `ForgeChunkManager`**，所以这些 ticket 不算「Forge 强制加载」。
- `removeWhenFarAway()` 返回 false：普通区块卸载时实体不会被删除，但会随区块卸载而**停止 tick**。

### 5.4 雷达与工作区同步

- 雷达数据源：`SorterEntity.getManagedBoxes()`（`BoxCategories` 里未加载的只跳过不删；箱子被拆则清理）。实体每 40 tick 算一次签名，变了就推给「手持管理器」的同维度玩家。
- 工作区：`WorkspaceSyncPacket.broadcast` 在全量变更后广播；`WorkspaceScreen` 保存 = `SET_WORKSPACE`（带目标分类者 UUID + 名字）。
- 严格独占：分类箱按坐标精确相等、工作区按归一化两角精确相等（不判区域重叠）。

---

## 6. 已知 bug（2026-10-30 审查结论，**代码未修**）

> 本节是两位审查者（Claude Code、Codex）交叉核对后的结论。作者尚未决定修哪几条。**修之前先确认你要修的是哪一条、是否与作者的规划冲突。**

### 6.1 确认：分类者自己的区块只加载到「方块 tick」，实体不 tick

- 位置：`SorterEntity.java:135`（实体区块）、`:170`（分类箱区块），distance 都是 `1`。
- 依据：1.18.2 `DistanceManager.addRegionTicket` 的 ticket level = `33 - distance` = 32，等于 `TICKING`；实体要 tick 需要 `ENTITY_TICKING`（level ≤ 31，即 distance ≥ 2；原版 `/forceload` 用的就是 31）。
- 后果：玩家走远后区块不卸载但实体不跑 → 分类者停工、雷达不刷新。
- 建议：实体那条改 distance `2`（箱子那条 `1` 够用，方块实体在 32 级会跑）。**一行改动，但会提高常加载成本，需要作者拍板。**

### 6.2 确认：ticket 比较器恒为 0，同区块两台分类者共用一个 ticket

- 位置：`SorterEntity.java:62-63`，`(a, b) -> 0`。
- 后果：同区块两台分类者只存在一张票；一台释放就把另一台的加载凭据删掉，而另一台的 `sorterTicketChunk` 还记着这个区块（`:131` 判断），**不会补挂**。
- 建议：比较器改为按实体 UUID 比较，`compareTo` 为 0 当且仅当同一实体。

### 6.3 确认：手里有货 + 没有目标箱 / 放不下 → 永久卡死（会跨重启）

- 位置：`SorterWorkGoal.java:145-166`（放不下仍归 0）、`:97-118`（state 0 只在手里为空时找箱子）、`:89-95`（箱空时只在手里为空才休眠）；`SorterEntity.java:863-865`（`CarriedItem` 写 NBT）。
- 后果：每 tick 空转、永不退出工作状态，物品永久挂在实体上；重启后从 NBT 带回来。
- 建议：在 **state 0 开头**加一条「手里有货」分支——重新找目标箱，找不到就 `returnCarriedItem()`（塞回分类箱，塞不下掉落）再清零。复用现成的 `returnCarriedItem()`。
- **优先级最高、风险最低、改动最小的一条。**

### 6.4 确认：工作区扫描会同步加载区块（主线程卡顿）

- 位置：`SorterWorkGoal.java:205-232` 逐格 `ContainerUtil.getChestAnchor` → `ContainerUtil.java:121` `level.getBlockState`，**没有 `hasChunkAt` 守卫**；`WarehouseSorterTask.java:160`、`:172` 同样漏了。
- 后果：未加载区块在服务端线程被同步加载；工作区上限 32768 格，`180×180×1` 就横跨约 121 个区块，而且**每拿起一件物品都扫一遍**。
- 已做守卫的对照：雷达 `SorterEntity.java:549/597`、占用 `SorterOccupancy.java:119-120`、创建 `CreateSorterPacket.java:131`。
- 建议：扫描循环先 `hasChunkAt` 再读方块；工作区扫描可以按 chunk 维度缓存「已加载」判断，不要逐格判定。

### 6.5 确认（设计限制）：名字唯一性 / 占用 / 列表都只看「已加载实体」

- 位置：`ManagerData.java:116-143`、`SorterOccupancy.java:95-116`、`WorkspaceActionPacket.java:132-144`。
- 现状：休闲状态的远处分类者不加载 → 扫不到 → 可能重名；「工作区管理」里绑定分类者时 `ManagerData.find` 也找不到 → 报「分类者不存在或不在已加载区块」。
- 已排除的误判：删除后的死实体被 `isLiveSorter` 过滤；没自定义名的实体（默认名「分类者」）不占名字；死亡动画期间（`getAllEntities` 暂时还返回）也不占名字。
- 待作者拍板：要不要做持久名字表。**不要自己决定。**

### 6.6 确认：维度里没有玩家时，整个 level 的实体 tick 会停

- 依据：Forge 在 `ServerLevel.tick` 里的补丁——没玩家且 `!ForgeChunkManager.hasForcedChunks` 满 300 tick（15 秒）就跳过实体 tick 循环。
- 后果：单机在主世界时，下界 / 末地的分类者 15 秒后全停；专用服无人时全服停。
- 关联：只有改用 `ForgeChunkManager` 持久票才能顺带解决 6.1 的「重启恢复」和本条。

### 6.7 疑似（未实证，需要进游戏验）

- 区块卸载时实体不走 `remove()`；一旦 ticket 提前丢失（6.2 / 重启），票和实体就再也接不上——要么区块永远为它加载着，要么它永远不醒。
- 验证方法：让分类者开始搬运，玩家跑开 200 格等 30 秒，再回去看它在不在动、调试日志有没有输出。

### 6.8 已排除（别浪费时间重查）

- 「目标箱区块卸载 → `getBlockEntity` 返回 null → 卡死」：不成立。1.18.2 的 `Level.getBlockEntity` → `getChunk(x, z, FULL, true)` 会**同步加载**未加载区块，`ServerLevel` 没覆写。真实后果是 6.4 的性能问题；卡死是 6.3 的另一条路径。
- 「新实体还没进 `getAllEntities` 就被拿去做重名判断」：不成立，`addFreshEntity` 在区块可访问时同步 `startTracking`。
- 「注销不配对」：`setWorking(false)`（`:341`）、`remove()`（`:766-779`，死亡 / `discard` / 跨维度都走这里）都释放 ticket，**当前没有泄漏**。改 6.1 / 6.2 时别把这里改坏。

---

## 7. 作者已提需求与当前实现状态的对照（**最容易踩坑的一节**）

2026-10-30 17:02–17:09，作者提了一轮 GUI / 命名需求，分两轮落地。**代码里只完成了其中一部分，别把没做的当已做。**

| 需求 | 状态 | 证据 |
|---|---|---|
| 一级菜单把「划定工作区」换成「工作区」 | ✅ 已完成 | `ManagerScreen.java:92-105`、`WorkspaceMenuScreen` |
| 二级菜单：划定工作区 / 工作区管理 | ✅ 已完成 | `WorkspaceMenuScreen.java:34/38` |
| 一级菜单加「创建分类者」 | ✅ 已完成（按裁定方案 A） | `ManagerScreen.java:101-105` |
| 分类者起名、留空默认「分类者」 | ✅ 已完成 | `CreateSorterScreen` + `CreateSorterPacket` |
| 分类者名称不可重复、重复给提示 | ✅ 已完成（**只覆盖已加载实体**，见 6.5） | `ManagerData.isSorterNameTaken` |
| 工作区可命名、可无名、不许重名 | ✅ 已完成 | `WorkspaceTable.isNameTaken` / `normalizeName` |
| 工作区列表：新建 / 选中 / 改名 / 解绑 | ✅ 已完成 | `WorkspaceManageScreen` |
| **在划定工作区界面里现场新建分类者并绑定** | ❌ **未实现**：该界面的「选分类者」只进 `SorterPickerScreen`（挑已有），没有「新建」入口 | `WorkspaceScreen.java:147-152` |
| **选中工作区后「标记」只显示该工作区的框线** | ❌ **未实现**：现在仍是「手持管理器时显示物品 NBT 里的工作区线框」，没有选中状态、没有框线开关 | `ManagerRenderer.java:131`、`WorkspaceScrollHandler` |
| **可同时标记多个 / 取消单个 / 取消全部** | ❌ 未实现 | 无相关代码 |
| **框线显示到玩家手动取消为止（玩家各记各的）** | ❌ 未实现 | 无相关代码 |
| 「分类者-2 被删后名字可重新分配」 | ✅ 已完成（名字不持久，跟着实体走，删掉即释放） | `ManagerData.collectSorterNameKeys` |

第二轮（标记 / 框线）Claude Code 已声明「停在验收口没往下做」，作者也没说开第二轮。**下一个 AI 接手前先问作者：本轮是修 bug（第 6 节）还是做第二轮（本节未实现的四条）。**

---

## 8. 接手清单（建议顺序）

1. 读 `AGENTS.md` → 本文 → `docs/PROJECT.md`（后者是旧文档，第 7 节有它没覆盖的内容）。
2. 手动读一遍 `SorterEntity.java`、`SorterWorkGoal.java`、`ContainerUtil.java`、`ManagerData.java`——所有 bug 都在后三个 + `SorterEntity` 的 ticket 段。
3. 跑一次 `.\gradlew.bat compileJava`，确认基线能编过。
4. 问作者两件事：**(a) 先修第 6 节哪几条**（建议 6.3 → 6.2 → 6.1 → 6.4；6.1 会提高常加载成本，要他点头）；**(b) 这一轮是否要做第 7 节剩下的四条 GUI 需求**。
5. 改任何一条之前：先读后写、最小改动、改完 `compileJava`、报告 DONE / FILES / VERIFY 三行。
6. 改完要部署时，按第 3 节的备份步骤走，**先确认游戏关着**。
7. 不要主动扩大范围：持久名字表（6.5）、`ForgeChunkManager` 持久票（6.1 / 6.6）都是作者没批准的大改动。

---

## 9. 写代码时的约定（已有事实，照做即可）

- 服务端判定、客户端只做即时提示与缓存（重名、占用、权限都在服务端复核）。
- 所有实体查找都过 `ManagerData.isLiveSorter`，否则会把死实体当占用者。
- 读方块状态前先 `hasChunkAt`，**除了第 6.4 节那三个已知漏点**（修的时候正好补上）。
- 新 NBT 字段：默认值安全、读档 try/catch、写入前判 null。
- 新网络包：追加在 `ModNetwork.register()` 末尾、`PROTOCOL_VERSION` 加一、`writeUtf` / `readUtf` 上限成对。
- 中文注释解释业务逻辑，标识符英文；4 空格缩进；不用通配符 import。
- 已知小事（不是 bug，顺手可提，不要擅自改）：
  - `ManagerActionPacket.java:318-321`：`explicitTarget != null` 分支先 `WorkspaceSyncPacket.broadcast` 再 `return`，跳过了后面的 `player.containerMenu.broadcastChanges()`；
  - `WorkspaceActionPacket` 每次改名都向所有在线玩家广播全量工作区表。
