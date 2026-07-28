# Wayfarer（越陌度阡）项目分析报告

> 生成日期：2026-07-28 | 代码版本：0.3.3

---

## 1. 项目概述

**Wayfarer** 是一个 Minecraft Fabric 客户端模组，中文名「越陌度阡」，旨在帮助玩家在游戏中记录、管理和浏览道路轨迹。玩家可以按热键开始/停止录制行走路径，模组自动将轨迹简化并保存为结构化的道路网络数据，提供游戏内编辑器、Xaero 世界地图叠加层渲染、以及本地 Web 编辑器三种交互方式。

| 属性 | 值 |
|---|---|
| 项目类型 | Minecraft Mod（客户端） |
| 模组框架 | **Fabric** + Fabric Loom + preprocess 多版本分发 |
| Mod ID | `wayfarer` |
| 包名 | `com.ecjkim.wayfarer` |
| 版本 | 0.3.3 |
| 许可协议 | GPL-3.0 |
| 支持的 MC 版本 | 1.20.1, 26.1.1, 26.2 |
| 核心依赖 | Fabric Loader, Fabric API, malilib |
| 构建工具 | Gradle 8.x + Kotlin DSL |
| Java 兼容 | 17+（随 MC 版本变化：1.20.1→17, 26.x→21/25） |

---

## 2. 目录结构

```
MinecraftNavigationAndMapMod/
├── build.gradle.kts              # 根构建脚本（preprocess 版本链定义）
├── common.gradle.kts             # 子项目通用构建脚本（Fabric Loom 配置）
├── settings.gradle.kts           # 动态 include 多版本子项目
├── settings.json                 # 版本列表 ["1.20.1","26.1.1","26.2"]
├── gradle.properties             # 全局属性（modId, modName, modVersion）
├── gradle/
│   └── libs.versions.toml        # 版本目录（Fabric Loom, Loader, 插件版本）
├── docs/                         # 文档
├── src/
│   └── main/
│       ├── java/com/ecjkim/wayfarer/client/
│       │   ├── WayfarerClient.java            # 模组入口（ClientModInitializer）
│       │   ├── WayfarerConfig.java            # 配置读取器（单例）
│       │   ├── WayfarerInitHandler.java       # malilib 初始化处理器
│       │   ├── Reference.java                 # 常量定义
│       │   ├── MainMenuScreen.java            # 主菜单界面
│       │   ├── config/
│       │   │   ├── WayfarerConfigs.java       # 配置项定义（malilib IConfigBase）
│       │   │   └── WayfarerHotkeys.java       # 热键定义
│       │   ├── gui/
│       │   │   └── WayfarerConfigScreen.java  # malilib 配置界面
│       │   └── road/
│       │       ├── RoadListScreen.java        # 三栏道路编辑器（752行）
│       │       ├── RoadMetadataScreen.java    # 录制完成后保存界面
│       │       ├── RoadRecordingManager.java  # 录制管理器
│       │       ├── RoadSimplifier.java        # 轨迹简化算法
│       │       ├── XaeroMapOverlay.java       # Xaero世界地图叠加渲染
│       │       ├── data/
│       │       │   └── RoadNetworkDatabase.java  # 内存数据库（1355行）
│       │       ├── layer/
│       │       │   ├── MapLayer.java          # 图层接口
│       │       │   └── LayerManager.java      # 图层注册中心
│       │       ├── model/
│       │       │   ├── Node.java              # 节点模型
│       │       │   ├── Segment.java           # 路段模型
│       │       │   ├── Road.java              # 道路模型
│       │       │   ├── CornerType.java        # 节点拐角类型枚举
│       │       │   ├── Source.java            # 来源枚举
│       │       │   └── Status.java            # 状态枚举
│       │       └── server/
│       │           └── WayfarerHttpServer.java  # HTTP API服务器（901行）
│       └── resources/
│           ├── fabric.mod.json               # Fabric 模组清单
│           ├── wayfarer.mixins.json          # Mixin 配置（暂无注入类）
│           ├── assets/wayfarer/
│           │   ├── lang/
│           │   │   ├── zh_cn.yml             # 简体中文语言文件
│           │   │   └── en_us.yml             # 英文语言文件
│           │   └── icon.png                  # 模组图标
│           └── web/
│               ├── index.html                # Web 编辑器前端
│               └── static/
│                   └── app.js                # Web 编辑器 JS（1294行）
├── versions/
│   ├── 26.1.1/src/main/java/...
│   │   ├── WayfarerClient.java              # tick：client.gui.screen()
│   │   ├── WayfarerInitHandler.java
│   │   └── XaeroMapOverlay.java             # GuiGraphicsExtractor.fill()
│   └── 26.2/src/main/java/...               # 8个overlay文件（完整覆盖）
│       ├── WayfarerClient.java              # 同上 + 完整热键消费逻辑
│       ├── WayfarerInitHandler.java
│       ├── WayfarerConfig.java
│       ├── WayfarerConfigScreen.java
│       ├── MainMenuScreen.java
│       ├── RoadListScreen.java              # 适配Mojang gui重命名
│       ├── RoadMetadataScreen.java
│       └── XaeroMapOverlay.java             # Bresenham自绘粗线
└── README.md
```

---

## 3. 多版本分发机制

项目使用 `io.github.arthurbambou.preprocess` Gradle 插件实现单源码库多 MC 版本构建：

- **主源码**（`src/main/java/`）：基础代码，默认针对 MC 1.20.1
- **版本覆盖层**（`versions/<mcVersion>/src/main/java/`）：针对特定 MC 版本的差异代码
- **版本链**：`1.20.1 → 26.2 → 26.1.1`
- **关键 API 差异**：
  - `client.screen`（1.20.1）→ `client.gui.screen()`（26.2 Mojang 重命名）
  - `ScreenEvents.afterRender`（1.20.1）→ `ScreenEvents.afterExtract`（26.x）
  - `GuiGraphics`（1.20.1）→ `GuiGraphicsExtractor`（26.x）
  - 26.x 移除了 `RenderSystem.enableBlend/lineWidth`，改用 Bresenham 算法自绘粗线
  - 26.x 版本号三段式（"26.1.1" vs "1.20.1"），需额外处理

---

## 4. 核心功能模块

### 4.1 道路录制（RoadRecordingManager）

**入口**：按 `R` 键（可配置）触发 `handleToggleRecording`

**录制过程**：
1. 每 tick 采集玩家坐标（`player.getX/Y/Z`）
2. 若开启 `autoIntegral`，坐标自动舍入到整数（0.5 格精度门槛）
3. 采样距离阈值：0.5 格（三维欧氏距离），低于阈值不采样

**保存流程**（`saveRecording`）：
1. **端点吸附**（`autoSnapEndpoints`）：先找 ε 范围内已有节点 → 再找路段边缘投影垂足 → 无匹配则创建新节点
2. **轨迹简化**（`RoadSimplifier.simplify`）：
   - 阶段一：折返检测（Backtrack Removal）——新点在历史点阈值 1.5 格内判定为折返并截断
   - 阶段二：Ramer-Douglas-Peucker 算法——XZ 平面递归垂直距离比较
3. **构建 Node + Segment**：复用吸附的节点 UUID 创建 Segment
4. 弹出 `RoadMetadataScreen` 让用户选择/创建道路名称和分级

### 4.2 道路网络数据库（RoadNetworkDatabase）

单例内存数据库，三个 `ConcurrentHashMap` 存储 Node、Segment、Road，序列化到 `wayfarer/roads.json`。

**核心操作**：
- **CRUD**：完整增删改查，带乐观锁版本控制
- **节点合并**（`mergeNodes` / `mergeNodesWithCleanup`）：多个节点合并为一个，保留所有路段连接
- **软删除**（`softDeleteNode`）：端点缩短、偶数度中心节点配对合并，保证图连通性
- **路段合并/拆分**（`mergeSegments` / `mergeSegmentsAtNode` / `splitSegment`）
- **交叉口插点**（`insertNodeAtIntersection`）：两段相交时拆分共享新节点
- **图化**（`graphify`）：自动将度 >2 的内部节点拆分为端点，适配 Dijkstra/A* 算法
- **GeoJSON 导出**：供 Web 编辑器使用
- **增量查询**（`getDeltaSince`）：返回自某时间戳后的变更

### 4.3 HTTP API 服务器（WayfarerHttpServer）

基于 `com.sun.net.httpserver` 的嵌入式服务器，端口 7891（备 7892）。

**REST API 端点**：

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/api/roads` | 获取完整路网（GeoJSON + editor 数据） |
| GET | `/api/config` | 获取模组配置 |
| GET | `/api/roads/delta` | 增量数据查询 |
| PUT/DELETE | `/api/nodes/:id` | 更新/删除节点 |
| POST | `/api/nodes/merge` | 合并节点 |
| POST | `/api/nodes/merge-clean` | 合并并清理冗余 |
| POST | `/api/nodes/soft-delete` | 软删除节点 |
| POST | `/api/nodes/merge-segments` | 合并节点所属路段 |
| POST/DELETE | `/api/segments/:id` | 增删路段 |
| POST | `/api/merge` | 合并多段 |
| POST | `/api/split/:id` | 拆分路段 |
| POST | `/api/segments/:id/insert` | 路段末端插点 |
| POST | `/api/segments/intersection` | 交叉口插点 |
| PATCH/DELETE | `/api/roads/:id` | 更新/删除道路 |
| POST | `/api/roads/restore` | 恢复已删除道路 |

所有写操作携带版本冲突检测（409 返回）。

### 4.4 Xaero 世界地图叠加层（XaeroMapOverlay）

通过 `ScreenEvents.AFTER_INIT` 检测 Xaero `GuiMap` 屏幕，注册渲染回调。

**渲染流程**：
1. 反射读取 GuiMap 的 `scale`、`cameraX`、`cameraZ` 字段
2. 计算视口映射（`effectiveScale = scale / (guiScale * guiScale)`）
3. 视口裁剪：只绘制屏幕可见范围内的路段
4. 按道路分级着色：
   - 国道/高速（G）：橙色 #FF8800，线宽 8px
   - 省道/高架（S）：黄色 #FFFF00，线宽 6px
   - 县道（X）：绿色 #00FF00，线宽 4px
   - 乡道（Y）：蓝色 #4488FF，线宽 4px
   - 村道（C）：灰色 #888888，线宽 4px
5. 1.20.1 用 OpenGL `BufferBuilder` 三角形条带绘粗线；26.x 用 Bresenham 算法 + `GuiGraphicsExtractor.fill()`

### 4.5 图层系统（LayerManager）

可扩展的地图图层注册中心，当前内置四层：

| 图层 ID | 显示名 | Z-Index | 默认可见 |
|---|---|---|---|
| `xaero_base` | Xaero 世界地图 | 0 | 是 |
| `road_network` | 道路路网 | 100 | 是 |
| `administrative` | 行政区域 | 200 | 是 |
| `poi` | 兴趣点 | 300 | 是 |

`MapLayer` 接口含 `id`、`displayName`、`zIndex`、`visible` 四属性，支持外部扩展注册/注销（内置层不可删除）。

### 4.6 游戏内编辑器（RoadListScreen）

三栏布局编辑器（752 行），左栏道路列表 → 中栏 Segment 列表 → 右栏 Node 列表。

**功能**：
- `LIST` 模式浏览编辑，`SELECT` 模式选择道路（供外部调用）
- 搜索框过滤道路名称
- 拖拽 Segment 到目标道路分组
- 左键选中切换层级，右键循环 Status/CornerType
- 增删道路/Segment/Node 按钮
- 滚动缓存刷新
- 道路按 G/S/X/Y/C 分级颜色标识

### 4.7 Web 编辑器（Web 前端）

基于 Leaflet.js 的 Apple 风格地图编辑器（index.html + app.js）。

**功能**：
- MC 坐标（X, Z）映射到 Leaflet `[lat=Z/128, lng=X/128]`
- 五种编辑工具：移动节点（沿路段轴线约束拖拽）、描点（在路段上新增节点）、合并节点、分合（路段拆分/合并）、软删除
- 撤销/重做（Ctrl+Z / Ctrl+Shift+Z），最大 50 步
- 节点/路段属性面板（坐标、分级、编号、道路名、来源、状态）
- 工具栏紧凑/详细模式切换
- 缩放级别可配置（默认 maxZoom=10）

---

## 5. 数据模型

```
Road (道路)
├── id: UUID
├── name: String
├── color: String (#RRGGBB)
├── classification: String (G/S/X/Y/C/空)
├── number: String (编号，如 "107")
├── segmentIds: List<UUID>
└── version: int (乐观锁)

Segment (路段)
├── id: UUID
├── nodeIds: List<UUID> (有序节点序列)
├── roadId: UUID (所属道路)
├── source: Source (USER/AUTO)
├── status: Status (CONFIRMED/DRAFT)
└── version: int

Node (节点)
├── id: UUID
├── x, y, z: double (世界坐标)
├── cornerType: CornerType (SHARP/ROUND/AUTO)
├── source: Source
├── version: int
└── modifiedAt: long (Unix 毫秒时间戳)
```

**枚举值**：
- `Source`：`AUTO`（自动生成）、`USER`（用户创建）
- `Status`：`DRAFT`（草稿）、`CONFIRMED`（已确认）
- `CornerType`：`SHARP`（直角）、`ROUND`（圆角）、`AUTO`（自动）

---

## 6. 配置系统（malilib）

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `defaultClassification` | String | 空 | 新建道路默认分级代码 |
| `autoIntegral` | Boolean | true | 录制时自动取整坐标 |
| `autoSnapEndpoints` | Boolean | true | 保存时自动吸附端点 |
| `rdpEpsilon` | Double | 1.0 | RDP 简化容差 |
| `autoDeleteOrphanNodes` | Boolean | true | 自动删除孤节点 |
| `autoGraphify` | Boolean | true | 自动图化处理 |
| `webMaxZoom` | Integer | 10 | Web 端最大缩放等级 |
| `toggleRecording` | Hotkey | R | 切换录制 |
| `openMenu` | Hotkey | N | 打开菜单 |

配置存储在 `config/wayfarer.json`。

---

## 7. 技术亮点

1. **Ramer-Douglas-Peucker 简化**：录制轨迹后自动压缩冗余点，大幅减少存储开销
2. **端点吸附**：录制新道路时自动对接已有路网，避免断头路
3. **图化（Graphify）**：将原始路网转为信息学意义上的图结构（所有度 >2 节点均为端点），为后续寻路算法（Dijkstra/A*）做准备
4. **乐观锁版本控制**：Node/Segment/Road 均带 version 字段，API 层做冲突检测（409）
5. **Web 编辑器**：本地 HTTP 服务 + Leaflet.js 提供 Apple 风格的可视化路网编辑体验
6. **双层吸附**：先找已有节点，再找路段边缘投影，无匹配才创建新节点
7. **视口裁剪**：Xaero 叠加层只渲染可见范围路段，避免性能浪费
8. **跨版本适配**：通过 preprocess 插件 + overlay 源码覆盖，一套代码库支持三个 MC 版本

---

## 8. 类职责总览

| 类名 | 行数 | 职责 |
|---|---|---|
| `WayfarerClient` | ~153 | 模组入口，初始化各组件，处理 tick 和热键 |
| `WayfarerConfig` | 109 | 配置单例，封装热键查找和配置读取 |
| `WayfarerConfigs` | 62 | 配置项定义（malilib IConfigBase） |
| `WayfarerHotkeys` | 31 | 热键定义 |
| `WayfarerInitHandler` | — | malilib 初始化处理器 |
| `WayfarerConfigScreen` | 32 | malilib 配置界面 |
| `Reference` | — | MOD_ID / MOD_NAME 常量 |
| `MainMenuScreen` | 82 | 主菜单（道路管理 + 设置） |
| `RoadRecordingManager` | 244 | 录制逻辑、端点吸附、保存流程 |
| `RoadSimplifier` | 198 | 折返检测 + RDP 简化 |
| `RoadNetworkDatabase` | 1355 | 内存数据库，CRUD、合并、拆分、图化、序列化 |
| `RoadListScreen` | 752 | 三栏道路编辑器 UI |
| `RoadMetadataScreen` | 233 | 道路元数据编辑弹窗 |
| `XaeroMapOverlay` | ~245 | Xaero 地图叠加层渲染 |
| `WayfarerHttpServer` | 901 | 嵌入式 HTTP 服务器 + REST API |
| `Node` | 112 | 节点模型（POJO） |
| `Segment` | 92 | 路段模型（POJO） |
| `Road` | 116 | 道路模型（POJO） |
| `LayerManager` | 141 | 图层注册中心 |
| `MapLayer` | 50 | 图层接口 |
| `app.js` | 1294 | Web 前端编辑器 |

---

## 9. 国际化

支持 **简体中文** 和 **英文**，语言文件使用 YAML 格式（`zh_cn.yml` / `en_us.yml`），通过 yamlang 插件构建时转换为 Fabric 标准 JSON 格式。翻译覆盖主菜单、道路编辑界面、设置界面、热键说明等全部 UI 文本。

---

## 10. 当前状态与局限

- **Mixin**：`wayfarer.mixins.json` 中 `client` 数组为空，当前无需 Mixin 注入
- **图层系统**：`administrative` 和 `poi` 层已注册但尚未实现实际渲染逻辑
- **寻路**：`graphify` 已为 Dijkstra/A* 做好数据准备，但寻路功能尚未实现
- **道路编号**：支持国道/省道/县道/乡道/村道五级分类（G/S/X/Y/C），编号字段独立存储
- **版本号**：`Reference.java` 中硬编码 `0.2.0`，实际版本由构建系统（`gradle.properties`）管理为 `0.3.3`
