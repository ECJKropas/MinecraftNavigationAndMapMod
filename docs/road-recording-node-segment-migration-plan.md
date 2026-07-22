# 道路录制→储存全流程迁移计划（RoadPath → Node/Segment/Road）

## 概述

将录制结束页面、道路列表页、道路详情页的 UI 与存储逻辑从旧的 `RoadPath`/`RoadPoint`/`RoadDataStore` 模型迁移到新的 `Node`/`Segment`/`Road`/`RoadNetworkDatabase` 模型。

## 影响范围

| 文件 | base | 26.2 overlay | 改动类型 |
|------|------|-------------|----------|
| `RoadMetadataScreen.java` | 重写 | 重写 | 完全重写 |
| `RoadRecordingManager.java` | 重写方法 | 无 overlay | 重写 saveRecording/finishAppend |
| `RoadListScreen.java` | 重写 | 重写 | 完全重写 |
| `WayfarerClient.java` | 修改 | 修改 | 调整录制结束流程 |
| `RoadNetworkDatabase.java` (data/) | 新增方法 | 无 overlay | 新增便捷查询方法 |
| `en_us.yml` / `zh_cn.yml` | 新增 key | 无 overlay | i18n 补充 |

## ⚠️ 待确认问题

1. **枚举值不一致**：任务描述中使用 `Source.RECORDED` 和 `Status.ACTIVE`，但模型中 `Source` 只有 `AUTO, USER`，`Status` 只有 `DRAFT, CONFIRMED`。计划中暂时使用 `Source.USER` + `Status.CONFIRMED`，如需新增枚举值请告知。

2. **Road 模型的 color 字段**：现有 `Road(String name, String color, List<UUID> segmentIds)`，任务中的"创建新道路"表单不包含颜色选择。计划中默认使用 `"#FFFFFF"`，后续可扩展颜色选择器。

3. **RoadListScreen 的"继续录制"按钮**：任务要求去掉（旧 RoadPath 概念已不存在）。但 `MainMenuScreen` 中的 `openRoadList` → `startAppendRecording` 回调当前传递 `Consumer<RoadPath>`。迁移后 `finishAppend` 的语义变了（追加 Segment 到已有 Road），新 RoadListScreen 的构造函数签名需调整。

4. **RoadRecordingManager 构造函数**：当前接收 `RoadDataStore`。迁移后需要改为接收 `RoadNetworkDatabase`（或者不接收参数直接用 `RoadNetworkDatabase.getInstance()`）。

---

## 任务 1: RoadMetadataScreen 完全重写

### 文件
- base: `src/main/java/com/ecjkim/wayfarer/client/road/RoadMetadataScreen.java`
- 26.2: `versions/26.2/src/main/java/com/ecjkim/wayfarer/client/road/RoadMetadataScreen.java`

### 新构造函数签名
```java
public RoadMetadataScreen(
    Segment segment,                    // 录制完成的 Segment
    Consumer<Road> onSaveCallback,      // 保存完成回调 (传入创建的/选中的 Road)
    Runnable onCancel                   // 取消回调
)
```

### 新 UI 布局

**上半部：已有道路选择区**
- 大按钮 `[选择已有道路]`，浅蓝色高亮背景 0xFF3366AA，边框 0xFF4E5768，宽占面板 80%，居中
- 点击 → 打开 `RoadListScreen`（SELECT 模式），选中 Road 后回到本页面
- 选中 Road 后按钮旁边显示 Road 名称（如 `已选择: G318国道`）

**下半部：创建新道路表单**
- 道路名输入框（保留现有）
- 分类/编号选择器（保留现有）
- 去掉宽度输入框（Road 模型无 width）
- 保存按钮 + 取消按钮

### 保存逻辑
- 若用户选择了已有道路：将当前 Segment 关联到已有 Road
  - `segment.setRoadId(selectedRoad.getId())`
  - `road.getSegmentIds().add(segment.getId())`
  - `RoadNetworkDatabase.updateSegment()` + `updateRoad()`
- 若用户未选择已有道路（走"创建新道路"表单）：
  - `new Road(UUID, name, "#FFFFFF", List.of(segment.getId()), 1)`
  - `segment.setRoadId(road.getId())`
  - 存入 RoadNetworkDatabase
- 保存完成后 `RoadNetworkDatabase.saveToDisk()`

### 26.2 overlay 适配要点
- `GuiGraphics` → `GuiGraphicsExtractor`
- `render()` → `extractRenderState()`
- `drawString()` → `text()` / `centeredText()`
- `setScreen(null)` → `setScreenAndShow(null)`
- `keyPressed(int, int, int)` → `keyPressed(KeyEvent)`

### 新增 i18n key

| key | en_us | zh_cn |
|-----|-------|-------|
| `wayfarer.road.gui.metadata.select_road` | Select Existing Road | 选择已有道路 |
| `wayfarer.road.gui.metadata.selected_road` | Selected: %s | 已选择: %s |
| `wayfarer.road.gui.metadata.create_new` | Create New Road | 创建新的道路 |
| `wayfarer.road.gui.metadata.segment_nodes` | Segment nodes: %d | 线段节点数: %d |
| `wayfarer.road.gui.metadata.title_record` | Save Recorded Road | 保存录制的道路 |
| `wayfarer.road.gui.metadata.subtitle_new` | Select an existing road or create a new one | 选择已有道路或创建新道路 |

---

## 任务 2: RoadRecordingManager 重写 saveRecording 和 finishAppend

### 文件
- 仅 base: `src/main/java/com/ecjkim/wayfarer/client/road/RoadRecordingManager.java`
- 26.2 无 overlay

### saveRecording 新流程

```java
public Segment saveRecording() {
    // 1. 对 sessionPoints 执行简化（保持现有 RoadSimplifier 流程）
    List<RoadPoint> simplified = RoadSimplifier.simplify(
        sessionPoints, BACKTRACK_THRESHOLD, epsilonFormula, width, dw);

    // 2. 从简化后的点创建 Node 列表
    long now = System.currentTimeMillis();
    List<UUID> nodeIds = new ArrayList<>();
    RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
    for (RoadPoint p : simplified) {
        Node node = new Node(UUID.randomUUID(), p.x, p.y, p.z,
            CornerType.STRAIGHT, Source.USER, 1, now);  // Source.USER 替代 RECORDED
        db.addNode(node);
        nodeIds.add(node.getId());
    }

    // 3. 创建 Segment
    Segment segment = new Segment(UUID.randomUUID(), nodeIds, null,
        Source.USER, Status.CONFIRMED, 1);  // Status.CONFIRMED 替代 ACTIVE
    db.addSegment(segment);

    // 4. 不再调用 RoadDataStore.addRoad() — 由 RoadMetadataScreen 处理 Road 关联

    sessionPoints.clear();
    return segment;
}
```

**改动要点**：
- 方法签名从 `void saveRecording(String name, double width, String classification, String number)` → `Segment saveRecording()`
- 不再接收元数据参数（名称/宽度/分类等），这些在 RoadMetadataScreen 中处理
- 保留简化逻辑不变，仅将简化后的 `RoadPoint` 转为 `Node`
- 去掉 `snapEndpoints` / `detectIntersections`（先 skip）
- 去掉 `roadDataStore.addRoad()` / `snapRoadsToRoad()` / `refreshRoadIntersections()`

### finishAppend 新流程

```java
public Segment finishAppend() {
    // 与 saveRecording 基本一致:
    // 1. 简化 sessionPoints
    // 2. 创建 Node → Segment
    // 3. 但不立即关联 Road（RoadMetadataScreen 处理）
    // 4. 清理 appendMode 状态

    RoadNetworkDatabase db = RoadNetworkDatabase.getInstance();
    // 删除旧 road 的 segment 引用...
    // (从 appendRoad 对应的 RoadNetworkDatabase.roads 中移除旧 segmentIds)

    return segment;
}
```

**改动要点**：
- 方法签名从 `void finishAppend(String name, double width, String classification, String number)` → `Segment finishAppend()`
- 旧 `appendRoad` (RoadPath) 的删除逻辑改为操作 `RoadNetworkDatabase`
- 后续 Road 关联留给 RoadMetadataScreen（append 模式下传入已有 Road）

### 其他改动
- `RoadRecordingManager` 构造函数移除 `RoadDataStore` 依赖，改为内部直接用 `RoadNetworkDatabase.getInstance()`
- 保留 `startRecording` / `stopRecording` / `discardRecording` / `tick` / `isRecording` 不变
- `startAppend` 方法签名改为接收 `Road`（而非 `RoadPath`），参数 `double playerX, playerY, playerZ` 保留

---

## 任务 3: WayfarerClient 调整录制结束流程

### 文件
- base: `src/main/java/com/ecjkim/wayfarer/client/WayfarerClient.java`
- 26.2: `versions/26.2/src/main/java/com/ecjkim/wayfarer/client/WayfarerClient.java`

### handleToggleRecording 录制结束分支改动

```java
// 新录制结束
ROAD_MANAGER.stopRecording();
if (ROAD_MANAGER.getRecordedPointCount() < 2) {
    ROAD_MANAGER.discardRecording();
    player.displayClientMessage(...);
} else {
    Segment segment = ROAD_MANAGER.saveRecording();  // 返回 Segment
    // 打开新的 RoadMetadataScreen，传入 segment
    client.setScreen(new RoadMetadataScreen(segment, savedRoad -> {
        // 保存完成回调
        player.displayClientMessage(Component.literal("道路已保存: " + savedRoad.getName()));
    }, ROAD_MANAGER::discardRecording));
}
```

**改动要点**：
- `saveRecording()` 不再需要元数据参数，改为无参返回 Segment
- `RoadMetadataScreen` 构造函数改为接收 Segment
- `finishAppend` 路径也做类似改动（无参返回 Segment，传入已有 Road 到 RoadMetadataScreen）
- `startAppendRecording` 方法签名从 `(RoadPath)` → `(Road)`

### 26.2 overlay 适配要点
- `client.screen` → `client.gui.screen()`
- `getWindow().getWindow()` → `getWindow().handle()`
- `setScreen()` → `setScreenAndShow()`
- `displayClientMessage()` → `sendSystemMessage()`
- `ClickEvent.Action.SUGGEST_COMMAND` → `ClickEvent.SuggestCommand` (已在 RoadListScreen overlay 中体现)

---

## 任务 4: RoadListScreen 重写为 Roads→Segments 层级视图

### 文件
- base: `src/main/java/com/ecjkim/wayfarer/client/road/RoadListScreen.java`
- 26.2: `versions/26.2/src/main/java/com/ecjkim/wayfarer/client/road/RoadListScreen.java`

### 新增 Mode 枚举
```java
public enum Mode {
    LIST,   // 正常浏览模式：查看详情、编辑、删除
    SELECT  // 选择模式：点击 Road 条目 → 回调返回 Road，关闭页面
}
```

### 新构造函数
```java
// LIST 模式（MainMenuScreen 打开）
public RoadListScreen(Mode mode, Consumer<Road> onRoadSelected)

// 不再依赖 RoadDataStore 和 RoadPreviewServer（数据来源改为 RoadNetworkDatabase）
```

### 数据来源
- 从 `RoadNetworkDatabase.getInstance().roads` 读取 Road 列表
- 从 `RoadNetworkDatabase.getInstance().segments` 读取 Segment
- 不再从 `RoadDataStore` 读取

### 左侧面板
- Road 列表，每个条目显示：Road 名称 + segment 数量括号
- 点击 Road 展开/选中
  - LIST 模式：展开看到该 Road 下的 Segments 子列表
  - SELECT 模式：点击即选中回调（非展开）
- 搜索框保留，按 Road 名称过滤

### 右侧详情面板
- 选中 Road 时：名称、颜色、segment 数量、每个 segment 的节点数
- 选中 Segment 时：起点终点坐标、节点数
- 去掉端点方向链接（旧基于 RoadPath.points）

### 去掉的功能
- "继续录制"按钮（旧 RoadPath 概念）
- RoadPath 相关的端点坐标链接
- RoadDataStore / RoadPreviewServer 依赖

### 编辑/删除
- 编辑 Road 名称：直接操作 `RoadNetworkDatabase.updateRoad()`
- 删除 Road：调用 `RoadNetworkDatabase.removeRoad()` + 清理关联 Segments
- （暂无 Segments 层面的删除/编辑）

### 新增 i18n key

| key | en_us | zh_cn |
|-----|-------|-------|
| `wayfarer.road.gui.segments` | Segments: %d | 线段数: %d |
| `wayfarer.road.gui.segment_nodes_count` | Nodes: %d | 节点数: %d |
| `wayfarer.road.gui.segment_start` | Start: (%.1f, %.1f, %.1f) | 起点: (%.1f, %.1f, %.1f) |
| `wayfarer.road.gui.segment_end` | End: (%.1f, %.1f, %.1f) | 终点: (%.1f, %.1f, %.1f) |
| `wayfarer.road.gui.select_prompt` | Select a road to associate | 选择一条道路关联 |
| `wayfarer.road.gui.color` | Color: %s | 颜色: %s |

### 26.2 overlay 适配要点（同 RoadMetadataScreen）
- `GuiGraphicsExtractor` / `extractRenderState` / `text()` / `centeredText()`
- `KeyEvent` / `MouseButtonEvent` / `CharacterEvent`
- `setScreenAndShow()` / `sendSystemMessage()`
- `ObjectSelectionList` API 变化：`setX()`/`getX()`、`scrollBarX()`、`extractListBackground()`/`extractListSeparators()`、`addEntry(entry, height)`
- `getY()` / `getContentX()` / `getContentWidth()` / `getContentHeight()` 替代旧的计算方式

---

## 任务 5: RoadNetworkDatabase 辅助方法

### 文件
- `src/main/java/com/ecjkim/wayfarer/client/road/data/RoadNetworkDatabase.java`

### 新增方法

```java
/**
 * 获取指定 Segment 的所有 Node 列表（按 nodeIds 顺序）。
 */
public List<Node> getNodesForSegment(UUID segmentId) {
    Segment segment = segments.get(segmentId);
    if (segment == null || segment.getNodeIds() == null) {
        return Collections.emptyList();
    }
    List<Node> result = new ArrayList<>();
    for (UUID nodeId : segment.getNodeIds()) {
        Node node = nodes.get(nodeId);
        if (node != null) {
            result.add(node);
        }
    }
    return result;
}

/**
 * 获取指定 Road 的所有 Segment 列表。
 */
public List<Segment> getSegmentsForRoad(UUID roadId) {
    Road road = roads.get(roadId);
    if (road == null || road.getSegmentIds() == null) {
        return Collections.emptyList();
    }
    List<Segment> result = new ArrayList<>();
    for (UUID segId : road.getSegmentIds()) {
        Segment seg = segments.get(segId);
        if (seg != null) {
            result.add(seg);
        }
    }
    return result;
}
```

---

## 执行顺序

1. **RoadNetworkDatabase** — 新增辅助方法（任务 5，影响面小）
2. **RoadRecordingManager** — 重写 saveRecording/finishAppend（任务 2）
3. **RoadMetadataScreen** — 完全重写 base + 26.2（任务 1）
4. **RoadListScreen** — 重写 base + 26.2（任务 4）
5. **WayfarerClient** — 调整录制结束流程 base + 26.2（任务 3）
6. **i18n** — 同步新增 key 到 en_us.yml 和 zh_cn.yml

每完成一个文件，用 `grep` 验证无中文硬编码残留，编译检查通过后再进行下一个。

---

## 不变的部分
- `RoadPath.java` / `RoadPoint.java` / `RoadBook.java` / `RoadDataStore.java` / `RoadIntersection.java` / `RoadSegment.java` / `RoadStyle.java` — 不动
- `RoadSimplifier.java` — 不动（仍使用 RoadPoint 做简化，结果转为 Node）
- `RoadPreviewServer.java` — 不动
- `Geometry.java` — 不动
- `XaeroMapOverlay.java` — 不动
- `MainMenuScreen.java` — 可能需微调构造函数调用（参数类型变化），但只改调用点不改完整逻辑
- `WayfarerConfig.java` / `WayfarerInitHandler.java` — 不动
