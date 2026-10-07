# 导航功能实现计划（v1）

基于已共识的设计（聊天共识 + `docs/adr/0001-distance-gated-routing.md`）。代码落点：新增包 `com.ecjkim.wayfarer.client.road.nav`。

## 0. 前置确认

- 路由在 XZ 平面计算（`Node.getX()` / `getZ()`），忽略 Y。
- 图边 = 每条 `Segment` 内相邻 `Node` 对；`Direction` 为 `ONE_WAY_*` 时仅单向，`BIDIRECTIONAL` 双向。
- 每段代价经 `Segment.getRoadId()` → `RoadNetworkDatabase.getRoad(id).getClassification()` 取等级；classification 为空串时按默认速度。
- 依赖 `RoadNetworkDatabase`（图与节点/段）、`WayfarerHttpServer`（REST + 前端）、malilib 配置/按键，均为多版本同源，导航逻辑本身与 MC 版本无关。

## 1. 配置（malilib）

- 在 `WayfarerConfigs` 新增 `Nav` 分类：`NAV_SNAP_RADIUS`(32)、`NAV_REROUTE_THRESHOLD`(8)、`NAV_ARRIVAL_RADIUS`(5)、`NAV_DISTANCE_GATE`(200)、`NAV_SPEED_G`(5.5)、`NAV_SPEED_S`(5.0)、`NAV_SPEED_Y`(4.5)、`NAV_SPEED_X`(4.0)、`NAV_SPEED_C`(3.5)。
- 在 `WayfarerConfig` 增加对应 getter（仿 `getClassificationColor` 风格）。

## 2. 吸附工具 `LocationSnapper`

- `nearestNode(double x, double z, double radius)`：遍历 `getAllNodes()`，取 XZ 平面欧氏最近且在 radius 内者；超半径返回空（调用方报"附近无道路"）。

## 3. 路由核心 `Router`

- 构图：遍历 `getAllSegments()`，对每段相邻 `Node` 对建边；代价 = edgeLength / effectiveSpeed(class, tripDistance)。
- `effectiveSpeed`：`tripDistance < navDistanceGate` → 各等级速度向基准(4.3)收敛（弱化偏好）；≥ 阈值 → 使用 `navSpeed*` 原值（强偏好）。
- 算法：Dijkstra（或 A*，启发式 = 直线距离）。输入 `(startNode, endNode, tripDistance)`；输出 `Route`（有序 Node 列表 + 各边长度/代价 + 总代价 + 总距离 + 总 ETA）。
- 无路径：返回空（调用方报"无可达路线"）。

## 4. 导航会话 `NavigationSession`（状态机）

- 状态：`IDLE` / `ACTIVE` / `ARRIVED`。
- 持有：Route、起点、终点、当前进度（路线上最近点索引 + 投影位置）。
- `start(destX, destZ)`：吸附终点 → 吸附起点(玩家位置) → 调用 Router → 置 ACTIVE；任一侧超半径或无路径则失败并返回原因。
- `tick(playerX, playerZ)`：更新进度（最近点投影）；若偏离 > `navRerouteThreshold` 则从玩家位置重算；若距终点 < `navArrivalRadius` 则转 ARRIVED。
- `stop()`：回 IDLE，清空路线。
- 单例，供 Web 端与 HUD 共享。

## 5. 浏览器端（Leaflet + REST）

- `WayfarerHttpServer.registerRoutes()` 新增：
  - `POST /api/nav/start`（body: `{x,z}` 或 `{nodeId}`）→ 启动，返回 route GeoJSON。
  - `GET /api/nav/state` → 当前 route + 进度（玩家投影点）。
  - `POST /api/nav/stop`。
- 前端（`web/` 静态资源）：地图点击出现"导航到这里"；坐标输入框 + "导航"按钮；渲染路线 polyline + 起终点 marker；"取消导航"按钮。复用现有 `/static/(.+)` 与 `serveResource` 机制。

## 6. 游戏内（HUD + 启动）

- `NavHudRenderer`：每帧从 `NavigationSession` 读取，绘制 HUD（剩余总距离、距下路口 + 转向箭头相对行进方向、当前道路等级/名称、ETA）。不绘制 3D 世界路线。
- 启动：malilib 小窗输入 X/Z（仿现有配置/菜单风格）→ "导航"；或按键触发（在 `WayfarerHotkeys` 增 `NAV_TOGGLE`）。
- 到达：`ARRIVED` 时 HUD 显示"已到达"数秒后自动消失。

## 7. 多版本

- 路由 / Session / 吸附均为纯逻辑，无需版本分支。
- HUD 渲染与按键走现有多版本机制；前端为独立 web 资源，天然多版本。

## 8. 测试

- `RouterTest`：小图验证最短距离、门控偏好（短途 vs 长途路径差异）、无路径、吸附半径、未归类路段。
- 手测：记录一段路网 → 浏览器点选导航 → 走动验证 HUD 与自动重规划 → 到达自动结束。

## 9. 文档

- 更新 `README.md`：新增"导航"章节（启动/结束、HUD、配置项）。
- 维护 `docs/CONTEXT.md` 与 `docs/adr/0001-*`。
