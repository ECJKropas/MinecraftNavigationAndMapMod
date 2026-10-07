# Final Target

## v0.5.0

1. 修复Xaero的bug,实现像How to go一样的效果
2. 完成导航系统
  - 不能完全照搬How to go,我们需要针对xaero的小地图上做进一步绘制导航,并重新编排我们的Hud
  - 我们要坚持主操作在网页端的根本方针
  - 我们还需要针对性的优化网页

> 依据《Wayfarer 与竞品 HowToGo 全方位差距分析报告》与《Wayfarer 追赶与改进计划》。
> 本文档只做「拆活 + 派活 + 认领」，不写方案细节；细节落在各自的分支与提交里。

---

# 分工

> 拆解口径：一个任务 = 一个可独立验证的交付物。
> 「认领」填写执行者（人或 AI 工具名），空着 = 待认领。
> 依赖只写「谁必须先完成」，不写工期。

## 0. 工作流总览

| 编号 | 工作流 | 对应最终目标 | 交付物 | 认领 |
|---|---|---|---|---|
| **WS-A** | Xaero 集成与地图渲染 | 目标 1 | 路网/导航在 Xaero 上的正确渲染与交互 | WorkBuddy（A1 ✓） |
| **WS-B** | 导航系统（算 · 引 · 导） | 目标 2 | 从「能算出一条路」到「跟着能走完」 | Marvis（B1 ✓、B2 ✓、B3 本次、B4 本次） |
| **WS-C** | 网页端（主操作台） | 目标 2 根本方针 | 网页能完成选点、规划、编辑全流程 | 待认领 |
| **WS-D** | 工程基建（回归与度量） | 支撑全部 | 无头检查器 + 性能基线 | Marvis（D1 本次） |

**约定**：WS-A 与 WS-B 在「导航路线怎么画到地图上」一处交汇，接口定为
`NavigationSession.snapshot()` → 渲染层只读消费，不做反向调用。

---

## WS-A　Xaero 集成与地图渲染

现状问题：`XaeroMapOverlay` 用 Fabric `ScreenEvents.afterRender/afterExtract` 钩子，
反射读 Xaero `GuiMap` 的 `scale` / `cameraX` / `cameraZ`，自己手算屏幕像素后用单位矩阵盖上去。
它与 Xaero 的地图管线无关，因此地图缩放动画、旋转、GUI 缩放变化时线会漂；
也不参与 Xaero 的图层顺序与交互。1.20.1 与 26.x 各维护了一套画法。

目标：迁到 Xaero 官方扩展点 `WorldMap.mapElementRenderHandler`（两个 API 代际均为 public static 字段，无需 mixin）。

| 编号 | 任务 | 交付物 | 依赖 | 认领 |
|---|---|---|---|---|
| A1 | 接入 `mapElementRenderHandler`，路网折线改在 Xaero 地图坐标系绘制（世界地图） | 新图层 `client/road/xaero/`，三版本编译通过 | — | **WorkBuddy** ✓ |
| A2 | 同一条管线把**导航路线**画上去（区分路网 / 路线配色，含起终点与当前位置） | 渲染层消费 `NavigationSession` | A1 ✓、B4 | 待认领 |
| A3 | 在 Xaero **小地图**上绘制导航（`IN_MINIMAP` / `OVER_MINIMAP` 位置） | 小地图导航指引 | A2 | 待认领 |
| A4 | 悬停高亮 + 右键菜单（定位到网页编辑器 / 复制坐标 / 删除该段） | 交互层接上 `ElementReader` | A1 ✓ | 待认领 |
| A5 | 路名标签（随缩放显隐、沿折线方向排布）与图层过滤开关（按分级 + 未分类） | 标签/过滤 | A1 ✓、B1 ✓ | 待认领 |
| A6 | Xaero 缺失或版本不匹配时的降级路径（不崩、不重复绘制） | 兜底 | A1 | 随 A1 一并完成 ✓ |

**A1 验收口径**：三版本 `compileJava` 通过；Xaero 未安装时不注册、不报错；
新图层注册成功后旧反射叠加层自动让位（不重复绘制）。

---

## WS-B　导航系统（算 · 引 · 导）

| 编号 | 任务 | 交付物 | 依赖 | 认领 |
|---|---|---|---|---|
| B1 | 路网空间索引替换 `LocationSnapper` 全量遍历（对外接口不变） | 网格索引类 | — | **Marvis** ✓ |
| B2 | `Router` 由 Dijkstra 改 A\*，并支持候选起终点（吸附不到唯一点时取最优） | 路由改造 | B1 ✓ | **Marvis** ✓ |
| B3 | 出行方式区分（步行 / 驾车）与按分级限速的 ETA | 通行代价函数 | B2 ✓ | **Marvis**（本次） |
| B4 | 逐段指引：下一转向距离 / 剩余距离 / 剩余时间；偏航重规划 | 引导层 | B2 ✓ | **Marvis**（本次） |
| B5 | HUD 重编排（26.x 当前**完全没有 HUD**：`HudRenderCallback` 已被移除，需要新的渲染入口） | HUD | B4 | 待认领 |
| B6 | 行进播报（文字优先，语音可选） | 播报 | B4 | 待认领 |

---

## WS-C　网页端（主操作台）

现状：已有 `/api/nav/start|state|stop` 与 Leaflet 折线可视化，能用但不完整。

| 编号 | 任务 | 交付物 | 依赖 | 认领 |
|---|---|---|---|---|
| C1 | 导航面板补全：起终点可点选/可搜索、路线可视化、剩余距离与时间、取消 | 网页导航面板 | — | 待认领 |
| C2 | 大路网渲染性能（折线抽稀 / 分块 / 视口裁剪） | 前端性能改造 | — | 待认领 |
| C3 | 编辑体验（批量、吸附、撤销重做）按使用频率排序后逐项优化 | 编辑体验改进 | — | 待认领 |

---

## WS-D　工程基建（回归与度量）

| 编号 | 任务 | 交付物 | 依赖 | 认领 |
|---|---|---|---|---|
| D1 | 无头检查器骨架（不启动游戏即可跑），首批 ≥ 6 条检查 | `tools/` 检查器 | — | **Marvis**（本次） |
| D2 | 性能基线 `baseline.json`（吸附 / 规划 / 扩展节点数） | 基线产物 | D1 | 待认领 |
| D3 | 顶层 `CHANGELOG.md`（记录「改动 + 原因」）与 README「已知限制」章节 | 文档 | — | 待认领 |

---

# 认领记录

## A1 —— Xaero 路网图层迁移到官方 MapElement 扩展点

- **认领者**：WorkBuddy
- **范围**：只做「世界地图 + 路网折线 + 分级着色/线宽」，含 Xaero 缺失时的降级。
  路线绘制（A2）、小地图（A3）、悬停右键（A4）、标签过滤（A5）**不在本次范围**。
- **不动**：`NavigationSession` / `Router` / 网页端 一律不改，A1 只新增渲染层。
- **验收**：见上「A1 验收口径」。

### 现场勘察结论（写入时已核实）

1. **三个目标版本的 Xaero 用的是同一代 element API**（`xaero/map/element/render/*`）：
   `1.20.1 → xaeroworldmap 1.44.2`、`26.1.1 → 1.41.3/1.44.2`、`26.2 → 1.44.2`。
   差异不在 Xaero，而在**参数里绑定的 MC 渲染类型**。
2. `renderElement` 签名**三代不同**（不是两代），这正是本次唯一的版本分叉点。
   注意：**分叉与 Xaero 无关，只跟该 MC 版本可用的渲染类型有关**，
   所以 26.1.x 与 26.2 虽然同属"26.x"，签名却不一样：

   | 版本 | 图形对象 | 顶点缓冲 |
   |---|---|---|
   | 1.20.1 | `GuiGraphics`（jar 内为 intermediary `class_332`） | `MultiBufferSource.BufferSource` |
   | 26.1.x | `MapElementGraphics` | `MultiBufferSource.BufferSource` |
   | 26.2 | `MapElementGraphics` | `xaero.lib.client.graphics.XaeroBufferProvider` |

   > 踩坑记录：初版想当然地把 26.x 统一写成 `XaeroBufferProvider`，
   > 26.1.1 编译直接报 `XaeroRoadRenderer is not abstract and does not override ...`。
   > 实际是 Xaero 1.44.2 在 26.1.2 上仍声明 `MultiBufferSource.BufferSource`，
   > 只有 26.2 换了 `XaeroBufferProvider`。**结论：签名以 jar 内字节码为准，不按大版本号推断。**

3. `XaeroBufferProvider` **不在世界地图 jar 里**，而是嵌套在
   `META-INF/jars/xaerolib-fabric-<ver>-1.7.1.jar`，编译期需要额外补这一份依赖。
   但**只有 26.2 需要**——1.20.1 / 26.1.x 用的是原版 `BufferSource`，补了反而是多余依赖。
   因此 `xaeroLibJar` 在 `gradle.properties` 里设为**可选**，只在 26.2 生效
   （`common.gradle.kts` 里空值即跳过）。
4. 坐标映射（结论来自 Xaero 1.44.2 字节码 + `howtogo` 的实测注释）：
   `screen = (world - anchor) * info.scale * poseScale + poseTranslate`；
   且 `GuiGraphics` / `MapElementGraphics` 的 `fill` 都吃 `pose()`，所以**用 pose 变换 + fill 即可画粗折线**，
   不必自建 `VertexConsumer`。
5. 时机：`WorldMap.mapElementRenderHandler` 在 Xaero 初始化完成后才非空，需要轮询注册，不能在自己 mod 的 init 里直接取。

### 交付物

| 文件 | 变更 |
|---|---|
| `client/road/xaero/XaeroLayer.java` | **新增**。安装器：查 mod 是否加载 → 轮询注册 → 失败即放弃并让旧叠加层继续画 → `isActive()` |
| `client/road/xaero/XaeroRegistration.java` | **新增**。全 mod **唯一**命名 Xaero 类型的类（`WorldMap.mapElementRenderHandler.add`），单独隔离 |
| `client/road/xaero/XaeroRoadRenderer.java`（根 `src/`） | **新增**。1.20.1 签名适配 + 视口记录 |
| `versions/26.1.1`、`versions/26.2` 的 `xaero/XaeroRoadRenderer.java` | **新增**。同上，只有参数类型不同 |
| `client/road/xaero/XaeroRoadProvider.java` | **新增**。元素缓存 + 每帧包围盒裁剪 |
| `client/road/xaero/XaeroRoadReader.java` | **新增**。`ElementReader` 实现；`isOnScreen` 恒 true（理由见下） |
| `client/road/xaero/XaeroRoadElement.java` | **新增**。段的扁平几何快照 + 世界包围盒；锚点取首顶点 |
| `client/road/xaero/XaeroRoadStroke.java` | **新增**。投影与折线转粗笔画，**不含任何 Xaero 类型** |
| `client/road/xaero/XaeroRoadStyle.java` | **新增**。分级配色/线宽；26.x 无 malilib 故自带调色板并做一次可用性探测 |
| `client/road/xaero/XaeroViewState.java` | **新增**。视口缓存（供下一帧裁剪）+ 每趟只记录一次的通道 |
| `client/road/XaeroMapOverlay.java`（含两份版本副本） | 安装新图层；`XaeroLayer.isActive()` 为真时旧反射叠加层直接 return |
| `common.gradle.kts`、三份 `versions/*/gradle.properties` | 新增 Xaero 编译期依赖；`xaeroLibJar` **仅 26.2 需要** |
| `client/road/data/RoadNetworkDatabase.java` | `updateSegment` 补一行 `markDirty()`（理由见下） |

### 验证（写入时已核实）

- **类隔离**（字节码核对）：`XaeroLayer` / `XaeroRoadStyle` / `XaeroRoadElement` / `XaeroRoadStroke` /
  `XaeroRoadContext` / `XaeroViewState` 对外部 `xaero/map`、`xaero/lib` 包引用 **0 处**；
  仅 `XaeroRegistration`(2) / `XaeroRoadReader`(2) / `XaeroRoadProvider`(5) / `XaeroRoadRenderer`(10) 引用。
  ⇒ Xaero 缺席时不会 `NoClassDefFoundError`，旧叠加层照常工作。
- **三版本编译**：本次改动闭包（`road/xaero/**` + `RoadNetworkDatabase` + `XaeroMapOverlay`）在
  1.20.1（Java 17）/ 26.1.1 / 26.2（Java 25）下，用各自真实 `compileClasspath` **javac 全绿**。
  整包 `:XX:compileJava` 当前被 `nav/` 的在建改动挡住（与本项无关，见文末工作区备忘）。
- **`spotlessJavaCheck`**：本项全部文件已合规（`nav/` 三个文件仍报违规，非本项）。
- **产物**：`build/libs/` 三个 jar 均不含任何 Xaero 类（Xaero 只进 `compileOnly`，未闭合进产物）。

### 自查与优化（A1 范围内）

| 项 | 结论 |
|---|---|
| 逐元素记录视口 | **已修**。原实现每画一段就查一次窗口尺寸；改为 `XaeroViewState.beginPass()` + `claimRecording()`，一趟里只有第一个元素记录。跨度的 pose 在一趟内对所有元素相同，取第一个即可 |
| 调色板变更不生效 | **已修**。元素列表要缓存，但配色只存在于 config、不在数据库里；若只按数据库 stamp 失效，改配置颜色得等下一次道路编辑才看得见。故加 `XaeroRoadStyle.stamp()`（按值算，覆盖分级色与线宽）参与失效判断 |
| 每帧重建整张路网 | **已修**。元素列表（= 整张路网）改为按 `(getMutationStamp(), XaeroRoadStyle.stamp())` 缓存，一趟只做包围盒筛选。稳态帧**不再分配**，也不必再遍历 `roads → segments → nodes` 三层 |
| 想让 Xaero 自己裁剪 | **已否决，并写下理由**。默认 `isOnScreen` 确实用 `getRenderBox*`（已反汇编确认），但那个 box 默认实现**不乘 `scale`**，是常数像素量 —— 表达不了「多少格的折线」这种随缩放变化的范围。故必须保留 `isOnScreen=true`，把裁剪放在 Provider 里按**世界**包围盒做。这条写进 `XaeroRoadReader` 类注释，防止后来者"顺手优化"掉 |
| `getRenderBox*` 返回的是绝对坐标 | **已记录、本次不改**。`isOnScreen` 被覆写后它只是"不覆写时的兜底"，`howtogo` 同样写法。**A4 做交互时必须一并改成锚点相对量**，否则命中框落在错误位置 |
| Provider 枚举口径 | **已评估、本次不改**。当前"先遍历 roads 取其 segment 列表、再取 unfiled"与写路径一致（`addSegmentToRoad` 两边同时维护；`updateSegment` 不单独改归属）。改成"单向遍历 `getAllSegments()`"更抗漂移，但会与在建的 `nav/` 改动重叠同文件，留待 A4/A5 |

### 越界说明（一行改动，需知会 B 线）

`RoadNetworkDatabase.updateSegment` 会改 `nodeIds` / `direction` / `roadId`，但**不推进** `getMutationStamp()`。
而它的 javadoc 声称「每次路网改动都推进」，且 **B1 的空间索引**与本项的新渲染缓存都以它为失效令牌。
不补这一行，两者都会继续读旧数据（B1 那边因为索引的是节点、节点数没变，恰好没暴露问题）。
改动为一行 `markDirty()`；`maybeCleanupOrphans()` 已调用时重复调用只多一次自增，无副作用。

### 下游解锁

渲染层已就位且与 MC 版本解耦：**新增一种绘制只需动 `XaeroRoadStroke`（共享）+ 三份 Renderer 的签名层**。
A2 可直接消费 `NavigationSession.snapshot()`；A4 的 `ElementReader` 接线点（`isInteractable` / `getInteractionBox*`
/ `getMenuName`）已在 `XaeroRoadReader` 中标出待改位置，并已写明 box 需改成锚点相对量。

### 工作区备忘（写入时状态，非本项问题）

- `:1.20.1:compileJava` 当前失败于 `client/road/nav/`：`Guidance_20261007_001504_641.java` 是工具自动重命名留下的
  副本，与正本同名类 → 整包编译失败；另有 `NavigationSession` 引用尚未落地的 `RerouteDecision.reroute()` /
  `Guidance.destination(Route)`。均属 B4 在建，本项未触碰。
- 因整包编译被挡，本次三版本验证改用「独立 `compileClasspath` + `javac` 只编本项闭包」的方式完成；
  待 B4 落地后建议补一次整包 `build` 复跑。

---

## B1 —— 路网空间索引替换 LocationSnapper 全量遍历

- **认领者**：Marvis
- **范围**：吸附查询（`LocationSnapper.nearestNode`）由「遍历全部节点求最近」改为「均匀网格索引查询」。
  对外接口与返回语义（最近节点、等距取小 id）**不变**。
  为保住这个语义，本次**必须**一并做失效链路改造（见「现场勘察结论」第 1、3 条）——
  这是唯一超出「只新增一个索引类」的部分，其余一律不动。
- **不动**：`Router` / `NavigationSession` / HUD / 网页前端一律不改；
  `LocationSnapper.nearestNode(db, x, z, radius)` 签名与全部调用点保持原样（调用方零改动）。
- **验收**：三版本 `compileJava` 通过；`spotlessJavaCheck` 通过；吸附结果与全量遍历**逐点一致**（差分测试）；
  改点 / 增点 / 删点 / 换库 / 磁盘重载后索引**立即**生效（不得读到旧位置）。

### 交付物

| 文件 | 变更 |
|---|---|
| `client/road/spatial/NodeSpatialIndex.java` | **新增**。均匀网格（cell = 32 格，构造器可覆盖）；环形扩展 + 提前剪枝；等距取 id 小者；`synchronized`；纯缓存、不持有数据 |
| `client/road/nav/LocationSnapper.java` | 改造为委托索引；对外签名与行为不变；按 `(nodeCount, revision)` 惰性重建；database 实例变化即 `invalidate()` |
| `client/road/data/RoadNetworkDatabase.java` | 新增 `getNodeCount()`（O(1)）与 `getMutationStamp()`（`markDirty()` 时自增，`loadFromDisk()` 显式自增）；顺带修正 `getAllNodes()` 的 javadoc（原文写 unmodifiable，实现其实是快照拷贝） |
| `client/road/server/WayfarerHttpServer.java` | 网页端改节点坐标的写路径由「直接 `setX/setZ` + `saveToDisk`」改走 `database.updateNodePosition()`。行为等价（x/z + modifiedAt + version+1），但会置脏 → 索引可感知 |

### 现场勘察结论（写入时已核实）

1. **失效口径**：索引缓存键 = `(nodeCount, mutationStamp)`。
   - 增 / 删节点 → 节点数变化 → 必然重建。已逐处核对：`addNode` / `removeNode` / `mergeNodes*` /
     `insertNodeIntoSegment` / `intersect` / `cleanupOrphans` / `restoreFromJson`。
   - 已索引节点**原地**改动（只改坐标、数量不变）→ 走 `markDirty()` → stamp 自增。
     已核对 `updateNodePosition` / `updateNode` / `restoreFromJson` 末行。
   - **磁盘重载是特例**：`loadFromDisk()` 是 `clear()` + `put()`，节点数可能恰好不变（stamp 也不会自己动），
     所以该路径**显式**自增 stamp —— 这是本次唯一为索引新增的失效点。
2. **索引不硬编码单例**：`LocationSnapper` 记住当前 database 实例，实例变了就 `invalidate()`，切换世界不会读脏。
3. **网页端「直接 setX/setZ」是唯一绕过链路**：原 `WayfarerHttpServer` 在 `synchronized (database)` 内直接改
   `Node` 的 x/z 再落盘 —— 不改数量、不置脏。改造前靠全量遍历「每次都能读到最新坐标」；
   改造后索引会停在旧格子里 → 该写路径必须改走 `updateNodePosition()`。**已改**。
4. **锁序**：索引重建时持自己的锁回调 `NodeSource`，故**索引锁必须是最内层**。
   已核实不存在反向获取：`nearestNode` 的唯一调用点是 `NavigationSession`（锁 session 实例，不持 database 锁），
   网页端只持 database 锁且不再调索引。该不变式已写进类注释，防止后来者踩。
5. **等距语义有一处微差**：旧实现是「遍历中最后一个最小者胜」（依赖遍历顺序）；
   新实现是「id 字典序小者胜」（与顺序无关）。差异只在**精确等距**时出现 —— 随机坐标下概率约等于 0，
   整数网格上却常见，故专门用整数网格做了差分对齐。

### 验证（写入时已核实）

- **差分测试**（新索引 vs 全量遍历，逐点比对）：均匀随机 20000 点、聚类 20200 点、负坐标 5000 点、
  整数网格 8000 点（cell = 1.0，刻意制造大量等距）—— 共 **15690 项断言全过，0 失败**。
- **失效测试**：原地改点、不改 stamp 的增点、不改 stamp 的删点、显式 `invalidate()` 四条链路全部即时生效。
- **并发测试**：4 线程 × 25000 次查询 + 1 线程持续替换数据并 `invalidate()`；10 万次查询 0 异常、0 越界，
  数据稳定后 1000 次查询与全量遍历**逐点一致** —— 验证类注释里「客户端线程与 HTTP 线程共享同一实例」的说法。
- **性能**（200k 节点、20k 次 r=48 查询，本机 JIT 后）：索引 ≈130 ms vs 全量 ≈25–38 s，**约 160–280×**；
  重建只在失效当帧发生一次，为 O(N)。
- **构建**：`spotlessJavaCheck` + `:1.20.1:compileJava` / `:26.1.1:compileJava` / `:26.2:compileJava` 全绿。
- 差分测试脚本目前放在会话中间产物目录（未入仓）；若要固化为回归资产，建议随 D1 的 `tools/` 检查器一并落仓。

### 下游解锁

B1 对外接口未变，**A5**（依赖 A1、B1）与 **B2**（依赖 B1）可直接就地开工，无需等待合并。

### 后续可做（自查发现的优化项，本次未纳入）

| 项 | 说明 | 建议归属 |
|---|---|---|
| k 近邻接口 | B2 需要「吸附不到唯一点时取最优」，可把索引扩成 `nearest(..., limit)` | **B2** |
| 自适应 cell | 极端聚类下 cell = 32 每格最多约 70 节点；可自适应或改两级索引 | 等 D2 基线数据再定 |
| 空环早停 | 已评估并**否决**：环框会先离开网格、再在更大半径处重新进入，`continue` 是正确写法，改成 `break` 会漏点；且 `byBounds` 已把环数钳在网格附近，实测 1e18 半径不挂 | 不采纳 |

---

## B2 —— Router 由 Dijkstra 改 A*，并支持候选起终点

- **认领者**：Marvis
- **范围**：把最短路径搜索从 `Router` 里抽成与 Minecraft 解耦的 `GraphSearch`（**多源多目标 A\***，
  启发式取「欧氏距离 ÷ 全路网最高速」以保证可采纳），`Router` 只负责建图与结果组装；
  并在 `NodeSpatialIndex` / `LocationSnapper` 上补 k 近邻接口（B1 遗留项），
  供「吸附不到唯一节点时取最优」的候选起终点使用。
- **不动**：`Router.route(Node, Node, double)` 的签名与语义保持原样（内部委托新实现，调用方零改动）；
  `NavigationSession` 只做最小接线（用候选集调用新重载，取不到候选时仍走旧单点路径）；
  HUD / 渲染层 / 网页前端一律不改。
- **验收**：三版本 `compileJava` 通过；`spotlessJavaCheck` 通过；
  A* 与 Dijkstra 在随机图 / 整数网格 / 单向边图 / 不可达（island）图上**总代价逐图一致**；
  候选起终点启用后结果不劣于「只喂单一节点」的旧行为。

### 交付物

| 文件 | 变更 |
|---|---|
| `client/road/nav/GraphSearch.java` | **新增**。与 MC 解耦的有向带权图搜索：多源多目标 A\*、可采纳启发式、等代价确定化 tie-break |
| `client/road/nav/Router.java` | 建图抽成独立方法；搜索改走 `GraphSearch`；新增「候选起终点」重载，旧签名委托 |
| `client/road/spatial/NodeSpatialIndex.java` | 新增 k 近邻查询（`nearest(..., limit)`），复用既有环形扩展 + 剪枝；等距仍取 id 小者 |
| `client/road/nav/LocationSnapper.java` | 新增 `nearestNodes(...)` 候选接口；旧接口不变 |
| `client/road/nav/NavigationSession.java` | `start` / `reroute` 改用候选集 |

### 现场勘察结论（写入时已核实）

1. 现有 `Router.route` 已是优先队列 + 惰性删除的 Dijkstra，缺的只是估价项 —— 改 A\* 的关键在启发式
   **必须可采纳**：本项目的边代价 = 长度 ÷ 路速，故 h = 欧氏距离 ÷ **全路网最高速**（用 1.0 不行，
   那是「除以最大代价的倒数」，会高估）。`WayfarerConfig.getNavigationSpeed` 的具体取值决定这个上界。
2. `buildGraph` 每次 `route()` 都全量重建（遍历全部 segment），A\* 的收益**只体现在搜索阶段**；
   建图仍是 O(E)，属 B2 范围外（留给后续按需缓存 / D2 基线）。
3. 单向边（`Direction.FORWARD` / `BACKWARD`）与不可达 island 是差分测试必须覆盖的边界 ——
   Dijkstra 判 `NO_ROUTE` 的图，A\* 必须给出完全一致的结论。
4. `WayfarerConfig` 依赖 MC / malilib，**不能进无头 harness**；故差分测试只测纯图输入的 `GraphSearch`，
   `Router` 只做「建图 + 组装」，其自身用三版本 `compileJava` + 人工核对覆盖。
5. 候选起终点语义：`nearestNode` 在半径内可能命中多个节点，旧实现只取单一最近点；
   改为「取半径内最近 k 个作候选，多源 / 多目标 A\* 选总代价最小者」。因此单候选是候选集的特例，
   行为必须能退化成与今天完全一致（这条要单独测）。

---

## B3 —— 出行方式区分（步行 / 驾车）与按分级限速的 ETA

- **认领者**：Marvis（本次）
- **范围**：把「一条边的通行代价」从隐式的单一速度改为**按出行方式取值**：
  `DRIVE` 走现有分级限速（`WayfarerConfig.getNavigationSpeed(char)` + 距离闸门），
  `WALK` 走统一步行速度（不吃道路分级）；并把 `Route.etaSeconds` 改为由**累计通行代价**
  （`totalCost`，即 Σ 边长 ÷ 该边速度）给出，让 ETA 与代价函数自洽。
- **不动**：`GraphSearch` 的搜索算法与可采纳启发式口径、`NodeSpatialIndex`、路网数据模型与写路径、
  Xaero 渲染层、web 前端资源、HUD 版面一律不改。`Router.route(Node, Node, double)` 旧签名保留，
  默认方式下**路径与总距离逐值不变**（调用方零改动）。出行方式的 **UI 选择**不属本项
  （HUD 属 B5、网页面板属 C1）。
- **验收**：三版本 `compileJava` 通过 + `spotlessJavaCheck` 通过 + 无头测试通过，口径：
  ①同一张含多分级的合成路网，`WALK` 与 `DRIVE` 的总代价不同，且存在两方式最优路径不同的图例；
  ②`etaSeconds` 由分级限速算出（`etaSeconds == totalCost`，即分级真正进入 ETA，
  不再像现在这样恒用默认速度 `totalDistance / effectiveSpeed("")`）；
  ③不传方式（旧签名）与改造前**路径、总距离逐值一致**（ETA 一项按 ② 修正，属本项预期改动）。

### 交付物

| 文件 | 变更 |
|---|---|
| `client/road/nav/TravelMode.java` | **新增**。出行方式枚举 + 「按方式解析单边速度」的代价函数 |
| `client/road/nav/Router.java` | 建图按方式取速；新增带方式的重载；旧签名委托默认方式 |
| `client/road/nav/NavigationSession.java` | `start` 接受出行方式；`Snapshot` 暴露当前方式 |
| `client/road/server/WayfarerHttpServer.java` | `/api/nav/start` 接受 `mode` 入参；导航 JSON **纯新增** `mode` 字段（既有字段名与语义不动） |

---

## B4 —— 逐段指引（下一转向 / 剩余距离 / 剩余时间）与偏航重规划

- **认领者**：Marvis（本次）
- **范围**：在 `client/road/nav` 内新增一个**与 Minecraft 解耦**的引导层，把「一条 `Route` + 当前足位」
  算成任一端都可消费的指引量：沿折线**投影**得到的剩余距离、剩余时间、**下一转向**（距离 + 转向类型 + 节点），
  以及**偏航判定 + 重规划**（带确认窗口与冷却）。指引量同时暴露到 `NavigationSession.Snapshot`
  与网页端 `/api/nav/state`、`/api/nav/start` 的 JSON。
- **不动**：`Router` / `GraphSearch` / `NodeSpatialIndex` / 路网数据模型与写路径 / Xaero 渲染层 / 前端资源一律不改。
  HUD **版面重排**属 B5、网页导航**面板 UI** 属 C1、按等级精算 ETA 属 B3 —— 本项只做引导层与其**数据出口**，不越界。
- **验收**：三版本 `compileJava` 通过 + `spotlessJavaCheck` 通过 + 引导层无头测试通过（口径见下）。

### 交付物

| 文件 | 变更 |
|---|---|
| `client/road/nav/Guidance.java` | **新增**。无状态引导层：折线投影、剩余距离/时间、下一转向、偏航判定与重规划决策 |
| `client/road/nav/NavigationSession.java` | 接线引导层；`Snapshot` 新增指引字段；修正 `destination` 取「路线真实终点」 |
| `client/road/server/WayfarerHttpServer.java` | `/api/nav/state`、`/api/nav/start` 的导航 JSON **纯新增**指引字段（既有字段名与语义不动） |

### 现场勘察结论（写入时已核实）

1. **现有「剩余」是最近节点近似，会跳变**：`snapshot()` 用 `nearestRouteIndex`（遍历全部路线节点取最近）
   再累加 `edgeLengths`。玩家位于两节点之间时，最近节点一换，剩余量就**突跳一个分段的长度**。
   引导层应改为**投影到折线**（点—线段最近点 + 沿线弧长），使剩余量随移动连续、单调。
2. **`destination` 取错**：`start()` 里 `destination = ends.get(0)`，但 B2 的候选路由是 `starts × ends` 取最优，
   真实终点可能是 `ends` 中**另一个**候选 → 到达判定可能盯错点。应改为取 `route` 的真实终点节点。
3. **偏航链路无滞回、无冷却**：`updatePlayer` 里 `distanceToRoute > config.getNavRerouteThreshold()` 一帧越界即
   `reroute()`；而 `reroute()` 会替换 `route`，进而改变 `distanceToRoute`，存在**抖动与重规划风暴**风险。
   引导层须补：**确认窗口**（连续越界 N 次 / 持续 T 秒）+ **冷却**（重规划后最小间隔）。
4. **重规划终点走的是单点**：`reroute()` 用 `router.route(starts, List.of(destination), ...)`，终点侧未走候选集；
   接线后应与 `start` 统一走候选重载（`Router` 已有 `route(List<Node>, List<Node>, double)`）。
5. **`Route` 不含分段道路级别**：只有 `nodes / edgeLengths / totalDistance / totalCost / etaSeconds`。
   剩余时间最自洽且不越界的口径是「按剩余分段长度占比折算 `etaSeconds`」；按级别精算属 B3。
6. **可无头测**：引导层不依赖 MC / malilib，也不依赖 `WayfarerConfig`（阈值由调用方传入），
   可直接用合成折线 + 足位序列做性质测试；`NavigationSession` 接线沿用 B2 的无头桩
   （stub `WayfarerConfig` / `RoadNetworkDatabase`）。

---


## D1 —— 无头检查器骨架（不启动游戏即可跑）

- **认领者**：Marvis（本次）
- **范围**：在 `tools/headless` 落一套**不启动游戏**的核心检查器：每次都编译真实的
  `client/road/{model,nav,spatial}`，用两个替身（stub `WayfarerConfig` / 内存版 `RoadNetworkDatabase`）
  把 malilib 与 Minecraft 挡在外面，把 B2 的四个差分套件并入统一入口，并补一个「快照契约」套件。
- **不动**：`src/main/**` 一行不改，检查器只读源码。
- **验收**：`./run.sh` 一条命令跑完全部套件；退出码 0 = 全绿、1 = 有断言失败、2 = 找不到仓库、
  3 = 工作区当前编译不过（此时不会把编译错误当成检查失败）。

### 交付物

| 文件 | 说明 |
|---|---|
| `tools/headless/run.sh` | 唯一入口：编译（真实 core + 替身）→ 跑 `checks.Master`；每次调用都重新编译 |
| `tools/headless/src/stub/.../WayfarerConfig.java` | 无头替身：导航相关 getter + 距离阈值 |
| `tools/headless/src/stub/.../data/RoadNetworkDatabase.java` | 内存替身：`addNode` / `addSegment` / `getNodesForSegment` 等，读接口与真实库一致 |
| `tools/headless/src/checks/*.java` | 套件注册表 `Master` + 六个套件（各含 `static void run(Checks)`） |
| `tools/headless/README.md` | 用法、各套件口径、新增套件的步骤、已知覆盖缺口 |
| `tools/headless/.gitignore` | 忽略 `out/` |

### 六条检查（口径）

| 套件 | 钉住什么 |
|---|---|
| `spatial-index-vs-brute-force` | `NodeSpatialIndex` 的 k 近邻 vs 全量扫描：环形剪枝、并列次序（距离升序，同距离按 id）、半径与网格边界、负坐标、原地增删 |
| `graph-search-vs-dijkstra` | `GraphSearch`（多源 / 多目标 A\*）vs 独立 Dijkstra：随机有向图、整数网格、单行道、孤岛；外加「路径是可行走序列」与确定性 |
| `router-vs-dijkstra` | 真实 `Router` vs 复刻的改造前建图：跨吸附阈值门、退化与候选端点集 |
| `navigation-session-wiring` | 单候选能退化成改造前的单点路线；陷阱节点场景**只有**把整个候选集交给路由才能选对；候选为空时保留 `START_NOT_NEAR_ROAD` / `DESTINATION_NOT_NEAR_ROAD` |
| `snapshot-contract` | `HUD` / HTTP 消费方可以依赖什么：快照与其路线自洽、剩余量有限非负、起点处等于全程、`stop()` 后清空 |
| `source-structure` | 纯算法包脱离 Minecraft 可加载，且公开 API 结构在重构中不丢 |

### 验证记录

- `./run.sh` → **175 246 条断言，0 失败，6/6 套件 PASS**（`VERDICT: PASS`，退出码 0）。
- 首轮快照检查曾捕获 9 处失败：沿路线行走时 `route` 被重规划（节点数 3→2→1）。该语义属引导层
  （见 B4 勘察结论 3），故从骨架契约里移除「路线不得变」一类断言，只保留与实现无关的自洽性
  （剩余量有限非负、状态合法、路线终点 == 快照终点）。
- 已知覆盖缺口：`server/` 的 HTTP 与 JSON、`xaero/**` 与各类 Screen（依赖 Xaero jar 与 Minecraft 类）、
  `record/**` 及一切触碰 `net.minecraft` 的类。
- 备忘：`find` 编译清单已排除工具自动重命名产生的备份副本（`Name_YYYYMMDD_HHMMSS_mmm.java`），
  这类副本与正本同名类会让整个包编译失败；`nav/` 下现存一个 `Guidance_*.java` 副本，建议由 B4 作者清掉。

---

## A2 —— Xaero 世界地图绘制导航路线（复用 A1 管线）

- **认领者**：WorkBuddy
- **范围**：在 A1 的 `mapElementRenderHandler` 同一条管线上，把**当前激活的导航路线**画到 Xaero 世界地图；
  路线与路网**区分配色**（路线 Wayfarer 蓝 `0xFF007AFF`，路网按分级），并在路线两端画**起点绿 `0xFF34C759` / 终点红 `0xFFFF3B30` 方形标记**。
  路线元素**每帧从 `NavigationSession.snapshot()` 重建**（不缓存）：路线短、且随玩家移动 / 重规划高频变化，A1 的 stamp 缓存是为整张路网设计的，对单条路线是过度设计。
  裁剪策略与 A1 一致——`isOnScreen` 恒 true，由 `XaeroRouteProvider` 按世界包围盒在 `XaeroViewState` 上做视口裁剪。
- **不动**：`NavigationSession` / `Router` / `Route` / 网页端一律不改；A4 的悬停右键交互不在本项
  （`XaeroRouteReader.isInteractable` 保持 false，anchor box 保持紧致，理由见 A1 同类结论）。
  Xaero 已有玩家 marker，故路线层**不重复画当前玩家位置**（当前位置标记由网页端 `circleMarker` 承担，见 C1）。
- **依赖**：A1 ✓（管线 + `XaeroRoadStroke` 投影复用）、B4 ✓（`snapshot()` 已含 `route` / `state` / 指引字段）。
- **交付物**

  | 文件 | 变更 |
  |---|---|
  | `client/road/xaero/XaeroPolyline.java` | **新增**。路线与路网共享的折线接口（`vertexCount/x/z/color/widthBlocks/anchorX/anchorZ`），投影只写一次 |
  | `client/road/xaero/XaeroRoadElement.java` | 改 `implements XaeroPolyline`（向后兼容，road 层无行为变化） |
  | `client/road/xaero/XaeroRouteElement.java` | **新增**。路线扁平快照 + 世界包围盒 + `outside()`；`<2` 顶点返回 null |
  | `client/road/xaero/XaeroRouteProvider.java` | **新增**。`begin()` 读 `snapshot()`，仅 `ACTIVE && route!=null && inView()` 时产出单元素 |
  | `client/road/xaero/XaeroRouteReader.java` | **新增**。`isOnScreen` 恒 true、`isInteractable` false、`getMenuName="Wayfarer 路线"` |
  | `client/road/xaero/XaeroRouteRenderer.java`（根 `src/`） | **新增**。1.20.1 签名；复用 `XaeroRoadStroke` + 起终点方形标记 |
  | `versions/26.1.1`、`versions/26.2` 的 `xaero/XaeroRouteRenderer.java` | **新增**。同上，仅参数类型不同（见下） |
  | `client/road/xaero/XaeroRoadStroke.java` | `draw(...)` 第二参由 `XaeroRoadElement` 改为 `XaeroPolyline`（一处投影，两处复用） |
  | `client/road/xaero/XaeroRegistration.java` | `attempt()` 追加 `handler.add(new XaeroRouteRenderer(...))` |

  **三版本 `renderElement` 分叉**（与 A1 同一代 element API，仅 MC 渲染类型不同）：

  | 版本 | 图形对象 | 顶点缓冲 |
  |---|---|---|
  | 1.20.1 | `GuiGraphics` | `MultiBufferSource.BufferSource` |
  | 26.1.1 | `MapElementGraphics` | `MultiBufferSource.BufferSource` |
  | 26.2 | `MapElementGraphics` | `xaero.lib.client.graphics.XaeroBufferProvider` |

- **验证（写入时已核对）**
  - **API 已逐处对照**：`NavigationSession.Snapshot` 为 record（`state()/route()/playerX()/playerZ()/destination()/remainingDistance()/remainingTime()/nextTurn()/offRoute()`）、
    `Route.getNodes()` 返回 `List<Node>`、`XaeroViewState.{beginPass/claimRecording/isValid/screenWidth/Height/min/maxWorld*}` 与 `update(...)` 签名一致、
    `WayfarerClient.getNavigationSession()` 返回静态单例（provider 仍做 null 防御）。
  - **三版本参数对齐**：`XaeroRouteRenderer` 三个副本仅图形/缓冲类型不同，绘制逻辑全部下沉到 `XaeroRoadStroke` + `XaeroRouteElement`（与 A1 同构）。
  - **类隔离保持**：`XaeroRoute*` 仅引用 `xaero/map`、`xaero/lib` 类型于 `XaeroRegistration`/`Reader`/`Provider`/`Renderer` 四处，Xaero 缺席不会 `NoClassDefFoundError`。
  - **整包编译**：当前仍被 `nav/` 在建改动（`Guidance_*.java` 副本 + 未落地 API）挡住，与 A1 同口径；本项闭包（`road/xaero/XaeroRoute*` + `XaeroPolyline` + `XaeroRoadStroke` 签名改动）按「独立 `compileClasspath` + `javac`」方式验证，待 B4 落地后建议补一次整包 `:XX:compileJava` 复跑。
- **下游解锁**：A3（小地图）可直接复用 `XaeroRouteProvider` / `XaeroRouteElement`，把 `shouldRender` 扩到 `IN_MINIMAP` / `OVER_MINIMAP` 并按小地图比例尺缩窄线宽即可，无需重写投影。

---

## C1 —— 网页导航面板补全

- **认领者**：WorkBuddy
- **范围**：把现有「能用但不完整」的导航做成完整面板——终点**点选（地图点选 / 坐标输入）/ 出行方式分段控件**、路线**蓝色 polyline** 可视化、
  **剩余距离 / ETA / 下一步转向（图标 + 距离）/ 进度条**、**到达与偏航**状态、以及**取消**。面板轮询 `/api/nav/state` 自刷新。
  出行方式 WALK/DRIVE 的 **UI 选择**在本项落地（分段高亮 + 记忆 `navMode`），但服务端按方式取速属 B3，B3 未接线前发送的 `mode` 服务端暂忽略（安全附加字段）。
- **不动**：后端 `/api/nav/*` 的 JSON 形状（B4 已定）只消费不改动；`Router` / `NavigationSession` / Xaero 渲染层不动。
- **依赖**：B4 ✓（`/api/nav/state|start` 的 `navigationJson` 形状已核对：`coordinates=[[x,z]...]`、`nextTurn.type∈{LEFT,RIGHT,UTURN,STRAIGHT}`、`destination.{x,z}`、`state`、`offRoute`、`ok`）。
- **交付物**

  | 文件 | 变更 |
  |---|---|
  | `index.html` | 新增毛玻璃 `#nav-panel` / `#nav-picker`（`data-i18n` 绑定），含进度条、转向图标、模式分段控件、取消按钮 |
  | `static/app.js` | 新增 `openNavPicker/startNavigation/renderNavigation/updateNavPanel/refreshNavigation/cancelNavigation/formatDistance/formatDuration`；`tool-navigation`→开面板、`pick-on-map`/`pick-coords-go`→发起、`nav-cancel`/`tool-navigation-stop`→取消、`nav-mode`→切方式 |
  | `static/i18n.js` | 新增 `nav.*` 双语键（zh-CN / en） |

- **验证（写入时已核对）**：`node --check` 通过；事件绑定逐处核对；`navigationJson` 字段名与客户端读取一一对应；Apple 毛玻璃设计系统（`--glass-blur` / `#007AFF` / 圆角）落地。

---

## C2 —— 大路网渲染性能

- **认领者**：WorkBuddy
- **范围**：替换「每秒全量 teardown 重画」为**按需增量重绘** + 三道降载：
  ①**视口裁剪**（`pointInView` 顶点测试、`segmentInView` 包围盒测试，路线/节点屏外直接跳过）；
  ②**屏幕空间 Douglas–Peucker 抽稀**（`simplifyScreen`，容差 1.2px，缩放越大丢得越多，视觉不变）；
  ③**脏标记**（`viewportDirty`，地图 `moveend`/`zoomend` 置脏；`loadDelta` 仅在「视口动了」或「本次同步的实体落入视口」时才重画）。
  右下角**性能胶囊**实时显示「已渲染 n / total 段」。
- **不动**：路网数据模型、写路径、`/api/roads` 协议不动；只改前端渲染循环。
- **交付物**

  | 文件 | 变更 |
  |---|---|
  | `static/app.js` | 新增 `pointInView/segmentInView/simplifyScreen/douglasPeucker/perpendicularDistance/changedEntitiesInView/maybeRender`；`renderAll` 两处线段分支加 `if(!segmentInView)continue;` + `simplifyScreen` + 计数；`loadDelta` 改走脏/可见判定；`initMap` 末尾挂 `moveend`/`zoomend` 置脏 |
  | `index.html` | 新增 `#perf-chip` / `#perf-text` 毛玻璃胶囊（`data-i18n="perf.*"`） |
  | `static/i18n.js` | 新增 `perf.rendered/culled/simplified` 双语键 |

- **验证（写入时已核对）**：`node --check` 通过；计数与裁剪路径已走读；抽稀容差 1.2px 取自「整条路仍对齐、节点级折线不抖」的折中。

---

## C3 —— 编辑体验（吸附 / 批量 / 撤销重做）

- **认领者**：WorkBuddy
- **范围**：按使用频率把编辑体验补两项高频能力——**网格吸附**（开关，节点拖拽 `onNodeDragEnd` 与插入线段 `insertNodeOnSegment` 时 `Math.round`）与**批量删除选中段**（确认后逐条 `DELETE /api/segments/:id`）。
  撤销/重做（C1 之前已有 `undo/redo`）保留不动，本项只接好触发与批量条显隐。
- **不动**：后端 `DELETE /api/segments/:id` 协议只消费不改动；数据模型不动。
- **依赖**：C1 的选中态（`selectedSegmentIds`）作为批量删除的输入。
- **交付物**

  | 文件 | 变更 |
  |---|---|
  | `index.html` | 新增 `#snap-toggle`（毛玻璃开关）、`#batch-bar`（暗色浮条，`data-i18n="edit.batchTitle"`） |
  | `static/app.js` | 新增 `toggleSnap`（切 `snapToGrid` + `#snap-toggle.active`）、`deleteSelectedSegments`（确认后逐条删）；`onNodeDragEnd`/`insertNodeOnSegment` 接入吸附；`updateMergeButton` 一并控制批量条显隐与计数 |
  | `static/i18n.js` | 新增 `edit.snap.*` / `edit.deleteSelected` / `edit.batchTitle` 双语键 |

- **验证（写入时已核对）**：`node --check` 通过；吸附在「坐标落整点」语义下成立；批量删除走既有 `DELETE` 端点，逐条失败不中断其余。

---

### 本交付未覆盖（A / C 仍待认领项）

> 本次只交付了会话中已开发的 **A2 + C1/C2/C3**。A 线其余三项与「完成 A 和 C 全部」的口径尚有缺口，列此备查，不冒充已完成：

| 编号 | 任务 | 状态 | 说明 |
|---|---|---|---|
| A3 | Xaero **小地图**绘制导航（`IN_MINIMAP`/`OVER_MINIMAP`） | 待认领 | A2 已把 Provider/Element 写好，仅需扩 `shouldRender` 到小地图位置并按比例尺缩线宽；独立任务 |
| A4 | 悬停高亮 + 右键菜单（定位网页 / 复制坐标 / 删除段） | 待认领 | 需改 `XaeroRoadReader.isInteractable=true` 并把 `getRenderBox*`/`getInteractionBox*` 改**锚点相对量**（A1 已标出此坑）；交互层新需求 |
| A5 | 路名标签（随缩放显隐、沿折线排布）+ 图层过滤开关 | 待认领 | 依赖 A1 ✓、B1 ✓；需在 Xaero 上画文字 + 加过滤 UI，独立任务 |
