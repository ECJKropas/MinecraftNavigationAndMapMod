---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_96d12daf859a11f1a68c525400826444
    ReservedCode1: cpwiGJPbqXYMBlrd7/5I25xc+uW8QUxgU1Yr/X6UpkHIa7g4Evgzo8IPCoSzGyQMei6S5HVNYjb/B3qZ8v31l0Q+2eqJyJX5otPNAGtSU6LdMkATjFGHRUxj63eMzZlcja1jPh2aVcVv8vYjByybTb03f4UewrHWtQqy0Nnc1H5wJq1DMQ3byo8/qfU=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_96d12daf859a11f1a68c525400826444
    ReservedCode2: cpwiGJPbqXYMBlrd7/5I25xc+uW8QUxgU1Yr/X6UpkHIa7g4Evgzo8IPCoSzGyQMei6S5HVNYjb/B3qZ8v31l0Q+2eqJyJX5otPNAGtSU6LdMkATjFGHRUxj63eMzZlcja1jPh2aVcVv8vYjByybTb03f4UewrHWtQqy0Nnc1H5wJq1DMQ3byo8/qfU=
---

# Wayfarer（越陌度阡）v0.3.1roads 产品需求文档

## 1. 文档概览

| 项目 | 内容 |
|------|------|
| 版本号 | v0.3.1roads |
| 状态 | 草稿 |
| 创建日期 | 2026-07-22 |
| 项目 | Wayfarer - Minecraft Fabric 客户端道路网络模组 |

## 2. 背景与目标

### 2.1 当前版本 (v0.1.0) 现状

- 按 R 键录制玩家轨迹，记录坐标点序列为道路
- 数据模型：一条道路 = 坐标点数组 + 名称 + 宽度
- 交叉口基于距离阈值自动识别
- 本地 HTTP 服务（7891端口）浏览器查看二维地图
- 存储于 `config/wayfarer/roads.json`

### 2.2 核心问题

当前"轨迹=道路"的模型存在根本缺陷：玩家走过的路线不等于道路本身。无法表达道路交叉、匝道、分叉等拓扑关系。游戏内第一人称视角缺乏全局空间感知，难以精确编辑。

### 2.3 产品目标 (SMART)

为 Minecraft 专业制图员提供**"游戏内采集 + 浏览器精修"**的一体化道路网络构建工具：

- 采集效率：Mapper 完成 100 米道路录制时间 ≤ 2 分钟
- 编辑体验：浏览器内完成道路合并/拆分操作 ≤ 3 步
- 数据精度：节点位置偏差 ≤ 1.5 格
- 同步延迟：游戏 ↔ 浏览器数据同步 ≤ 2 秒

## 3. 用户画像

### 目标用户：专业 Mapper（制图员）

- 为服务器绘制地图的专业玩家
- 具备地理知识，明确知道要绘制什么
- 需要精确控制，而非无感自动记录

### 次要用户：普通玩家

- 走路时开启 Auto 模式自动记录
- 事后通过浏览器查看探索轨迹
- 不关心拓扑精确性

## 4. 范围定义

### 4.1 包含范围 (In Scope)

**游戏端（采集层）**
- 双模式录制：Auto 模式（Douglas-Peucker 压缩） + Survey 模式（R/G/T 精确控制）
- 三层数据模型：Node（节点）/ Segment（路段）/ Road（道路）
- 数据版本号（乐观锁）
- 按键绑定：R（开始/结束）、G（强制节点）、T（拐角类型切换）
- 录制中轨迹临时渲染
- JSON 持久化

**浏览器端（编辑层）**
- Leaflet 地图加载 GeoJSON
- 节点拖拽编辑
- 道路属性编辑（名称/颜色）
- 路段多选合并
- 路段拆分
- 轮询同步（2秒间隔）

**HTTP API（同步层）**
- RESTful CRUD（GET/PUT/POST/DELETE/PATCH）
- 版本检查与冲突处理
- 增量同步接口
- 静态文件服务

### 4.2 不包含范围 (Out of Scope)

- 多人协作编辑
- WebSocket 实时推送（后续版本）
- 离线编辑（游戏关闭后浏览器独立编辑）
- AI 道路智能推断
- 导航路径规划

### 4.3 MVP 边界 (MoSCoW)

| 优先级 | 内容 | 占比 |
|--------|------|------|
| **Must** | Survey 模式 + 数据模型 + 浏览器基础编辑 + API | 60% |
| **Should** | Auto 模式 + 多选合并 + 路段拆分 + 冲突处理 | 20% |
| **Could** | HUD 状态显示 + 候选节点提示 + 撤销重做 | 15% |
| **Won't** | 实时同步 + 离线编辑 + 多人协作 | 5% |

## 5. 价值主张

| 维度 | 现状 (Before) | 我们的方案 (How) | 改变后 (After) |
|------|--------------|-----------------|---------------|
| 数据模型 | 轨迹=道路，无法表达拓扑 | Node/Segment/Road 三层解耦 | 真正道路网络，支持导航 |
| 采集方式 | 自动记录，精度不可控 | 双模式：Auto 草稿 + Survey 精控 | 效率与精度兼得 |
| 编辑能力 | 游戏内第一人称，空间感知差 | 浏览器全局视图 + 拖拽编辑 | 所见即所得 |
| 数据质量 | 手抖偏差、噪声污染 | 节点吸附 + 版本锁 + DRAFT/CONFIRMED | 数据可信、可追溯 |

## 6. 用户故事

| 角色 | 动作 | 价值 | 验收标准 |
|------|------|------|----------|
| Mapper | 在游戏内骑马录制主干道 | 快速采集基础几何 | 按 R 开始，走完按 R 结束，HUD 显示节点数和长度 |
| Mapper | 在浏览器拖拽调整节点位置 | 精确修正采集偏差 | 拖拽松开后 PUT 成功，相邻路段自动刷新 |
| Mapper | 在浏览器合并被路口切碎的路段 | 保持道路语义完整性 | Ctrl+点击两段，点合并，生成一条 Road |
| Mapper | 游戏内按 G 强制在匝道口插入节点 | 精确控制拓扑断点 | 按 G 后当前 Segment 结束，新 Segment 开始 |
| 普通玩家 | 开启 Auto 模式随意行走 | 自动生成探索轨迹地图 | 按 R 开始自动录，结束后 Douglas-Peucker 压缩存 DRAFT |

## 7. 详细功能说明

### 7.1 数据模型

```
Node
├── UUID id
├── double x, y, z
├── CornerType cornerType (SHARP / ROUND / AUTO)
├── Source source (AUTO / USER)
├── int version
└── long modifiedAt

Segment
├── UUID id
├── List<UUID> nodeIds (有序，长度≥2)
├── UUID roadId
├── Source source
├── Status status (DRAFT / CONFIRMED)
└── int version

Road
├── UUID id
├── String name (可空)
├── String color
├── List<UUID> segmentIds
└── int version
```

### 7.2 双模式采集

**Auto 模式流程**：按 R 开始 → 逐 Tick 记录原始轨迹点 → 按 R 结束 → Douglas-Peucker 压缩 (epsilon=2.0) → 生成 DRAFT Segment → 存入数据库。

**Survey 模式流程**：按 R 开始 → 记录起点 Node → 行走中距离 > 8 格自动记录 Node / 按 G 强制记录 Node → 按 T 切换拐角类型 → 按 R 结束 → 生成 CONFIRMED Segment → 存入数据库。

### 7.3 浏览器编辑功能

| 功能 | 触发方式 | API |
|------|---------|-----|
| 拖拽节点 | 鼠标拖拽 → mouseup | PUT /api/nodes/:id |
| 编辑属性 | 点击路段 → 侧边栏修改 | PATCH /api/roads/:id |
| 删除节点 | 选中 → 删除按钮 | DELETE /api/nodes/:id |
| 合并路段 | Ctrl+点击两段 → 合并按钮 | POST /api/merge |
| 拆分路段 | 选中节点 → 拆分按钮 | POST /api/split/:id |

### 7.4 HTTP API

| 方法 | 路径 | 功能 | 关键参数 |
|------|------|------|----------|
| GET | /api/roads | 全量 GeoJSON | - |
| GET | /api/roads/delta | 增量同步 | ?since=timestamp |
| PUT | /api/nodes/:id | 更新节点位置 | {x, z, expectedVersion} |
| DELETE | /api/nodes/:id | 删除节点（级联） | - |
| POST | /api/segments | 创建 Segment | {nodeIds} |
| DELETE | /api/segments/:id | 删除 Segment | - |
| POST | /api/merge | 合并路段 | {segmentIds[], expectedVersions{}} |
| POST | /api/split/:id | 拆分路段 | {nodeIndex, expectedVersion} |
| PATCH | /api/roads/:id | 更新 Road 属性 | {name, color} |

**冲突处理**：所有修改操作携带 `expectedVersion`，版本不匹配返回 409 Conflict，浏览器端收到后全量刷新。

## 8. 技术架构

```
游戏端 (Fabric Mod)
├── 数据层: Node/Segment/Road POJO + RoadNetworkDatabase
├── 采集层: MovementAnalyzer + DouglasPeucker
├── 交互层: KeyBinding + HUD
├── 同步层: WayfarerHttpServer (REST API)
└── 渲染层: WorldRenderEvents (临时轨迹)

浏览器端 (HTML/JS)
├── 地图层: Leaflet + 可编辑插件
├── 数据层: 本地缓存 + 轮询同步
└── UI层: 侧边栏属性面板
```

## 9. 非功能需求

- **性能**：1000 个节点渲染帧率 ≥ 30 FPS，内存占用 ≤ 50 MB
- **兼容**：Minecraft 1.20.1 / 26.1.1 / 26.2
- **可靠**：HTTP 服务 7x24 小时不崩溃，数据自动备份

## 10. 风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|----------|
| 浏览器编辑冲突 | 中 | 中 | 乐观锁 + 409 提示 + 全量刷新 |
| 数据丢失 | 低 | 高 | 每次保存前自动备份上一版本 |
| 性能问题（大路网） | 中 | 中 | 分页加载 + 细节层次渲染 |
| 用户学习成本 | 高 | 中 | Survey 模式默认配置合理，Auto 模式零门槛 |

## 11. 迭代计划

### Sprint 1（第1周）：数据层 + 基础同步
- Node/Segment/Road POJO + version 字段 + RoadNetworkDatabase
- HTTP 服务扩展路由注册 + GET/PUT 基础 API
- 浏览器 Leaflet 基础渲染 + 节点拖拽

### Sprint 2（第2周）：Survey 模式 + 基础编辑
- R/G/T 按键绑定与录制状态机
- 浏览器属性编辑（名称/颜色）
- 增量同步 + 轮询机制

### Sprint 3（第3周）：Auto 模式 + 高级编辑
- Douglas-Peucker 轨迹压缩 (epsilon=2.0)
- 浏览器多选合并 + 路段拆分
- 冲突处理与用户提示

### Sprint 4（第4周）：优化 + 文档 + 发布
- 性能优化（渲染、内存、序列化）
- 完整测试用例
- 用户文档
- 发布 v0.3.1roads

### 后续版本 (v0.4.0+)
- WebSocket 实时同步 → 离线编辑 → 多人协作 → AI 辅助 → 跨平台编辑器
*（内容由AI生成，仅供参考）*
