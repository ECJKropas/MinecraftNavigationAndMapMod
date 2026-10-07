# 删除地形瓦片浏览器预览功能 - 执行计划

## 目标
彻底删除 Wayfarer 浏览器端的地形地图（瓦片）预览功能，保留：
- 道路录制与管理
- 游戏内道路显示（XaeroMapOverlay）
- 浏览器端道路预览（Leaflet + GeoJSON 路网叠加）

## 变更范围总览

| 操作 | 文件 | 说明 |
|------|------|------|
| DELETE | `src/main/java/com/ecjkim/wayfarer/client/road/map/MapProvider.java` | 瓦片提供者接口 |
| DELETE | `src/main/java/com/ecjkim/wayfarer/client/road/map/ProviderManager.java` | 瓦片提供者管理器 |
| DELETE | `src/main/java/com/ecjkim/wayfarer/client/road/map/SelfBuiltProvider.java` | 自建瓦片渲染 |
| DELETE | `src/main/java/com/ecjkim/wayfarer/client/road/map/XaeroProvider.java` | Xaero 瓦片源 |
| MODIFY | `src/main/java/com/ecjkim/wayfarer/client/WayfarerClient.java` | 移除 ProviderManager 初始化与引用 |
| MODIFY | `src/main/java/com/ecjkim/wayfarer/client/WayfarerConfig.java` | 移除 tileProviderMode 字段 |
| MODIFY | `src/main/java/com/ecjkim/wayfarer/client/config/WayfarerConfigs.java` | 移除 TileProviderMode 枚举与配置项 |
| MODIFY | `src/main/java/com/ecjkim/wayfarer/client/road/RoadPreviewServer.java` | 移除瓦片 API 端点、HTML 中瓦片层与轮询代码 |

---

## 详细变更

### 1. WayfarerConfigs.java（删除 TileProviderMode 枚举）

- 删除 `TileProviderMode` 枚举（第 31-68 行）
- 删除 `TILE_PROVIDER_MODE` 字段（第 91-92 行）
- 从 `OPTIONS` 列表（第 93-94 行）中移除 `TILE_PROVIDER_MODE`

### 2. WayfarerConfig.java（删除 tileProviderMode 字段）

- 删除 `public String tileProviderMode;`（第 36 行）
- 删除构造函数中 `this.tileProviderMode = ...`（第 43-44 行）

### 3. WayfarerClient.java（移除 Tile Provider 体系）

- 删除 import 行（32-34）：ProviderManager / SelfBuiltProvider / XaeroProvider
- 删除字段 `PROVIDER_MANAGER`（第 47 行）
- 删除 onInitializeClient 中的瓦片初始化块（第 55-68 行）
- 删除 shutdown 回调中的 `pm.shutdown()`（第 73 行，local 变量 pm 会因引用删除自然消失）
- 删除 `setTileProviderMode()` 方法（第 84-96 行）

### 4. RoadPreviewServer.java（移除瓦片相关 API 与前端代码）

**Java 后端**：

- 删除 import：`java.awt.image.BufferedImage`、`java.io.ByteArrayOutputStream`、`javax.imageio.ImageIO`、`ProviderManager`
- 删除字段：`providerManager`（第 58 行）、`tileCacheVersion`（第 60 行）
- 删除方法：`setProviderManager()`、`clearTileCache()`、`handlePlayerDimension()`、`handlePlayerPosition()`、`handleProviderStatus()`、`handleXaeroTile()`、`handleTile()`、`sendTransparentPng()`、`sendNoCacheTransparentPng()`、`tileCachePath()`
- 从 `start()` 删除 5 个 context 注册：`/api/xaero/tiles/`、`/api/tiles/`、`/api/player-dimension`、`/api/player-position`、`/api/provider-status`

**HTML 前端（createLeafletPage）**：

- 删除轻量网格瓦片层（`L.gridLayer({maxZoom:18,...}).addTo(map);`）
- 删除 `ChunkGridCanvas` 类定义和 `chunkGridLayer` 实例
- 删除 `satTiles` tileLayer 声明和 `.addTo(map)`
- 删除 `tileRetryTimer` 变量
- 删除 `pollDimension()` 函数与 `setInterval(pollDimension, 2000)` 调用
- 删除 `pollProvider()` 函数与 `setInterval(pollProvider, 1000)` 调用
- 删除 `currentDim` 和 `providerVersion` 变量
- 从 `L.control.layers` 中移除 `'卫星地图': satTiles` 和 `'区块网格':chunkGridLayer` 两项

### 5. 四个 map 包文件（直接删除）

- `src/main/java/com/ecjkim/wayfarer/client/road/map/MapProvider.java`
- `src/main/java/com/ecjkim/wayfarer/client/road/map/ProviderManager.java`
- `src/main/java/com/ecjkim/wayfarer/client/road/map/SelfBuiltProvider.java`
- `src/main/java/com/ecjkim/wayfarer/client/road/map/XaeroProvider.java`

---

## 保留确认

以下功能**不受影响**：
- RoadDataStore / RoadRecordingManager / RoadPath 模型
- RoadPreviewServer 的道路 API（`/api/roads`、`/api/roads/geojson`、`/api/roads/{id}`、`/api/layers`）
- Leaflet 道路预览页面（道路线、交叉口、标签、搜索、信息卡片）
- XaeroMapOverlay（游戏内小地图道路叠加）
- LayerManager 与应用宝图层
- WayfarerConfig 的道路宽度/分级配置
- 所有热键绑定
