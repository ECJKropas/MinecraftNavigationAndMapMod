# Wayfarer 路网展示升级 — 设计文档

> 版本：V1.1.0 | 状态：复审通过 | 日期：2026-07-17 | 作者：ECJKim | 审核：C6H5Li3O7盐之有锂

## 1. 问题陈述

当前 Wayfarer 路网预览采用纯 Canvas 手绘渲染（内嵌 HTTP 服务），存在以下不足：

- 无地理底图参照，路网飘在白底上
- 无缩放/平移交互
- 无道路分级着色和沿路标签
- 无图层控制系统
- 数据孤立，无法与外部地图工具互通

## 2. 设计目标

1. **浏览器端**：Leaflet 引擎驱动，GeoJSON 路网图层 + 分级着色 + 沿路标签 + 可扩展图层系统
2. **游戏内**：Mixin 注入 Xaero 世界地图渲染管线，叠加路网图层
3. **双端共享**：统一路网数据模型，一次录制双端可见

## 3. 架构总览

```
┌─────────────────────────────────────────┐
│           Wayfarer 核心                  │
│  轨迹录制 · 交叉口识别 · 数据持久化       │
└──────────────────┬──────────────────────┘
                   │
┌──────────────────▼──────────────────────┐
│        LayerManager（新增）              │
│  · 图层注册/注销                         │
│  · 图层可见性/顺序控制                    │
│  · 图层数据源适配                         │
└──────┬───────────────────────┬──────────┘
       │                       │
┌──────▼──────┐         ┌──────▼──────────┐
│ 游戏内渲染   │         │ 浏览器渲染       │
│ Xaero Mixin │         │ Leaflet         │
│ + 自绘叠加层 │         │ + GeoJSON 图层   │
└─────────────┘         └─────────────────┘
```

## 4. 数据模型

### 4.1 RoadPath（道路）

现有字段保持兼容，新增：

```json
{
  "id": "uuid",
  "name": "G107 京深线",
  "number": "G107",
  "classification": "G",
  "width": 7,
  "points": [...],
  "segments": [...],
  "intersections": [...],
  "style": {
    "color": "#D9432B",
    "line_width": 3,
    "dash_pattern": null
  }
}
```

### 4.2 RoadSegment（路段）

```json
{
  "id": "seg-uuid",
  "parent_road_id": "uuid",
  "points": [...],
  "length": 1200.5,
  "start_intersection": "int-uuid-1",
  "end_intersection": "int-uuid-2"
}
```

### 4.3 Intersection（交叉口）

```json
{
  "id": "int-uuid",
  "position": {"x": 123, "y": 64, "z": 456},
  "type": "cross",
  "connected_segments": ["seg-1", "seg-2", "seg-3"],
  "name": "城南立交"
}
```

## 5. 图层系统设计

### 5.1 图层基类

```java
public interface MapLayer {
    String getId();           // 唯一标识
    String getDisplayName();  // 用户可见名称
    int getZIndex();          // 层叠顺序，越大越靠上
    boolean isVisible();      // 当前可见性
    void setVisible(boolean); // 切换可见性
    MapLayerDataProvider getDataProvider(); // 数据源
}
```

### 5.2 内置图层

| 图层 ID | 名称 | Z-Index | 数据来源 |
|---------|------|---------|----------|
| `xaero_base` | Xaero 世界地图 | 0 | Xaero 渲染缓存（游戏内）/ 深色网格或 Xaero 缓存瓦片（浏览器） |
| `road_network` | 道路路网 | 100 | RoadDataStore → GeoJSON（浏览器）/ RoadDataStore → 屏幕坐标投影（游戏内） |
| `administrative` | 行政区域 | 200 | 预留，未来实现 |
| `poi` | 兴趣点 | 300 | 预留，未来实现 |

### 5.3 图层控制 UI

**浏览器端**：Leaflet `L.control.layers` 扩展，自定义面板渲染每个图层条目（复选框 + 名称 + 缩略图标）。

**游戏内（初期方案）**：仅通过 Wayfarer 配置菜单（Mod Menu → Wayfarer → 图层）控制可见性，不侵入 Xaero UI 组件。
- 优点：避免 Mixin 与 Xaero UI 组件冲突，降低维护成本
- 劣势：切换图层需退出地图界面，体验稍逊
- 后续迭代：若需求强烈，再评估 Xaero 侧边栏注入方案

### 5.4 扩展方式

新增图层只需：
1. 实现 `MapLayer` 接口
2. 向 `LayerManager.register(layer)` 注册
3. 图层自动出现在双端的图层控制 UI 中

## 6. 道路分级配色

| 等级 | 颜色 | 线宽 (px) | 标签 |
|------|------|-----------|------|
| G 国道 | #D9432B 红 | 3-4 | 红底白字，始终显示 |
| S 省道 | #F0A030 橙 | 2-3 | 黄底黑字，始终显示 |
| X 县道 | 白底灰边 | 1.5 | 白底灰框，中缩放可见 |
| Y 乡道 | #adb5bd 灰 | 1 | 灰字，高缩放可见 |
| C 村道 | #dee2e6 浅灰 | 0.5 | 不显示标签 |

## 7. 分期规划

### P1：Leaflet Web 前端（2-3 周）

| # | 任务 | 模块 |
|---|------|------|
| 1 | `RoadDataStore.toGeoJson()` | Java |
| 2 | `/api/roads/geojson` 端点 | Java |
| 3 | LayerManager + 内置图层注册 | Java |
| 4 | Leaflet 页面（HTML/CSS/JS） | Web |
| 5 | 分级配色 + leaflet-textpath | Web |
| 6 | 图层控制面板 | Web |
| 7 | 交叉口标记 + 道路点选 + 搜索 | Web |
| 8 | 深色网格 TileLayer | Web |
| 9 | 三版本构建 + 兼容测试 | Gradle |
| 10 | 文档更新 | Docs |

### P2：Xaero Mixin 游戏内（3-4 周，P1 期间并行研究）

| # | 任务 |
|---|------|
| R1 | 逆向 XaeroPlus 源码（坐标投影 + Mixin 注入点） |
| R2 | 逆向 Xaero 地图 GUI 渲染流程 |
| R3 | Mixin 注入 + OpenGL 路网绘制 |
| R4 | 游戏内图层控制 UI |
| R5 | 多版本兼容适配 |

### P3：MC 地形底图（2-3 周）

| # | 任务 |
|---|------|
| 1 | 逆向 Xaero 地图缓存格式 |
| 2 | Xaero 缓存 → Leaflet 瓦片适配器 |
| 3 | 种子离线渲染（可选） |

## 8. API 接口规范

### 8.1 GET /api/roads/geojson

返回全部路网数据的 GeoJSON FeatureCollection。

**请求**：`GET /api/roads/geojson`

**响应 200**：
```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "id": "road-uuid-1",
        "name": "G107 京深线",
        "number": "G107",
        "classification": "G",
        "width": 7
      },
      "geometry": {
        "type": "LineString",
        "coordinates": [[120.0, 30.0], [120.1, 30.1], ...]
      }
    }
  ]
}
```

**响应 500**：
```json
{
  "error": "DATA_READ_ERROR",
  "message": "无法读取路网数据文件: roads.json"
}
```

**查询参数（可选）**：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `classification` | string | 无 | 按道路等级筛选，逗号分隔（如 `G,S`） |
| `bbox` | string | 无 | 边界框筛选 `minX,minZ,maxX,maxZ` |

**状态码**：

| 状态码 | 含义 |
|--------|------|
| 200 | 成功，返回 GeoJSON |
| 400 | 参数格式错误（如 bbox 格式非法） |
| 500 | 服务器内部错误（数据文件损坏/不存在/读取失败） |

### 8.2 GET /api/roads/{id}

返回单条道路详情。

**请求**：`GET /api/roads/{id}`

**响应 200**：
```json
{
  "type": "Feature",
  "properties": {
    "id": "road-uuid-1",
    "name": "G107 京深线",
    "number": "G107",
    "classification": "G",
    "width": 7,
    "length": 3520.5,
    "segments": ["seg-1", "seg-2"],
    "intersections": ["int-1", "int-2", "int-3"]
  },
  "geometry": { ... }
}
```

**响应 404**：
```json
{
  "error": "NOT_FOUND",
  "message": "道路不存在: road-uuid-1"
}
```

### 8.3 GET /api/layers

返回当前注册的所有图层元信息。

**请求**：`GET /api/layers`

**响应 200**：
```json
{
  "layers": [
    {
      "id": "road_network",
      "displayName": "道路路网",
      "zIndex": 100,
      "visible": true
    }
  ]
}
```

### 8.4 错误码汇总

| 错误码 | HTTP 状态 | 含义 |
|--------|-----------|------|
| `INVALID_PARAM` | 400 | 参数格式或取值非法 |
| `NOT_FOUND` | 404 | 指定道路/图层不存在 |
| `DATA_READ_ERROR` | 500 | 数据文件读取失败 |
| `DATA_PARSE_ERROR` | 500 | 数据文件格式错误 |
| `INTERNAL_ERROR` | 500 | 未分类的内部错误 |

---

## 9. 风险与缓解

| 风险 | 等级 | 缓解 | 补充措施 |
|------|------|------|----------|
| Xaero 更新导致 Mixin 失效 | 高 | 锁定支持版本范围；CI 版本兼容检测 | 运行时版本检查：启动时比对 Xaero 版本，不匹配则输出警告弹窗并自动禁用路网叠加（不崩溃），同时记录日志；用户手册中列出支持版本 |
| Xaero 缓存格式变化（P3） | 中 | 回退到深色网格底图；缓存读取失败静默降级 | P3 降级为"研究探索"，不列入正式开发计划，避免资源占用 |
| Leaflet 大数据量性能 | 低 | LOD 简化；视口裁剪；MVP 数据量可控 | 明确 LOD 阈值——缩放到 8 级以下用 Douglas-Peucker ε=2 简化，6 级以下 ε=5；前端 `L.geoJSON` 的 `onEachFeature` 中控制缩放可见范围

## 10. 非功能需求

### 10.1 性能指标

| 指标 | 目标值 | 基准测试环境 |
|------|--------|-------------|
| Web 页面加载 | ≤ 2s | CPU: i7-10700K, RAM: 16GB, GPU: RTX 2060；数据集: 500 条道路，平均长度 2000 格，视口内约 200 条可见；测量方式: Chrome DevTools Lighthouse Performance 评分 + `DOMContentLoaded` 事件时间 |
| 游戏内帧率影响 | ≤ 5% FPS 下降 | 同上硬件；视口内 200 条道路可见；测量方式: F3 调试屏帧率，对比启用/禁用路网叠加前后 30s 均值 |
| 大数据量衰减 | 2000 条道路下加载 ≤ 5s | 同上硬件；视口内约 500 条可见；允许启用 LOD 简化 |

### 10.2 兼容性

- 向后兼容现有 roads.json 格式（新字段均为 optional）
- 版本支持分阶段交付：P1 浏览器端三版本同步（1.20.1 / 26.1.1 / 26.2），P2 游戏内叠加随 Mixin 开发进度逐版本适配
- 缺少 classification 字段的旧道路默认按 C 村道渲染（浅灰，最低优先级）

---

## 附录 A：Xaero 版本支持矩阵

> 目标：Mixin 注入时锁定 Xaero 世界地图（Xaero's World Map）的已知兼容版本，启用运行时版本检查。

| MC 版本 | Wayfarer 版本 | Xaero WorldMap 推荐版本 | Xaero Minimap 推荐版本 | 说明 |
|---------|--------------|------------------------|----------------------|------|
| 1.20.1 | 1.0.x | 1.38.7 / 1.38.8 | 24.6.1 | Fabric 1.20.1，主流稳定版 |
| 1.26.1.1 | 26.1.x | TBD（逆向后锁定） | TBD | 26.1.1 为新版本号体系 |
| 1.26.2 | 26.2.x | TBD（逆向后锁定） | TBD | 26.2 为最新版本号体系 |

**运行时版本检测逻辑**：
1. 启动时通过 Mod 容器扫描 Xaero WorldMap mod 的 `fabric.mod.json` / `mods.toml` 获取版本号
2. 与支持矩阵比对：匹配则正常启用路网叠加 Mixin；不匹配则弹出警告对话框（`/wayfarer version-mismatch`），自动设置路网图层 visible=false
3. 用户可通过配置文件 `wayfarer.json` → `xaero.override_version_check=true` 强制启用（风险自负）
4. 所有版本检测结果写入 `logs/wayfarer.log`，包含检测时间、期望版本、实际版本、决策结果

**Xaero 更新策略**：
- 每个 MC 大版本下锁定 1-2 个 Xaero 版本作为正式支持版本
- 每次 Xaero 发布新版后，由维护者评估 Mixin 注入点是否仍然有效
- CI 构建流水线包含 Xaero 版本兼容检测任务，若目标缺失则构建失败并通知

---

## 附录 B：LOD 简化策略

| 缩放级别 | Douglas-Peucker ε | 效果 |
|----------|-------------------|------|
| ≥ 9 级 | 0（不简化） | 全精度渲染，适合近距离观察 |
| 7-8 级 | 2 | 合并相邻共线点，去除微小锯齿 |
| 5-6 级 | 5 | 大幅简化，保留主要走向 |
| ≤ 4 级 | 10 | 极度简化，仅保留大致轮廓 |

简化在前端 `L.geoJSON` 的 `coordsToLatLng` / 预处理阶段完成，避免每次渲染时重复计算。
