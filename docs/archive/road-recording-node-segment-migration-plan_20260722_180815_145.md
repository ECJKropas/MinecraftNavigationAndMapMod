# 道路录制→储存全流程迁移计划 v2（含旧模型删除）

## 概述

将录制结束页面、道路列表页、道路详情页的 UI 与存储逻辑从旧的 `RoadPath`/`RoadPoint`/`RoadDataStore` 模型迁移到新的 `Node`/`Segment`/`Road`/`RoadNetworkDatabase` 模型。append recording 整体删除，旧存储系统（9 个文件）整体移除。

## 影响范围

| 文件 | base | 26.2 overlay | 改动类型 |
|------|------|-------------|----------|
| `model/Road.java` | 修改 | 无 | 新增 classification/number/width 字段 |
| `data/RoadNetworkDatabase.java` | 修改 | 无 | 新增 3 个辅助方法 |
| `RoadRecordingManager.java` | 重写 | 无 | 删除 append/snap/detect，新增 saveRecording() |
| `RoadMetadataScreen.java` | 重写 | 重写 | 完全重写 |
| `RoadListScreen.java` | 重写 | 重写 | 完全重写 |
| `WayfarerClient.java` | 重写 | 重写 | 调整结束流程，删 append |
| `MainMenuScreen.java` | 重写 | 重写 | 删 RoadDataStore/RoadPreviewServer/onContinueRecording |
| `XaeroMapOverlay.java` | 重写 | 重写 | 适配 RoadNetworkDatabase |
| `RoadSimplifier.java` | 重写 | 无 | 内部用 double[] 替代 RoadPoint |
| `RoadPreviewServer.java` | 改 stub | 无 | 改为空实现 |
| `en_us.yml` / `zh_cn.yml` | 新增 key | 无 | 补充 i18n |

## 删除文件（9 个）
- `model/RoadPath.java` — 旧模型，已由 Road + Segment 替代
- `model/RoadPoint.java` — 旧坐标类，已由 Node 替代；RoadSimplifier 内部改用 double[]
- `model/RoadBook.java` — 旧数据集合，已由 RoadNetworkDatabase 替代
- `model/RoadSegment.java` — 旧路段模型，已由 Segment 替代
- `model/RoadStyle.java` — 旧渲染样式，Road 已有 color 字段
- `model/RoadIntersection.java` — 旧交叉点模型（先删，后续基于 Node 重做）
- `RoadDataStore.java` — 旧存储层，已由 RoadNetworkDatabase 替代
- `RoadStorageContext.java` — 旧上下文层
- `Geometry.java` — 旧几何工具函数，在简化和重叠渲染中不再需要

---

## 执行阶段

### Phase 0: 前置修正

#### 0a. Road.java 新增字段
添加 `String classification`、`String number`、`double width` 字段，getter/setter，全参构造函数。
Gson 兼容：字段不存在时默认 classification=""、number=""、width=7.0。

#### 0b. RoadNetworkDatabase 新增辅助方法
```java
public List<Node> getNodesForSegment(UUID segmentId)
public List<Segment> getSegmentsForRoad(UUID roadId)
public void addSegmentToRoad(UUID roadId, UUID segmentId)
```

---

### Phase 1: RoadRecordingManager 重构

**saveRecording 新签名**: `public Segment saveRecording()`

流程：
1. 简化 sessionPoints（RoadSimplifier，内部改用 double[] 代替 RoadPoint）
2. 简化后点 → Node(UUID, x, y, z, CornerType.STRAIGHT, Source.USER, 1, now)
3. 创建 Segment(UUID, nodeIds, null, Source.USER, Status.CONFIRMED, 1)
4. 所有 Node → addNode(), Segment → addSegment()
5. 清空 sessionPoints，返回 Segment

**删除内容**：
- finishAppend() 及所有 append 方法
- appendMode / appendRoad / appendEndpoint / appendWaitingForAngle 字段
- getAppendRoadName() / getAppendRoadWidth() 等 getter
- startAppend()
- snapEndpoints / snapEndpoint / snapEndpointsToRoad / snapEndpointToRoad / detectIntersections
- 构造函数中 RoadDataStore 依赖 → RoadNetworkDatabase.getInstance()
- RoadPath / RoadDataStore / Geometry / RoadIntersection import
- 所有 appendMode 分支

**保留内容**：
- tick() 方法（采样逻辑保留，去掉 appendMode 分支）
- startRecording / stopRecording / discardRecording / isRecording / getRecordedPointCount

**新增 import**：
- Node / Segment / Road / CornerType / Source / Status
- RoadNetworkDatabase

---

### Phase 2: RoadMetadataScreen 重写 (base + 26.2)

**构造函数**：
```java
public RoadMetadataScreen(Segment savedSegment, Consumer<Road> onSave, Runnable onCancel)
```

**UI**：
- 上半部：浅蓝按钮 `[选择已有道路]` (0xFF3366AA 背景, 0xFF4E5768 边框, 80% 宽居中)
  - 点击 → RoadListScreen(SELECT)，选中 Road 后回到本页面
  - 选中后按钮下方显示所选道路名称和编号
- 下半部：`创建新的道路` 表单
  - 名称 EditBox
  - 分类 cycleButton + 编号 numberBox
  - 删除宽度输入框（Road 有 width 字段但不在此输入，用默认值 7.0）
  - 保存 + 取消按钮

**保存逻辑**：
- 若选择了已有道路 → segment.roadId = road.id; road.segmentIds.add(segment.id); addSegmentToRoad()
- 若创建新道路 → new Road(UUID, name, "#FFFFFF", List.of(segmentId), 1); 设置 classification/number/width=7.0; addRoad()
- 最终 saveToDisk()

**26.2 overlay**: GuiGraphicsExtractor / extractRenderState / keyPressed(KeyEvent) / setScreenAndShow

**新增 i18n key**:
- select_existing_road / 选择已有道路
- create_new_road / 创建新的道路
- selected_road / 已选道路: %s
- title_record / 保存录制的道路

---

### Phase 3: RoadListScreen 重写 (base + 26.2)

**Mode 枚举**: `LIST`, `SELECT`

**构造函数**:
```java
// LIST 模式（从 MainMenuScreen 打开）
public RoadListScreen()
// SELECT 模式（选择道路，供 RoadMetadataScreen 回调）
public RoadListScreen(Consumer<Road> onRoadSelected, Runnable onCancel)
```

**数据源**: `RoadNetworkDatabase.getInstance()`

**左侧 Roads→Segments 层级列表**：
- 顶层 Road 条目：名称 + segment 数量 badge
- 展开 → Segment 子条目（缩进）
- SELECT 模式：点击 Road 触发 onRoadSelected 并关闭
- 搜索框保留，按 Road 名称过滤

**右侧详情面板**：
- 选中 Road：名称/编号/分类/宽度/颜色/Segment 数量
- 选中 Segment：起点/终点坐标/节点数

**删除**：
- RoadDataStore / RoadPreviewServer 依赖
- "继续录制"按钮 (▶) 及相关回调
- RoadPath/RoadPoint 相关
- 旧端点方向链接
- 旧 RoadEntry/RoadEntryList 组件

**编辑**：Road 重命名、属性编辑 → RoadNetworkDatabase.updateRoad()

**26.2 overlay**：同上 API 适配 + ObjectSelectionList API 差异

---

### Phase 4: WayfarerClient 调整 (base + 26.2)

**handleToggleRecording**:
```java
ROAD_MANAGER.stopRecording();
if (points < 2) { discard + message; return; }
Segment seg = ROAD_MANAGER.saveRecording();
client.setScreen(new RoadMetadataScreen(seg, road -> {
    player.sendSystemMessage(Component.literal("已保存: " + road.getName()));
}, ROAD_MANAGER::discardRecording));
```

**删除**：isAppending() 分支、startAppendRecording()、RoadDataStore 静态字段、RoadPreviewServer 静态字段

**构造函数改为用 RoadNetworkDatabase 替代 RoadDataStore**

**26.2 overlay**: 同步改动

---

### Phase 5: MainMenuScreen 调整 (base + 26.2)

- 删除 `RoadDataStore roadDataStore`、`RoadPreviewServer previewServer`、`Consumer<RoadPath> onContinueRecording` 字段
- 删除构造函数中对应参数
- `new RoadListScreen` 调用改为无参（LIST 模式）
- 按钮从 `"道路管理"` 保持，但回调简化

---

### Phase 6: XaeroMapOverlay 重写 (base + 26.2)

数据源从 `RoadDataStore` → `RoadNetworkDatabase`：
- 从 `RoadNetworkDatabase.roads` 遍历 Road
- 每个 Road 的 Segments → 解析 Node 坐标渲染多段线
- 颜色从 `classificationColor()` 保留（从 Road.classification 读取）
- 线宽从 classification 推导

---

### Phase 7: RoadSimplifier 内部重构

将 `RoadPoint` 依赖替换为 `double[]`：
- `simplify()` 签名改为 `public static List<double[]> simplify(List<double[]> points, ...)`
- 内部所有 RoadPoint.x/y/z → points[i][0/1/2]
- RoadPoint.tick 不再需要（simplification 不需要 tick 信息）

---

### Phase 8: RoadPreviewServer stub

改为空实现（保留类存在，start/stop 空方法），供其他未迁移模块引用。

---

### Phase 9: 删除旧文件 + 修改引用方

验证所有引用已修改后，删除 9 个旧文件。

---

### Phase 10: i18n 更新

新增 key 到 en_us.yml 和 zh_cn.yml。

---

## 工具链：三版本构建验证

每次完成一个文件改动后执行：
```bash
cd /Users/cjkim/Documents/MinecraftNavigationAndMapMod
./gradlew build 2>&1 | tail -30
```

---

## 枚举使用规范
- Source: `USER`（录制产生）
- Status: `CONFIRMED`（录制产生）
- CornerType: `STRAIGHT`（默认直线采样点）
