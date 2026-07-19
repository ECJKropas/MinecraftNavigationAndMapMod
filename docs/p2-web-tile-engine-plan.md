# P2 web tile engine plan (revised)

## overview

Web 预览底图目前是透明占位。实现两套 MapProvider，用户在设置中选择。

**方案 A (XaeroProvider)**：通过反射直接读取 Xaero 运行时内存中的 tile 缓存（`MapProcessor` → `CurrentMapWorld` → `TileCache` → 已渲染的 `int[]` 像素数组），不碰磁盘文件，不解析 `region.xaero` 二进制。Xaero 更新后只要内部缓存结构不变就不需要改解析器。

**方案 B (SelfBuiltProvider)**：监听 Chunk 加载，`Heightmap.Types.WORLD_SURFACE` 取地表颜色，自建瓦片目录。

`MapProvider` 接口暴露 `int[] getTile(String dim, int tileX, int tileY)`，返回 256×256 的 ARGB 像素数组。`ProviderManager` 根据配置项路由到具体实现。`RoadPreviewServer` 不感知 tile 细节，只调用 `MapProvider.getTile()` 并做一次 PNG 编码输出。

**坐标系统**：`tileX = floor(worldX / 256)`, `tileY = floor(worldZ / 256)`, `pixelX = worldX % 256`, `pixelY = worldZ % 256`。瓦片路径 `/tiles/{dim}/{tileX}/{tileY}.png`，CRS 为 `L.CRS.Simple`（1 pixel = 1 block）。

---

## phase 1: MapProvider interface & settings

新建 `MapProvider` 接口，方法 `int[] getTile(String dim, int tileX, int tileY)`（返回 256×256 ARGB 像素数组，null 表示无数据）。新建 `ProviderManager` 单例，持有当前 `MapProvider` 实例并根据 `WayfarerConfig.mapProvider`（可选 `xaero` / `selfbuilt` / `none`，默认 `none`）初始化对应实现。

`SettingsScreen` 加一个下拉按钮选择底图方案，切换时 `ProviderManager` 重建 provider 实例。`RoadPreviewServer` 移除 `handleXaeroTile` 的业务逻辑，新 `handleTile` 端点只做：`int[] pixels = ProviderManager.getProvider().getTile(dim, tileX, tileY)` → 若非 null 则 `BufferedImage` → `ImageIO.write` 输出 PNG byte[] → HTTP 200。若 null 返回空占位 PNG。

**key files**: `MapProvider.java`（新增）, `ProviderManager.java`（新增）, `WayfarerConfig.java`, `SettingsScreen.java`, `RoadPreviewServer.java`

---

## phase 2: scheme A — XaeroProvider (memory-based)

### 2.1 locate xaero internals via reflection

通过反射链获取 Xaero 运行时缓存：`Minecraft.getInstance().screen` 若为 `xaero.map.gui.GuiMap` 实例，反射取 `mapProcessor` 字段 → `MapProcessor.getMapWorld()` → `MapWorld` 的 tile 缓存字段（推测为 `MapWorld.tileCache` 或 `CurrentMapWorld` 内的 `Map` 容器）。tile 缓存 key 为 `(x, y)` 坐标，value 为 `TileChunk` 对象，其中持有 512×512 的已渲染颜色数组。

不做磁盘扫描。不依赖 `.minecraft/XaeroWorldMap` 路径。不解析 `region.xaero`。Xaero 的存档路径（`CurrentSavePath` 或等效字段）通过反射获取，仅用于日志/调试，不参与像素读取。

### 2.2 convert 512px tiles to 256px tiles

Xaero 内部 tile 是 512×512（覆盖 32×32 chunk），Web 端需要 256×256（覆盖 16×16 chunk）。从 Xaero 的 `TileChunk` 中读取 512×512 `int[]`（ARGB），取左上角 256×256 子区域返回。若 Xaero 缓存未覆盖请求坐标，返回 null → 前端显示透明占位。

### 2.3 caching

缓存 `BufferedImage` 或 `int[]` 仅在 provider 内部做 LRU（~128 个 tile），不在 `RoadPreviewServer` 层缓存。PNG 编码仅在 HTTP 输出时做一次，不存磁盘。

**key files**: `XaeroProvider.java`（新增），`XaeroTileReflector.java`（新增，封装反射链和版本兼容）

**expected**: Xaero 地图打开期间 Web 端实时显示与游戏内一致的底图，无磁盘 I/O。

---

## phase 3: scheme B — SelfBuiltProvider

### 3.1 chunk listener

注册 `ClientChunkEvents.CHUNK_LOAD`。每个 Chunk（16×16）加载时，对每个 x/z 坐标调用 `level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)` 获取地表 Y（O(1) 查表，不遍历 build height。若无此 Heightmap 则 fallback 到 `MOTION_BLOCKING_NO_LEAVES`）。取 `level.getBlockState(pos).getMapColor(level, pos)` 得到 `MaterialColor`，转为 RGB 像素值。

每个 chunk 产出 16×16 像素。chunk 不直接写 tile 文件，计算其所属 tile（`tileX = floor(chunkX * 16 / 256)`, `tileY = floor(chunkZ * 16 / 256)`），将 tile 坐标加入 `dirtyTileSet`（去重）。

### 3.2 tile generation worker

后台线程消费 `dirtyTileSet`，攒 2 秒或累积 8 个 tile 后批量处理。对一个 tile，遍历其覆盖的 16×16 个 chunk（共 256×256 个 x/z 坐标），从已缓存的 Chunk 数据中拼出完整的 256×256 `int[]` 像素数组。若某 chunk 未加载，该区域留默认灰色。

worker 线程不应阻塞渲染线程，tile 生成过程中持有 `int[]` 即可。

### 3.3 tile storage

`TileWriter` 将生成的 `int[]` 写为 PNG：`wayfarer/tiles/{dim}/{tileX}/{tileY}.png`。`SelfBuiltProvider.getTile()` 优先读磁盘 PNG 返回 `int[]`（缓存后直接返回），磁盘没有则返回 null。不缓存 PNG byte[]，缓存 `int[]`。

**key files**: `SelfBuiltProvider.java`（新增）, `ChunkTileListener.java`（新增）, `TileGenerator.java`（新增）, `TileWriter.java`（新增）

**expected**: 探索过的区域逐步生成 tile，浏览器实时显示彩色地形从灰变彩。

---

## phase 4: endpoint & frontend alignment

`RoadPreviewServer` 新增 `GET /api/tiles/{dim}/{tileX}/{tileY}.png` 端点，内部只做 `ProviderManager → MapProvider.getTile() → int[] → BufferedImage → PNG`。旧 `/api/xaero/tiles/` 路径保留为别名，302 重定向到新路径。

Leaflet 端 `L.tileLayer('/api/tiles/overworld/{x}/{y}.png', {tileSize: 256})`。CRS 为 `L.CRS.Simple`，无需坐标转换。

---

## multi-version notes

方案 B 仅用原版 API（`Heightmap.Types.WORLD_SURFACE`, `getMapColor()`, `ClientChunkEvents`），Fabric API 在 MC 1.20 和 1.21.x 均提供，代码无需分版本。

方案 A 的反射链（`GuiMap.mapProcessor` → `MapProcessor.mapWorld` → tile 缓存）以 Xaero 1.37.2 和 1.44.2 为双锚点。`MapProcessor` 类路径 `xaero.map.MapProcessor` 在所有分析过的版本中不变。若未来版本 tile 缓存字段改名，`XaeroTileReflector` 做版本号检测（读 Xaero 的 `fabric.mod.json` 中 version 字段）后切分支，fallback 返回 null 即透明占位，不崩溃。

---

## key files summary

| file | phase | role |
|---|---|---|
| `MapProvider.java` | 1 | 接口 `int[] getTile(dim, tileX, tileY)` |
| `ProviderManager.java` | 1 | 根据配置创建/切换 provider 实例 |
| `WayfarerConfig.java` | 1 | 新增 `mapProvider` 配置字段 |
| `SettingsScreen.java` | 1 | 新增底图方案下拉选择 |
| `XaeroProvider.java` | 2 | 反射读 Xaero 内存 tile 缓存 |
| `XaeroTileReflector.java` | 2 | 封装反射链和版本兼容 |
| `SelfBuiltProvider.java` | 3 | Chunk 监听 + tile 生成调度 |
| `ChunkTileListener.java` | 3 | 注册 CHUNK_LOAD 事件，维护 dirtyTileSet |
| `TileGenerator.java` | 3 | 后台线程拼 256×256 像素数组 |
| `TileWriter.java` | 3 | `int[]` 写磁盘 PNG |
| `RoadPreviewServer.java` | 4 | `/api/tiles/` 端点，仅调 MapProvider + PNG 编码 |
