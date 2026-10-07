# Wayfarer（越陌度阡）docs 清理与功能状态分析报告

> 分析日期：2026-09-04 ｜ 分析范围：`docs/` 下全部 57 个文件
> 说明：本报告为「只读分析 + 清理建议」。所有删除 / 移动操作均需你确认后再执行，不会自动改动文件。

---

## 一、功能状态总览（基于 release-notes / ANALYSIS.md / business-logic-flowchart.md）

### ✅ 已实现（Implemented）

| # | 功能 | 落地版本 / 来源 |
|---|------|----------------|
| 1 | 道路自动录制（R 键，采样 + 去重） | 核心，v0.1+ |
| 2 | 轨迹智能简化（回退检测 + Douglas-Peucker） | RoadSimplifier，v0.2.2 |
| 3 | 端点 / 折线自动吸附（两段式 autoSnapEndpoints） | v0.3.1 |
| 4 | Node / Segment / Road 数据模型 + RoadNetworkDatabase | v0.3.1 迁移完成 |
| 5 | 图自动拓扑（graphify，度>2 拆分） | v0.3.2 auto-graphify |
| 6 | 路段拆分 / 合并工具（Fenhe） | v0.3.2 |
| 7 | 度数感知软删除（soft delete） | v0.3.2 |
| 8 | 智能合并 / 交叉口插点 | v0.3.2 |
| 9 | 孤立节点自动清理 | v0.3.2 |
| 10 | Web 编辑器（Leaflet）：拖拽 / 描点 / 合并 / 拆分 / 软删 / 撤销重做 / 分级着色 / 沿路标签 / 实时双向同步 / 乐观并发 409 | v0.3.x |
| 11 | Xaero 世界地图游戏内叠加（XaeroMapOverlay） | v0.2.1 起持续打磨 |
| 12 | 可扩展图层系统（LayerManager / MapLayer）基础实现 | xaero_base + road_network 已渲染 |
| 13 | 游戏内三栏编辑器（RoadListScreen） | 持续迭代 |
| 14 | Survey 勘测模式（状态机 / 渲染 / HUD / 热键 / 工具物品） | v0.4.0 |
| 15 | 节点指示渲染（NodeIndicatorRenderer，粒子路径可视化） | v0.4.0（注：原 node-indicator-plan 草案的"末地烛+光柱"方案被粒子方案取代） |
| 16 | Xaero 叠加未归属路段 / 路段起点吸附 / ToolItemManager | v0.4.0 |
| 17 | 可配置分级颜色与线宽 | v0.4.1 |
| 18 | 多版本构建（1.20.1 / 26.1.1 / 26.2，preprocess 覆盖） | 全周期 |
| 19 | 按世界隔离存储 + 旧路径自动迁移 | v0.4.0 |
| 20 | malilib 配置界面 | v0.2.1 |

### 🕓 待实现（To be implemented / 计划中未交付）

| # | 功能 | 状态说明 |
|---|------|----------|
| 1 | **寻路 / 导航（Dijkstra / A\*）** | graphify 已为寻路做好数据准备，但寻路功能本身**尚未实现**。PRD 明确"导航功能（路径规划/最短路径）"为 Out of Scope；modrinth 描述将其定位为"未来能力"。→ 探索方向，不在当前路线图 |
| 2 | **行政区域图层渲染（administrative）** | 已在 LayerManager 注册，但**无实际渲染逻辑**（ANALYSIS §10） |
| 3 | **兴趣点图层渲染（poi）** | 同上，已注册未渲染 |
| 4 | **Web 编辑器部分交互** | PRD UAT 中的"搜索飞移(UAT-08)"、"分类筛选端点 ?classification=(UAT-09)"、"点击道路弹出信息卡(UAT-06)" 需核对代码确认是否全部落地 → 部分待确认 |
| 5 | Xaero 小地图（Minimap）叠加 | PRD 明确 **Out of Scope**（仅世界地图）→ 明确不做，非待实现 |
| 6 | QGIS 直连 / MC 种子地形离线渲染（P3） | PRD 降级为"研究探索"，不列入开发计划 → 已放弃 |

### ❌ 已放弃 / 已移除（Abandoned / Removed）

| # | 功能 | 处置 |
|---|------|------|
| 1 | **浏览器地形瓦片预览**（MapProvider / SelfBuiltProvider / XaeroProvider + tile API 端点） | v0.2.1 实现后**随即彻底移除**，仅保留道路路网预览 → 核心功能被砍 |
| 2 | **RDP 公式解析器**（rdpEpsilonFormula，含 `[RW]/2` 占位符） | v0.2.2 加入，v0.3.0 移除，改为数值 epsilon → 已放弃 |
| 3 | **Road.width 字段及整套宽度配置** | v0.3.0 移除 → 已放弃 |
| 4 | **旧数据模型**（RoadPath / RoadPoint / RoadDataStore / RoadBook / RoadSegment / RoadStyle / RoadIntersection / RoadStorageContext / Geometry，共 9 文件） | 被 Node/Segment/Road 取代并删除 → 已淘汰 |
| 5 | **MC 种子地形离线渲染（P3）** | PRD 降级为研究探索 → 已放弃 |
| 6 | **Geoman 依赖** | v0.3.2 移除，替换为原生 SVG 拖拽 → 技术选型变更 |
| 7 | 旧 `dragMode` 字段 | v0.3.0 移除 |

---

## 二、文档清理建议

### A 类 · 明确误放，可直接删除（非文档文件，是日志 / 崩溃转储）

| 文件 | 大小 | 说明 |
|------|------|------|
| `docs/11:22:27] [Render thread:INFO] (WorldMap` | 19 KB | 游戏运行日志，误存进 docs |
| `docs/[16:46:48] [Render thread:INFO] (Minecra` | 23 KB | 游戏运行日志，误存进 docs |
| `docs/cause "this.level" is null` | 863 KB | 崩溃报告转储，误存进 docs |

> 这 3 个根本不是文档，是运行期产物，删除无风险。

### B 类 · 过期 / 重复 / 草稿计划（建议删除或归档）

| 文件 | 理由 | 建议 |
|------|------|------|
| `docs/road-recording-node-segment-migration-plan.md` | v1 草稿（含"待确认问题"），已被 v2 取代；迁移本身已于 v0.3.1 完成 | **删除**（被 v2 取代） |
| `docs/road-recording-node-segment-migration-plan_20260722_180815_145.md` | v2 最终版，迁移已完成 | **归档**（历史） |
| `docs/v0.3.1roads_update/Conversation1.md` (1724 行) | 原始 AI 对话转储，非文档 | **删除**（或归档） |
| `docs/v0.3.1roads_update/Conversation2.md` (365 行) | 原始 AI 对话转储 | **删除**（或归档） |
| `docs/v0.3.1roads_update/Conversation3.md` (1087 行) | 原始 AI 对话转储 | **删除**（或归档） |
| `docs/node-indicator-plan.md` | 状态「草稿」，节点指示功能已以粒子形态在 v0.4.0 实现，草案方案被取代 | **归档 / 删除** |
| `docs/remove-tile-preview-plan.md` | 地形瓦片预览移除执行计划，v0.2.1 已完成 | **归档**（历史） |
| `docs/road-simplifier-plan.md` | 轨迹简化计划，v0.2.2 已完成 | **归档**（历史） |
| `docs/p2-web-tile-engine-plan.md` | 已放弃的地形瓦片功能计划 | **归档**（历史/已放弃记录） |
| `docs/p2-implementation-plan.md` | Xaero 叠加部分已完成，web bridge 部分已放弃 | **归档** |
| `docs/审核意见.md` | PRD/设计文档审核意见，已解决（PRD 已 V1.1.0） | **归档**（历史） |

### C 类 · 已完成的历史计划（建议整文件夹归档，保留可追溯性）

`docs/v0.3.1roads_update/` 整个文件夹（v0.3.1 PRD、implementation_plan、sprint 计划、First_version_desc）对应已发布的 v0.3.1，**建议整体移入 `docs/archive/`**，不要把 4 个已完成计划继续留在主目录。

### D 类 · 建议保留的核心文档（不要动）

- `ANALYSIS.md`（项目分析，略旧，建议后续补到 v0.4.x）
- `business-logic-flowchart.md`（当前架构 / 流程图，保留）
- `PRD-Wayfarer-Road-Network-Upgrade.md`（主 PRD，产品基线）
- `ToAllAI.md`（AI 工作流指引）
- `multi-version-maintenance.md`（多版本维护手册）
- `modrinth-description.md`（Modrinth 商店描述）
- `superpowers/specs/2026-07-17-road-network-upgrade-design.md`（设计文档）
- `superpowers/specs/p2-xaero-research.md`（Xaero 逆向研究）
- `p2-xaero-reverse-engineering-report.md`（Xaero 逆向报告）
- `release-notes/*.md`（版本历史，保留）
- `project_skills/publish/*`（发布技能，保留）
- `auto_modrinth_upload.command`（发布脚本，保留 / 或归档）

### E 类 · 重组建议（非删除，优化结构）

1. **release-notes/ 与 releases/ 功能重复**：两者都是发布说明。建议把 `releases/v0.2.2.md` 移入 `release-notes/v0.2.2.md`（统一命名），删除空的 `releases/` 文件夹。
2. **docs/other_mods/*.jar（11 个 Xaero/malilib 的 jar）**：是逆向 / 兼容参考二进制，不是文档。建议移到 `docs/` 外的专用目录（如项目根 `ref/` 或 `libs/`），或保留但知悉用途；**不必删除**（开发可能需要）。
3. **docs/mcmod/*.md（4 个 mcmod.cn 站点通用编辑规范，最后更新 2021）**：平台通用规则，非项目文档。建议移到 `archive/` 或保留作发布参考。
4. **docs/8月12日/（B站运营方案 + 视频脚本）、docs/wayfarer-bilibili-video-plan.md、docs/zimu.txt**：营销 / 视频内容，与工程文档无关。建议移到 `docs/marketing/` 或 `archive/`，不删除。

---

## 三、建议执行的清理动作（待你确认）

**若全部同意，将执行：**
1. 删除 A 类 3 个误放文件（日志 / 崩溃转储）
2. 删除 B 类：`road-recording-node-segment-migration-plan.md`（v1 草稿）
3. 删除 B 类：`v0.3.1roads_update/Conversation1~3.md`（3 个 AI 对话转储）
4. 新建 `docs/archive/`，将下列移入归档：`road-recording-node-segment-migration-plan_20260722...md`、`remove-tile-preview-plan.md`、`road-simplifier-plan.md`、`p2-web-tile-engine-plan.md`、`p2-implementation-plan.md`、`审核意见.md`、`node-indicator-plan.md`、整个 `v0.3.1roads_update/`、`mcmod/`
5. 重组：`releases/v0.2.2.md` → `release-notes/v0.2.2.md`，删除空 `releases/`
6. `8月12日/`、`wayfarer-bilibili-video-plan.md`、`zimu.txt` 移到 `docs/marketing/`
7. `other_mods/*.jar` 移到项目根 `ref/`（或保留，见上）

> 删除采用"移到回收站/归档"优先策略，关键历史计划一律归档而非直接销毁，确保可追溯。
