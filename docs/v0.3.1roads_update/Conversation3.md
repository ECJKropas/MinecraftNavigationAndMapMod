这是我正在做的一个模组~

Wayfarer（越陌度阡）看起来是个很实用的 Minecraft Fabric 模组！记录道路轨迹并自动识别交叉口，还能在浏览器里预览地图，这个思路挺有意思的。

我注意到几个点：

**关于版本号** — 支持版本表里写的是 `26.1.1` 和 `26.2`，这是笔误吗？Minecraft 似乎没有这两个正式版本号，可能是想写 `1.21.1` 和 `1.21.2`？

**关于功能** — 浏览器预览用的是本地 HTTP 服务（7891 端口），这在客户端模组里算是比较少见的设计。实际运行中有没有遇到过端口冲突的问题？比如玩家同时开了其他占用 7891 端口的程序。

**关于数据存储** — 道路数据存在 `roads.json` 里，随着记录的路网越来越复杂，这个文件可能会变得很大。有没有考虑过按维度（Overworld/Nether/End）分文件存储，或者加个数据压缩/归档的机制？

另外，自动识别交叉口是基于什么逻辑？是判断两条轨迹的物理距离小于某个阈值就算交叉，还是有更复杂的几何计算？

你是想让我帮忙看看 README 有没有需要完善的地方，还是模组本身遇到了什么需要解决的技术问题？

我的模组最近想要优化路网系统,讨论了几个不同的方案,但我感觉我自己对我真正想要什么不是很清楚,能帮我理清一下吗

好的，我来帮你理清思路。先把几个文档里的核心信息梳理一下，然后帮你找到真正的方向。

---

## 先回顾：你目前在讨论什么

你上传了三个对话记录 + 一个最终方案文档，核心围绕一个 Minecraft Fabric 客户端模组 **Wayfarer（越陌度阡）**，功能是**记录道路轨迹并可视化**。

### 你的模组现状（v0.1.0）

| 方面 | 当前实现 |
|------|---------|
| 录制 | 按 `R` 开始/停止，记录玩家走过的坐标点序列 |
| 数据 | 一条道路 = 一串坐标点 + 名称 + 宽度 |
| 交叉口 | 自动识别（基于距离阈值） |
| 浏览 | 按 `N` 打开路线列表 |
| 预览 | 本地 HTTP 服务（7891端口），浏览器看二维地图 |
| 存储 | `.minecraft/config/wayfarer/roads.json` |

### 你和 ChatGPT 讨论后，提出的"改进方案"

核心变化是从**"记录轨迹"**升级为**"构建道路网络"**：

```
旧模型：Road = [Point, Point, Point...]  ← 一条折线

新模型：
  Node（节点）= 路口、分叉点、端点
  Segment（路段）= Node 之间的有序连接
  Road（道路）= 多个 Segment 的语义集合（如"中山路"）
```

还加了：
- `CornerType`（拐角类型：SHARP/ROUND/AUTO）
- 弦长检测（判断转弯是直角还是曲线）
- 线段相交检测（自动识别十字路口）
- 节点吸附（消除手抖偏差）
- 浏览器作为编辑平台

---

## 但我注意到一个根本矛盾

你在 **Conversation1** 里明确说了：

> "我这个模组作为**纯客户端辅助模组**，无法将其发送至服务器。换句话说，这个地图模组是**对于要给服务器制图的玩家的一个更便捷的游戏内操作平台**，而并非适用于所有玩家"

> "我其实一开始就是按照**'玩家知道自己要绘制一套地图''玩家就是制图人——有一定地理知识'**的"

但你在 **First_version_desc.md** 里设计的方案，大量使用了**自动推断**：
- 自动检测转弯生成节点
- 自动判断拐角类型
- 自动识别十字路口
- 弦长检测、速度变化分析……

**问题：这些自动算法是给"普通玩家无感使用"设计的，但你的目标用户是"主动制图的 Mapper"。**

---

## 帮你理清：你需要回答三个问题

### 问题一：你的用户到底是谁？

| 场景A：普通玩家 | 场景B：专业 Mapper |
|--------------|------------------|
| 走路时自动记录，事后看地图 | 主动规划要画哪些路 |
| 不需要懂道路拓扑 | 需要精确控制节点位置 |
| 算法容错率可以低 | 算法错误 = 后期大量手工修正 |
| 类似 Strava / 运动轨迹 | 类似 JOSM / ArcGIS |

**你的选择是什么？** 这会决定后续所有设计。

---

### 问题二：游戏内 vs 浏览器，各自承担什么？

你提到"玩家会因为没有参照物而失去空间感知"，想把操作转移到浏览器。但这里有个循环依赖：

```
游戏内没参照物 → 去浏览器编辑
↓
浏览器编辑需要看到道路 → 需要先有道路数据
↓
道路数据来自游戏内录制
↓
游戏内录制时没参照物 → 录不准
```

**可能的出路：**

| 方案 | 说明 |
|-----|------|
| **游戏内为主，浏览器为辅** | 游戏内做"粗录"（走一遍），浏览器做"精修"（调节点、命名、合并） |
| **浏览器为主，游戏内为眼** | 游戏内只负责"打点"（R/G/T），所有拓扑编辑在浏览器完成 |
| **混合实时** | 游戏内录制时，浏览器同步显示实时地图（需要 WebSocket 或轮询） |

---

### 问题三：你真正想解决的核心痛点是什么？

从文档里，我看到你提到了很多"想要"：
- 自动识别交叉口
- 弯道平滑
- 直角/曲线区分
- 拓扑导航
- 浏览器编辑
- 语义道路名
- 无感录制 vs 手动控制

但这些**不是并列的需求**，它们服务于不同的目标。你需要选一个**最痛的点**先解决。

---

## 我的建议：先做一个"最小可行决策"

与其一次性实现完整 GIS 系统，不如先明确**第一优先级**：

### 如果选 A：面向普通玩家（轨迹记录器）

**目标**：玩家走一遍，自动生成一张能看的地图  
**砍掉**：CornerType、弦长检测、拓扑合并、浏览器编辑  
**保留**：自动记录轨迹、简单交叉口识别、浏览器预览  
**下一步**：优化轨迹压缩算法（减少噪声点），让地图"看起来对"就行

### 如果选 B：面向专业 Mapper（制图工具）

**目标**：Mapper 主动、精确地构建道路网络  
**砍掉**：自动转角检测、弦长推断、速度分析（这些对 Mapper 是干扰）  
**保留**：手动节点（R/G）、浏览器精修、拓扑编辑、道路语义  
**关键改变**：
- **R = 开始/结束一段路的录制**（不是"自动分析"）
- **G = 在精确位置插入节点**（Mapper 走到路口按一下）
- **浏览器 = 真正的编辑工作台**（拖节点、合并道路、命名、调拓扑）
- **游戏内只负责"采集坐标"，所有智能判断放到浏览器**

---

## 你现在最需要做的决定

请回答我：

> **你的模组，是帮"普通玩家自动生成地图"，还是帮"Mapper 更高效地手工制图"？**

这两个方向的数据模型、交互流程、技术栈完全不同。选定了，我才能帮你把后续的开发路线真正压实。

我到希望两个都有保留,但是侧重第二个,第一个用Douglas-Peucker 优化一下?

明白了！你的方向是：**以 Mapper 手工制图为主，普通玩家自动记录为辅**。Douglas-Peucker 做轨迹压缩是个好选择——它能把玩家随便走的曲线简化成关键节点，正好和 Mapper 需要的"精确节点"形成互补。

我来帮你把这个双模式系统彻底理清，然后给出可落地的架构。

---

## 核心设计：两种模式，一套数据

```
┌─────────────────────────────────────────┐
│           Wayfarer 双模式架构            │
├─────────────────────────────────────────┤
│  模式A：Auto（自动轨迹）                  │
│  ─────────────────────                  │
│  目标：普通玩家无感记录                   │
│  输入：玩家移动轨迹（大量点）              │
│  处理：Douglas-Peucker 压缩               │
│  输出：一组 Segment（节点已优化）          │
│  交互：按 R 开始/停止，全程自动            │
├─────────────────────────────────────────┤
│  模式B：Survey（测绘模式）                │
│  ─────────────────────                  │
│  目标：Mapper 精确构建路网                │
│  输入：玩家主动控制的关键点                │
│  处理：无压缩，节点即意图                  │
│  输出：精确 Segment，拓扑可控              │
│  交互：R=开始/结束，G=强制节点，T=拐角类型  │
└─────────────────────────────────────────┘
           ↓
    统一进入 Road Network 数据库
           ↓
    浏览器编辑平台（精修、命名、拓扑）
```

---

## 关键问题：两种模式的节点怎么兼容？

| | Auto 模式 | Survey 模式 |
|--|-----------|-------------|
| **节点来源** | Douglas-Peucker 算法提取 | 玩家按键（R/G）精确指定 |
| **节点密度** | 稀疏（只保留特征点） | 完全由玩家控制 |
| **交叉口** | 事后检测（线段相交算法） | 玩家走到路口按 G |
| **拐角类型** | 压缩后统一为 SHARP（折线） | 玩家按 T 指定或自动推断 |
| **精度** | "看起来对" | "坐标精确" |

**兼容策略**：Auto 模式产出的 Segment 进入数据库后，Mapper 可以在浏览器里把它"升级"——拆分节点、调整位置、赋予语义。Auto 是草稿，Survey 是定稿。

---

## 数据模型（统一）

```json
{
  "nodes": {
    "n_001": { "x": 100, "z": 200, "y": 64, "source": "AUTO" },
    "n_002": { "x": 120, "z": 200, "y": 64, "source": "USER" }
  },
  "segments": [
    {
      "id": "seg_001",
      "nodeIds": ["n_001", "n_002", "n_003"],
      "source": "AUTO",
      "roadId": null,
      "status": "DRAFT"
    }
  ],
  "roads": [
    { "id": "road_001", "name": "中山路", "segmentIds": ["seg_001"] }
  ]
}
```

**关键字段**：
- `source`: `AUTO` | `USER` —— 知道这条 Segment 是自动生成的还是手工测绘的
- `status`: `DRAFT` | `CONFIRMED` —— Auto 模式产出默认是草稿，Survey 模式产出默认是确认稿，浏览器里可以互相转换

---

## 两种模式的录制流程

### 模式A：Auto（自动轨迹）

```
按 R 开始
  │
  ▼
记录玩家每 Tick 的坐标（原始轨迹点）
  │
  ▼
按 R 结束
  │
  ▼
Douglas-Peucker 压缩
  │
  ▼
生成 Segment（节点 = 压缩后的特征点）
  │
  ▼
status = DRAFT, source = AUTO
  │
  ▼
存入数据库
```

**Douglas-Peucker 阈值**：建议 2.0 格（epsilon=2.0）。MC 里道路通常是 1-3 格宽，这个阈值能把小幅抖动过滤掉，保留真正的转弯。

### 模式B：Survey（测绘模式）

```
按 R 开始
  │
  ▼
记录起点 Node
  │
  ▼
行走中 ───────────────────────┐
  │                            │
  ▼                            │
检测：移动距离 > 8格？ ──是──→ 自动记录 Node（可关闭）
  │ 否                         │
  ▼                            │
玩家按 G ──────────────────────┤
  │                            │
  ▼                            │
强制记录 Node（精确位置）       │
  │                            │
  ▼                            │
玩家按 T（可选）               │
  │                            │
  ▼                            │
切换最新 Node 的 cornerType    │
  │                            │
  ▼                            │
按 R 结束                      │
  │
  ▼
生成 Segment（节点即意图，无压缩）
  │
  ▼
status = CONFIRMED, source = USER
  │
  ▼
存入数据库
```

---

## 浏览器职责（统一编辑平台）

不管哪种模式产生的数据，最终都到浏览器里精修：

| 功能 | 说明 |
|------|------|
| **节点拖拽** | 调整位置（解决 Auto 模式的偏差） |
| **节点拆分/合并** | 把 Auto 的稀疏节点细化，或把 Survey 的密集节点简化 |
| **Segment 合并** | 把被路口切碎的 Segment 合并成一条 Road |
| **赋予语义** | 给 Road 命名、设颜色、加属性 |
| **拓扑修正** | 手动连接/断开节点，修复交叉口 |
| **模式转换** | DRAFT → CONFIRMED，或反过来 |

---

## 你现在真正需要做的决策

架构已经清晰了，但你还需要选一个**第一优先级**：

| 选项 | 工作量 | 效果 |
|------|--------|------|
| **A. 先做 Auto 模式 + Douglas-Peucker** | 中等 | 普通玩家立刻能用，Mapper 有草稿可用 |
| **B. 先做 Survey 模式 + 浏览器编辑** | 较大 | Mapper 核心体验立住，Auto 后续补 |
| **C. 先做统一数据模型 + 浏览器基础** | 较小 | 地基打好，两边都能接 |

我的建议：**选 C**。因为：
- Auto 和 Survey 的数据最终要进同一个数据库
- 浏览器是两种模式的共同出口
- 先把 `Node` / `Segment` / `Road` 三层模型 + JSON 序列化 + 浏览器基础预览做出来，后面接 Auto 或 Survey 都只是"前端交互不同"

---

## 如果你选 C，第一周的开发清单

1. **数据层**（1天）
   - `Node`、`Segment`、`Road` 三个 POJO
   - `RoadNetworkDatabase` 单例（管理三个 Map）
   - Gson 序列化/反序列化
   - 存到 `config/wayfarer/roads.json`

2. **浏览器桥梁**（1-2天）
   - 内嵌 HTTP 服务（你已经有了，7891端口）
   - `/api/roads` 返回 GeoJSON
   - 基础 HTML 页面，Leaflet 加载显示

3. **Survey 模式骨架**（2天）
   - 绑定 R/G/T 键
   - 录制状态机（IDLE → RECORDING）
   - 按 R 记录起点/终点，按 G 记录中间节点
   - 生成 Segment 存入数据库

4. **Auto 模式骨架**（1天）
   - 按 R 开始/结束
   - 记录原始轨迹点
   - Douglas-Peucker 压缩（先调库，后期自己实现）
   - 生成 DRAFT Segment

5. **渲染层**（1天）
   - 游戏内绘制当前录制中的轨迹（临时）
   - 节点用小球标记（区分来源颜色）

---

## 最后一个问题

你的浏览器预览现在已经是本地 HTTP 服务了。你想把浏览器做成**只读预览**，还是**可编辑**？

- **只读**：游戏内录完，浏览器看，修改回游戏内做。简单，但 Mapper 体验割裂。
- **可编辑**：浏览器拖节点、改名字、调拓扑，同步回游戏。体验好，但需要双向同步机制。

这会影响你第一周的技术选型。你想先做哪种？

可编辑

明白了，可编辑浏览器。这意味着你需要**双向同步**：浏览器改完能回写到游戏，游戏内录制的新数据能实时推给浏览器。

这个方向体验最好，但技术复杂度也最高。我来帮你把架构彻底压实，避免后期返工。

---

## 核心问题：双向同步的时序

```
游戏内录制 ──→ 内存数据库 ──→ 浏览器显示
     ↑                              │
     └──────── 浏览器编辑回写 ←──────┘
```

**关键矛盾**：浏览器编辑时，游戏可能也在录制新数据。两边同时改同一个 Segment 怎么办？

---

## 解决方案：版本化 + 乐观锁

每个 `Segment` 和 `Node` 加一个 `version` 字段：

```json
{
  "nodes": {
    "n_001": { "x": 100, "z": 200, "version": 3 }
  },
  "segments": {
    "seg_001": { "nodeIds": ["n_001", "n_002"], "version": 5 }
  }
}
```

**同步规则**：
- 浏览器拉数据时，拿到当前版本号
- 浏览器提交修改时，带上 `expectedVersion`
- 游戏端比较：如果内存里的版本 > `expectedVersion` → 冲突，返回 409
- 浏览器收到 409 → 刷新数据，让用户重试

**为什么乐观锁够用**：MC 里一个玩家不太可能同时开游戏录路和浏览器编辑同一段路。冲突概率极低，真冲突了提示一下就行。

---

## 浏览器编辑的具体功能（优先级排序）

| 优先级 | 功能 | 技术方案 |
|--------|------|----------|
| P0 | 拖节点改位置 | Leaflet 可编辑 GeoJSON，mouseup 发 PUT |
| P0 | 给 Road 命名 | 侧边栏表单，PATCH `/api/roads/:id` |
| P0 | 删除节点/Segment | DELETE，级联检查 |
| P1 | 合并两个 Segment | 选两段，发 POST `/api/merge`，后端拓扑重算 |
| P1 | 拆分 Segment（在某节点处切断） | POST `/api/split/:segmentId/:nodeIndex` |
| P1 | 手动连接两个节点（创建新 Segment） | POST `/api/segments`，传两个 nodeId |
| P2 | 撤销/重做 | 浏览器端维护操作栈，或后端加 `undoLog` |
| P2 | 实时同步（游戏内录时浏览器自动刷新） | WebSocket 或 SSE，或轮询（先轮询，后期升级） |

---

## 第一周开发清单（可编辑浏览器版）

### Day 1：数据层 + 版本化

```java
// Node.java
public class Node {
    UUID id;
    double x, y, z;
    CornerType cornerType;
    Source source; // AUTO / USER
    int version;   // 乐观锁
    long modifiedAt;
}

// Segment.java  
public class Segment {
    UUID id;
    List<UUID> nodeIds;
    UUID roadId;
    Source source;
    Status status; // DRAFT / CONFIRMED
    int version;
}

// Road.java
public class Road {
    UUID id;
    String name;
    String color;
    List<UUID> segmentIds;
    int version;
}

// RoadNetworkDatabase.java
public class RoadNetworkDatabase {
    Map<UUID, Node> nodes = new ConcurrentHashMap<>();
    Map<UUID, Segment> segments = new ConcurrentHashMap<>();
    Map<UUID, Road> roads = new ConcurrentHashMap<>();
    
    synchronized void incrementVersion(UUID id) { ... }
    boolean checkVersion(UUID id, int expected) { ... }
}
```

**关键**：所有修改操作（游戏内录制、浏览器编辑）都走 `RoadNetworkDatabase` 的统一接口，自动维护 `version` 和 `modifiedAt`。

### Day 2：HTTP API（RESTful）

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/roads` | 全量 GeoJSON（浏览器初始化） |
| GET | `/api/roads?since=timestamp` | 增量同步（只给修改过的） |
| PUT | `/api/nodes/:id` | 更新节点位置（body: `{x, z, expectedVersion}`） |
| DELETE | `/api/nodes/:id` | 删除节点（级联删除关联 Segment） |
| POST | `/api/segments` | 创建新 Segment（手动连接两节点） |
| DELETE | `/api/segments/:id` | 删除 Segment |
| POST | `/api/merge` | 合并两段（body: `{segmentIds[], expectedVersions[]}`） |
| POST | `/api/split/:id` | 拆分（body: `{nodeIndex, expectedVersion}`） |
| PATCH | `/api/roads/:id` | 更新 Road 属性（name, color） |

### Day 3-4：浏览器前端

**技术栈**：纯 HTML + Leaflet（你已经有了），加 `leaflet-editable` 插件做节点拖拽。

**页面结构**：
```
┌─────────────────────────────┐
│  Leaflet 地图（可拖节点）      │
│                             │
│    ●────●────●              │
│         │                   │
│         ●                   │
│                             │
├─────────────────────────────┤
│  侧边栏：选中对象的属性编辑    │
│  - 节点坐标（可手动输入）      │
│  - 所属 Road（下拉选择）      │
│  - 拐角类型（SHARP/ROUND）    │
│  - 删除按钮                   │
└─────────────────────────────┘
```

**关键交互**：
- 点击 Segment → 高亮，侧边栏显示属性
- 拖拽节点 → `mouseup` 时发 PUT `/api/nodes/:id`
- 双击空白处 → 创建新节点（可选，先不做）
- Ctrl+点击两个 Segment → 显示"合并"按钮

### Day 5：Survey 模式骨架

- R 键：开始/结束录制
- G 键：强制插入节点
- T 键：切换最新节点的 cornerType
- 录制中的轨迹用临时 Segment 显示（不存数据库，结束后再存）

### Day 6：Auto 模式骨架 + Douglas-Peucker

```java
public class DouglasPeucker {
    List<Vec3d> simplify(List<Vec3d> points, double epsilon) {
        // 经典实现，epsilon 建议 2.0
    }
}
```

- 录制时记录原始点
- 结束时跑 Douglas-Peucker
- 输出节点序列，生成 DRAFT Segment

### Day 7：整合 + 测试

- 游戏内 Survey 录一段路
- 浏览器打开，拖节点、改名
- 游戏内再录一段，浏览器刷新看新数据
- 浏览器合并两段，验证拓扑

---

## 一个你可能没意识到的问题

**浏览器编辑时，游戏必须开着吗？**

| 方案 | 说明 | 复杂度 |
|------|------|--------|
| **A. 游戏必须开着** | HTTP 服务随游戏启动/关闭 | 低（当前方案） |
| **B. 游戏可关闭，浏览器独立编辑** | 浏览器直接读写 `roads.json` | 中 |
| **C. 混合：游戏开着实时同步，关了浏览器也能离线编辑** | 浏览器维护本地缓存，游戏启动时合并 | 高 |

如果选 **B**，浏览器需要能直接读写文件（通过 HTTP 服务代理，或浏览器用 File System Access API）。但 File System Access API 兼容性差。

**我的建议**：先 **A**，把体验做顺。后期如果 Mapper 反馈"想关游戏慢慢编辑"，再升级成 B。

---

## 最后一个确认

你现在的 HTTP 服务是用 Java 内嵌的（比如 NanoHTTPD / Spark / 自己写的）？还是用了 Fabric 的什么机制？

这会影响 Day 2 的 API 实现方式。如果是自己写的简单 HTTP 服务，可能需要扩展路由处理。告诉我现状，我可以给更具体的代码结构。

应该是自己写的

好的，自己写的 HTTP 服务。那我来帮你把浏览器可编辑的完整架构压实，包括服务端路由扩展和前端交互设计。

---

## 服务端架构：扩展你的 HTTP 服务

假设你现在的 HTTP 服务大概长这样：

```java
// 现有的大概结构
public class WayfarerHttpServer {
    private ServerSocket serverSocket;
    
    public void start() {
        // 在 7891 端口监听
        // 目前可能只处理了 GET /
        // 和 GET /api/roads
    }
}
```

你需要扩展成能处理 **RESTful API** 的结构。不用引入大框架，保持轻量。

### 路由表设计

```java
public class WayfarerHttpServer implements Runnable {
    private final RoadNetworkDatabase database;
    private final Gson gson = new Gson();
    private volatile boolean running = true;
    
    // 路由注册表：路径 + 方法 → 处理器
    private final Map<String, Map<String, Handler>> routes = new HashMap<>();
    
    public WayfarerHttpServer(RoadNetworkDatabase database) {
        this.database = database;
        registerRoutes();
    }
    
    private void registerRoutes() {
        // GET /api/roads → 全量 GeoJSON
        addRoute("GET", "/api/roads", this::handleGetRoads);
        
        // GET /api/roads?since=timestamp → 增量
        addRoute("GET", "/api/roads/delta", this::handleGetDelta);
        
        // PUT /api/nodes/:id → 更新节点
        addRoute("PUT", "/api/nodes/(.+)", this::handleUpdateNode);
        
        // DELETE /api/nodes/:id → 删除节点
        addRoute("DELETE", "/api/nodes/(.+)", this::handleDeleteNode);
        
        // POST /api/segments → 创建 Segment
        addRoute("POST", "/api/segments", this::handleCreateSegment);
        
        // DELETE /api/segments/:id → 删除
        addRoute("DELETE", "/api/segments/(.+)", this::handleDeleteSegment);
        
        // POST /api/merge → 合并两段
        addRoute("POST", "/api/merge", this::handleMerge);
        
        // POST /api/split/:id → 拆分
        addRoute("POST", "/api/split/(.+)", this::handleSplit);
        
        // PATCH /api/roads/:id → 更新 Road
        addRoute("PATCH", "/api/roads/(.+)", this::handleUpdateRoad);
        
        // GET / → 静态 HTML
        addRoute("GET", "/", this::handleStaticHtml);
        
        // GET /static/* → JS/CSS/图片
        addRoute("GET", "/static/(.+)", this::handleStaticFile);
    }
    
    private void addRoute(String method, String pathPattern, Handler handler) {
        routes.computeIfAbsent(method, k -> new HashMap<>())
              .put(pathPattern, handler);
    }
    
    @FunctionalInterface
    interface Handler {
        Response handle(Request req) throws Exception;
    }
    
    // 请求/响应的简易封装
    static class Request {
        String method;
        String path;
        Map<String, String> queryParams;
        String body;
        Map<String, String> pathParams; // 从正则提取的 :id
    }
    
    static class Response {
        int status;
        String contentType;
        String body;
        
        static Response json(int status, Object data) {
            return new Response(status, "application/json", new Gson().toJson(data));
        }
        
        static Response ok(Object data) { return json(200, data); }
        static Response created(Object data) { return json(201, data); }
        static Response noContent() { return new Response(204, null, null); }
        static Response conflict(String msg) { return json(409, Map.of("error", msg)); }
        static Response notFound() { return new Response(404, "text/plain", "Not Found"); }
    }
}
```

### 核心 API 实现示例

```java
// PUT /api/nodes/:id
private Response handleUpdateNode(Request req) {
    UUID nodeId = UUID.fromString(req.pathParams.get("id"));
    UpdateNodeRequest body = gson.fromJson(req.body, UpdateNodeRequest.class);
    
    Node node = database.getNode(nodeId);
    if (node == null) return Response.notFound();
    
    // 乐观锁检查
    if (node.version != body.expectedVersion) {
        return Response.conflict("Version conflict. Current: " + node.version);
    }
    
    // 应用修改
    node.x = body.x;
    node.z = body.z;
    node.y = body.y;
    node.version++;
    node.modifiedAt = System.currentTimeMillis();
    
    database.saveToDisk(); // 异步或同步保存
    
    return Response.ok(node);
}

// POST /api/merge
private Response handleMerge(Request req) {
    MergeRequest body = gson.fromJson(req.body, MergeRequest.class);
    
    // 检查版本
    for (UUID segId : body.segmentIds) {
        Segment seg = database.getSegment(segId);
        if (seg.version != body.expectedVersions.get(segId)) {
            return Response.conflict("Segment " + segId + " version mismatch");
        }
    }
    
    // 拓扑合并：两段首尾相连 → 合并成一段
    // 中间节点去掉，保留端点
    Segment merged = database.mergeSegments(body.segmentIds);
    
    return Response.created(merged);
}

// POST /api/split/:id
private Response handleSplit(Request req) {
    UUID segId = UUID.fromString(req.pathParams.get("id"));
    SplitRequest body = gson.fromJson(req.body, SplitRequest.class);
    
    Segment seg = database.getSegment(segId);
    if (seg.version != body.expectedVersion) {
        return Response.conflict("Version mismatch");
    }
    
    // 在 nodeIds[body.nodeIndex] 处切断
    // 生成两段新 Segment，共享切断点
    List<Segment> result = database.splitSegment(segId, body.nodeIndex);
    
    return Response.ok(result);
}
```

---

## 前端架构：Leaflet + 可编辑层

### 页面结构（index.html）

```html
<!DOCTYPE html>
<html>
<head>
    <link rel="stylesheet" href="/static/leaflet.css" />
    <style>
        #map { height: 70vh; width: 100%; }
        #sidebar { 
            height: 30vh; 
            border-top: 2px solid #333;
            padding: 10px;
            overflow-y: auto;
        }
        .node-marker { border-radius: 50%; }
        .node-SHARP { background: #ff4444; }
        .node-ROUND { background: #4444ff; }
        .node-AUTO { background: #888888; }
        .segment-AUTO { stroke: #888; stroke-dasharray: 5,5; }
        .segment-USER { stroke: #44f; }
        .segment-selected { stroke: #f44; stroke-width: 4; }
    </style>
</head>
<body>
    <div id="map"></div>
    <div id="sidebar">
        <h3>属性编辑</h3>
        <div id="selection-info">点击地图上的节点或路段进行选择</div>
        <div id="edit-form" style="display:none;">
            <label>名称: <input id="road-name" type="text"/></label>
            <label>颜色: <input id="road-color" type="color"/></label>
            <label>拐角类型:
                <select id="corner-type">
                    <option value="AUTO">自动</option>
                    <option value="SHARP">直角</option>
                    <option value="ROUND">圆弧</option>
                </select>
            </label>
            <button id="btn-save">保存</button>
            <button id="btn-delete">删除</button>
            <button id="btn-merge" style="display:none;">合并选中段</button>
            <button id="btn-split" style="display:none;">在此拆分</button>
        </div>
    </div>
    
    <script src="/static/leaflet.js"></script>
    <script src="/static/app.js"></script>
</body>
</html>
```

### 前端核心逻辑（app.js）

```javascript
class WayfarerEditor {
    constructor() {
        this.map = L.map('map').setView([0, 0], 10);
        this.nodes = new Map();      // id -> {lat, lng, data}
        this.segments = new Map();   // id -> {nodeIds, layer}
        this.selected = new Set();   // 选中的 segment ids
        this.lastSync = 0;
        
        // 使用 MC 坐标系：z 作为纬度，x 作为经度
        // 注意 Leaflet 默认是 lat/lng，需要适配
        this.initMap();
        this.loadData();
        this.startPolling(); // 轮询同步
    }
    
    mcToLatLng(x, z) {
        // MC 坐标直接映射，比例尺 1:1
        return [z, x];
    }
    
    async loadData() {
        const resp = await fetch('/api/roads');
        const geojson = await resp.json();
        this.renderGeoJSON(geojson);
    }
    
    renderGeoJSON(geojson) {
        // 清空现有
        this.nodes.clear();
        this.segments.clear();
        
        // 渲染节点
        geojson.nodes.forEach(n => {
            const marker = L.circleMarker(this.mcToLatLng(n.x, n.z), {
                radius: 6,
                className: `node-marker node-${n.cornerType || 'AUTO'}`,
                draggable: true  // 关键：可拖拽
            }).addTo(this.map);
            
            marker.on('dragend', (e) => {
                const latlng = e.target.getLatLng();
                this.updateNodePosition(n.id, latlng.lng, latlng.lat);
            });
            
            marker.on('click', () => this.selectNode(n));
            
            this.nodes.set(n.id, { marker, data: n });
        });
        
        // 渲染路段
        geojson.segments.forEach(s => {
            const coords = s.nodeIds.map(nid => {
                const n = this.nodes.get(nid);
                return this.mcToLatLng(n.data.x, n.data.z);
            });
            
            const polyline = L.polyline(coords, {
                className: `segment-${s.source}`,
                weight: 3
            }).addTo(this.map);
            
            polyline.on('click', (e) => {
                if (e.originalEvent.ctrlKey) {
                    this.toggleSelectSegment(s.id);
                } else {
                    this.selectSegment(s);
                }
            });
            
            this.segments.set(s.id, { layer: polyline, data: s });
        });
    }
    
    async updateNodePosition(nodeId, x, z) {
        const node = this.nodes.get(nodeId).data;
        const resp = await fetch(`/api/nodes/${nodeId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                x, z, y: node.y,
                expectedVersion: node.version
            })
        });
        
        if (resp.status === 409) {
            alert('冲突：游戏内已修改此节点，请刷新');
            this.loadData(); // 强制刷新
            return;
        }
        
        const updated = await resp.json();
        this.nodes.get(nodeId).data = updated;
        this.refreshSegmentsForNode(nodeId);
    }
    
    async selectSegment(segment) {
        // 高亮
        this.segments.forEach((s, id) => {
            s.layer.setStyle({ className: `segment-${s.data.source}` });
        });
        this.segments.get(segment.id).layer.setStyle({ 
            className: 'segment-selected' 
        });
        
        // 显示编辑表单
        document.getElementById('edit-form').style.display = 'block';
        document.getElementById('road-name').value = segment.roadName || '';
        
        // 绑定保存
        document.getElementById('btn-save').onclick = () => this.saveSegment(segment);
        document.getElementById('btn-delete').onclick = () => this.deleteSegment(segment.id);
    }
    
    async saveSegment(segment) {
        const name = document.getElementById('road-name').value;
        const color = document.getElementById('road-color').value;
        
        await fetch(`/api/roads/${segment.roadId || 'new'}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, color })
        });
        
        this.loadData(); // 刷新
    }
    
    // 轮询同步（先简单轮询，后期可升级 WebSocket）
    startPolling() {
        setInterval(async () => {
            const resp = await fetch(`/api/roads?since=${this.lastSync}`);
            if (resp.status === 200) {
                const delta = await resp.json();
                if (delta.hasChanges) {
                    this.loadData(); // 有变化就全量刷新（简单方案）
                }
            }
            this.lastSync = Date.now();
        }, 2000); // 2秒轮询
    }
}

// 启动
const editor = new WayfarerEditor();
```

---

## 关键交互细节

### 1. 节点拖拽后的路段自动刷新

```javascript
refreshSegmentsForNode(nodeId) {
    // 找到所有包含此节点的 Segment，重绘
    this.segments.forEach((seg, id) => {
        if (seg.data.nodeIds.includes(nodeId)) {
            const newCoords = seg.data.nodeIds.map(nid => {
                const n = this.nodes.get(nid);
                return this.mcToLatLng(n.data.x, n.data.z);
            });
            seg.layer.setLatLngs(newCoords);
        }
    });
}
```

### 2. Ctrl+多选合并

```javascript
toggleSelectSegment(segId) {
    if (this.selected.has(segId)) {
        this.selected.delete(segId);
        this.segments.get(segId).layer.setStyle({ opacity: 1 });
    } else {
        this.selected.add(segId);
        this.segments.get(segId).layer.setStyle({ opacity: 0.5 });
    }
    
    // 显示/隐藏合并按钮
    const mergeBtn = document.getElementById('btn-merge');
    mergeBtn.style.display = this.selected.size >= 2 ? 'inline' : 'none';
    mergeBtn.onclick = () => this.mergeSelected();
}

async mergeSelected() {
    const ids = Array.from(this.selected);
    const versions = {};
    ids.forEach(id => versions[id] = this.segments.get(id).data.version);
    
    const resp = await fetch('/api/merge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ segmentIds: ids, expectedVersions: versions })
    });
    
    if (resp.ok) {
        this.selected.clear();
        this.loadData();
    }
}
```

---

## 数据持久化：增量保存策略

```java
public class RoadNetworkDatabase {
    private final Path savePath;
    private long lastSaveTime = 0;
    private boolean dirty = false;
    
    // 每次修改后标记脏
    public synchronized void markDirty() {
        this.dirty = true;
    }
    
    // 异步保存（避免阻塞游戏主线程）
    public void asyncSave() {
        if (!dirty) return;
        dirty = false;
        
        CompletableFuture.runAsync(() -> {
            try {
                String json = gson.toJson(this.toJson());
                Files.writeString(savePath, json);
            } catch (IOException e) {
                // 保存失败，重新标记脏，下次再试
                dirty = true;
            }
        });
    }
    
    // 游戏内每 30 秒自动保存，或浏览器编辑后立即保存
}
```

---

## 第一周的精确任务清单

| 天 | 任务 | 产出 |
|---|------|------|
| 1 | 重构数据模型（Node/Segment/Road + version） | 3 个 POJO + Database 类 |
| 2 | 扩展 HTTP 服务（注册路由 + 请求解析） | 能响应 GET/PUT/POST/DELETE |
| 3 | 实现核心 API（节点 CRUD + 版本检查） | 浏览器能拖节点，保存不丢 |
| 4 | 前端基础（Leaflet 加载 + 节点拖拽） | 浏览器能看到路，能拖点 |
| 5 | Survey 模式（R/G/T 键绑定） | 游戏内能精确录路 |
| 6 | Auto 模式 + Douglas-Peucker | 自动轨迹压缩成 DRAFT |
| 7 | 整合测试（双向同步 + 冲突处理） | 游戏录 → 浏览器改 → 游戏刷新看到 |

---

## 一个需要你现在决定的问题

**Douglas-Peucker 的 epsilon 值，你打算固定还是可调？**

- **固定 2.0**：简单，但不同场景（城市街道 vs 山间小路）可能不适用
- **录制时可调**：按 `R` 开始时弹一个小 HUD 选"精细/标准/粗略"
- **浏览器里可调**：Auto 录完后在浏览器里重新跑压缩

我的建议：**先固定 2.0**，Survey 模式才是 Mapper 的主力。Auto 只是给普通玩家玩的，"看起来差不多"就行。

你觉得这个架构可行吗？有没有哪个部分需要我再细化？
