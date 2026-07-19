# P2 web tile engine plan

## overview

Wayfarer 的 Web 预览（Leaflet SPA at `localhost:7891`）目前底图是透明占位 + 网格。两个底图方案均需实现，用户在设置中选择。

**方案 A**：检测 Xaero 安装，解析其 tile 像素缓存（`region.xaero` 二进制），服务端转 PNG 输出。
**方案 B**：监听 Chunk 加载，`BlockState.getMapColor()` 取地表颜色，自建 `z/x/y.png` 瓦片目录，类似 BlueMap/Dynmap。

`region.xaero` 已知格式：512×512 RGB 像素数组。二进制头 `ff000600`，类 NBT 序列化存储方块状态。`PNGExporter` 类在 Xaero jar 中，但不提供运行时 API。

---

## phase 1: settings & tile engine router

在 `WayfarerConfig` 新增配置项 `tileEngine`，可选 `xaero` / `selfbuilt` / `none`（默认 `none`）。`SettingsScreen` 加一个下拉按钮切换。

新建 `TileEngineRouter` 单例，根据配置项选择引擎。`RoadPreviewServer` 中 `/api/xaero/tiles/{z}/{x}/{y}` 端点不再硬编码透明 PNG，改为委托给 `TileEngineRouter.renderTile(z, x, y)`。

**key files**: `WayfarerConfig.java`, `SettingsScreen.java`, `TileEngineRouter.java`（新增）, `RoadPreviewServer.java`

---

## phase 2: scheme A — xaero tile parser

### 2.1 find xaero world data

启动时扫描 `.minecraft/XaeroWorldMap/Multiplayer_<server>/`。取当前连接的服务器 IP，匹配目录名。从中找到当前维度（DIM0 / DIM-1 / DIM1）下的 `mw$default/`。

Xaero tile 命名格式：`x_y.zip`（不是 `z_x_y`）。zip 内只有一个 `region.xaero` 文件。

### 2.2 parse region.xaero binary

实现 `RegionXaeroParser`：读 zip 中的二进制，解析 512×512 RGB 像素数组，写入 `BufferedImage`，编码为 PNG byte[]。首字节 `ff000600` 做版本校验，不匹配则返回错误占位图。

因文件多在 1MB 左右，每次请求解析会较慢。加一个 LRU 内存缓存（~64 个 tile），命中时直接返回缓存 PNG。

### 2.3 xaero availability detection

如果 Xaero 未安装或当前维度无 tile 文件，`TileEngineRouter` 检测到方案 A 不可用时自动降级到透明占位，并在日志中 warn。设置界面显示当前方案状态（可用/不可用/解析中）。

**key files**: `RegionXaeroParser.java`（新增）, `XaeroTileLocator.java`（新增）, `TileEngineRouter.java`

**expected**: Web 页面刷新后显示 Xaero 世界地图底图，zoom/pan 正常，延迟在可接受范围。

---

## phase 3: scheme B — self-built tile engine

### 3.1 chunk listener

注册 `ClientChunkEvents.CHUNK_LOAD` 监听。每次 Chunk 加载（16×16×384）时，遍历顶层方块（从 build height 往下找第一个非空气方块），取 `BlockState.getMapColor()` 返回的 `MaterialColor`，对每个 x/z 坐标产出 1 像素颜色值（16×16）。

监听只记录"哪个 chunk 脏了"并写入一个队列，不立即生成 tile。

### 3.2 tile generation worker

后台线程消费脏 chunk 队列，攒够足够 chunk 或延迟 2 秒后批量生成 tile。一个 tile 默认 256×256 像素（覆盖 16×16 chunk 即 256×256 方块区域）。

`TileWriter` 将生成的 tile 存为 PNG：`wayfarer/tiles/<dim>/<z>/<x>/<y>.png`，z=0 时 1 tile ≈ 256×256 blocks。

### 3.3 tile serving

`TileEngineRouter` 在方案 B 模式下直接读本地 PNG 文件返回。若 tile 尚未生成（未探索区域），返回一个浅灰/空白的 256×256 PNG 占位。

**key files**: `ChunkTileListener.java`（新增）, `TileGenerator.java`（新增）, `TileWriter.java`（新增）

**expected**: 探索过的区域逐步生成 tile，浏览器端实时看到底图从灰色变成彩色地形。

---

## phase 4: web endpoint unification

`RoadPreviewServer.handleXaeroTile` 改名为 `handleTile` 并调用 `TileEngineRouter.renderTile(z, x, y)`。URL 路径保持 `/api/xaero/tiles/{z}/{x}/{y}` 以兼容已部署的 Leaflet 配置。

Leaflet 的 CRS 是 `L.CRS.Simple`（1 pixel = 1 block），不需要坐标转换。方案 A 和方案 B 在相同的 CRS 下输出一致的 tile，前端无感知切换。

---

## multi-version notes

方案 B 仅用原版 API（`BlockState.getMapColor()`, `ClientChunkEvents`），Fabric API 提供的 chunk 事件在 MC 1.20 和 1.21.x 均可用，代码无需分版本。

方案 A 的 `region.xaero` 二进制格式判断：历史经验表明 Xaero 1.37.2 和 1.44.2 的 tile 存储结构（`mw$default/x_y.zip` → `region.xaero`）未变化。若 26.2 版本中发现格式变更，按 `ff000600` 头中的版本号做分支处理。`Multiplayer_<server>` 目录命名在不同 Xaero 版本中一致。

文件系统路径（`.minecraft/XaeroWorldMap`）通过 `Minecraft.getInstance().gameDirectory` 获取，跨启动器兼容。

---

## key files summary

| file | phase | role |
|---|---|---|
| `WayfarerConfig.java` | 1 | 新增 `tileEngine` 配置字段 |
| `SettingsScreen.java` | 1 | 新增 tile engine 下拉选择 UI |
| `TileEngineRouter.java` | 1 | 方案选择和委托路由 |
| `RegionXaeroParser.java` | 2 | 解析 `region.xaero` 二进制 → PNG |
| `XaeroTileLocator.java` | 2 | 定位 Xaero tile 目录和 zip |
| `ChunkTileListener.java` | 3 | 监听 Chunk 加载记录脏区域 |
| `TileGenerator.java` | 3 | 后台线程生成 tile PNG |
| `TileWriter.java` | 3 | `getMapColor()` → RGB → 写 PNG 文件 |
| `RoadPreviewServer.java` | 4 | 端点委托给 `TileEngineRouter` |
