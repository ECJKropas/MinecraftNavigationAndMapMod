<!--
  Modrinth 模组描述（Markdown）
  使用说明：
  - 全文为 GitHub 风格 Markdown，可直接粘贴到 Modrinth 后台的 "Description" 字段。
  - 下文所有 `BANNER_URL` / `EDITOR_URL` / `XAERO_URL` / `RENDER_URL` 为图片占位符，
    请先在 Modrinth 后台上传图片到 Gallery，再把占位符替换成对应的图片直链。
  - `<wiki-url>` / `<issues-url>` / `<discord-url>` / `<source-url>` 请替换为真实链接。
  - 英文版在前，中文版在后，用 `---` 分隔。
-->

# Wayfarer (越陌度阡)

> Record, survey, and edit your road network in Minecraft — from the paths you walk to a complete road map.

Wayfarer is a **client-side** Fabric mod that turns the roads you travel into a structured **node – segment – road** graph. You can auto-record while walking, or precisely survey with a handheld tool, then view and refine the entire network in a built-in browser editor or as an overlay on Xaero's World Map.

Because it's fully client-side, **you can use it on any server, Realms, or single-player world** — no server install required, and other players don't need it.

## ✨ Features

- **Two collection modes**
  - **Auto-record** — press `R` to start and stop; Wayfarer samples your walk and simplifies it automatically.
  - **Survey mode** — hold a tool item (default: wheat seeds), left-click to place the start/end point, right-click for waypoints, and click an existing node to snap and connect.
- **Smart trajectory simplification** — backtrack detection plus the Douglas–Peucker algorithm compress thousands of raw samples into the key turning points.
- **Automatic graph building** — endpoints auto-snap and intersections auto-split, producing a true graph (not just a pile of lines).
- **In-browser live editor** — Wayfarer starts a local HTTP server at `http://localhost:7891` and serves a full Leaflet map editor: drag nodes (axis-constrained), insert/split/merge segments, soft-delete intersections, undo/redo, all with **real-time two-way sync** to the game and optimistic-concurrency conflict protection.
- **Chinese road classification** — G (national highway) / S (provincial) / Y (county) / X (township) / C (village) roads, color-coded with numbered badges on both the map overlay and the web editor.
- **Xaero's World Map overlay** — automatically detected and overlaid via reflection; works with or without Xaero installed.
- **One source, many versions** — a single codebase compiles for MC 1.20.1 and the 26.x series.

## 🚀 Record Your First Road

1. Press `R` and walk along a path.
2. Press `R` again — a road info screen appears.
3. Enter a name, pick a classification (e.g. G), and save. Done!

## 📷 Gallery

![Wayfarer banner](BANNER_URL)
![Browser editor](EDITOR_URL)
![Xaero overlay](XAERO_URL)
![In-game 3D render](RENDER_URL)

## 🎮 Controls

| Key | Action |
| --- | --- |
| `R` | Start / stop auto-recording |
| `N` | Open the main menu (routes list / settings) |
| `Ctrl` + `Alt` + `T` | Set the currently held item as the Survey tool |

> All keys are rebindable in the malilib config screen.

## 🛠 Installation

1. Install **Fabric Loader** (≥ 0.19.2).
2. Install **malilib** for your Minecraft version.
3. Download the Wayfarer JAR that matches your Minecraft version.
4. Place it in your `.minecraft/mods/` folder.
5. Launch the game.

**Dependencies**
- **malilib** (required) — config screen & key bindings
- **Fabric API** (required)
- **Xaero's World Map** (optional) — map overlay base layer
- **Java 17+** (Java 25 for 26.x versions)

## 🌐 Supported Versions

| Minecraft | Mod version |
| --- | --- |
| 1.20.1 | 0.3.3 |
| 26.1.1 | 0.3.3 |
| 26.2 | 0.3.3 |

## 📂 Data & Storage

- **Per-world storage** — each save/server gets its own `config/wayfarer/<world>/roads.json` (auto-migrated from older paths).
- **Auto topology** — pass-through nodes with degree > 2 are split so pathfinding (Dijkstra / A*) runs directly.
- **Orphan cleanup** — nodes with no connecting segment are removed automatically.
- **GeoJSON export** — export the full network as a standard GeoJSON `FeatureCollection`.

## 🔗 Links

- 📖 Documentation / Wiki: `<wiki-url>`
- 🐛 Report issues: `<issues-url>`
- 💬 Discord / Community: `<discord-url>`
- 📦 Source code: `<source-url>`

## 📜 License

Released under **GPL-3.0-only**.

---

# Wayfarer（越陌度阡）

> 在 Minecraft 中记录、测绘与编辑你的道路网络——从踩出的每一步，到一张完整的路网地图。

Wayfarer 是一个**客户端侧**的 Fabric 模组，把你在游戏里走过的路变成结构化的**节点 – 路段 – 道路**图数据。你可以边走边自动记录，也可以手持工具精确测绘，再在内置的浏览器编辑器或 Xaero 世界地图叠加层里查看与修正整张路网。

由于它是纯客户端模组，**你可以把它带进任何服务器、Realms 或单人世界**——无需服务端安装，其他玩家也不必安装。

## ✨ 功能特性

- **双模式采集**
  - **自动记录** —— 按 `R` 开始 / 停止，模组自动采样并简化你的行进轨迹。
  - **Survey 测绘** —— 手持工具物品（默认小麦种子），左键放置起点 / 终点，右键放置中间路径点，点击已有节点即可吸附并自动连接。
- **轨迹智能简化** —— 回溯检测 + Douglas–Peucker 算法，把成千上万的原始采样点压缩为关键拐点。
- **路网自动成图** —— 端点自动吸附、交叉自动分裂，生成的是真正的"图"，而非一堆折线。
- **浏览器实时编辑器** —— 模组在 `http://localhost:7891` 启动本地 HTTP 服务，提供完整的 Leaflet 地图编辑器：沿轴约束拖拽节点、插入 / 分裂 / 合并路段、软删交叉口、撤销 / 重做，全部与游戏端**实时双向同步**，并带乐观并发冲突保护。
- **中国道路分级** —— 支持国道(G) / 省道(S) / 县道(Y) / 乡道(X) / 村道(C)，在地图叠加层与网页端按等级着色并带编号徽章。
- **Xaero 世界地图叠加** —— 自动检测并通过反射叠加渲染路网图层；装不装 Xaero 都能用其他功能。
- **一套代码，多版本** —— 同源代码编译支持 MC 1.20.1 与 26.x 系列。

## 🚀 三步记录第一条路

1. 按 `R`，沿道路行走。
2. 再次按 `R`，弹出道路信息界面。
3. 输入名称、选择分级（如 G 国道）、保存。完成！

## 📷 截图展示

![封面图](BANNER_URL)
![浏览器编辑器](EDITOR_URL)
![Xaero 叠加](XAERO_URL)
![游戏内 3D 渲染](RENDER_URL)

## 🎮 按键

| 按键 | 功能 |
| --- | --- |
| `R` | 开始 / 停止自动记录道路 |
| `N` | 打开主菜单（路线列表 / 设置） |
| `Ctrl` + `Alt` + `T` | 将当前手持物品设为 Survey 工具 |

> 所有按键均可在 malilib 配置界面中自定义。

## 🛠 安装

1. 安装 **Fabric Loader**（≥ 0.19.2）。
2. 安装对应 MC 版本的 **malilib** 前置。
3. 下载与你的 Minecraft 版本匹配的 Wayfarer JAR。
4. 放入 `.minecraft/mods/` 目录。
5. 启动游戏。

**依赖**
- **malilib**（必需）—— 配置界面与按键绑定
- **Fabric API**（必需）
- **Xaero's World Map**（可选）—— 地图叠加底图
- **Java 17+**（26.x 版本需要 Java 25）

## 🌐 支持版本

| Minecraft | 模组版本 |
| --- | --- |
| 1.20.1 | 0.3.3 |
| 26.1.1 | 0.3.3 |
| 26.2 | 0.3.3 |

## 📂 数据与存储

- **按世界隔离** —— 每个存档 / 服务器独立 `config/wayfarer/<世界名>/roads.json`（自动从旧路径迁移）。
- **图自动拓扑** —— 度数 > 2 的穿行节点自动拆为端点，确保 Dijkstra / A* 可直接运行。
- **孤立节点清理** —— 自动删除无路段引用的节点。
- **GeoJSON 导出** —— 完整路网可导出为标准 GeoJSON `FeatureCollection`。

## 🔗 相关链接

- 📖 文档 / Wiki：`<wiki-url>`
- 🐛 反馈问题：`<issues-url>`
- 💬 社区 / Discord：`<discord-url>`
- 📦 源代码：`<source-url>`

## 📜 许可证

基于 **GPL-3.0-only** 协议发布。
