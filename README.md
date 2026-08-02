# Wayfarer（越陌度阡）

> 在 Minecraft 中记录、测绘与编辑你的道路网络——从踩出的每一步，到一张完整的路网地图。

Wayfarer 是一个客户端侧 Minecraft Fabric 模组。它把"走过的路"变成结构化的**节点-路段-道路**图数据：你可以边走边自动记录，也可以手持工具逐点精确测绘，再通过内置浏览器编辑器或 Xaero 地图叠加层查看与修正整张路网。

## 为什么选择 Wayfarer

- **双模式采集**：懒人按 `R` 自动记轨迹，工匠手持工具逐点测绘——两种精度，一个模组搞定
- **轨迹智能简化**：内置回溯检测 + Douglas-Peucker 算法，把成千上万的采样点压缩为关键拐点
- **路网自动成图**：端点自动吸附、交叉自动分裂，生成的是信息学意义上的"图"，而非一堆折线
- **浏览器实时编辑**：游戏内建 HTTP 服务，打开浏览器即可拖拽节点、合并路段、软删交叉口，与游戏端实时双向同步
- **中国道路分级**：支持国道(G)/省道(S)/县道(Y)/乡道(X)/村道(C) 分类，地图叠加与网页端按等级着色
- **多版本同源**：一套代码同时支持 MC 1.20.1 与新一代 26.x 版本

## 功能一览

### 记录与测绘

| 模式            | 触发方式                     | 适合场景                                 |
| --------------- | ---------------------------- | ---------------------------------------- |
| **自动记录**    | 按 `R` 开始/停止             | 快速走过一条路，模组自动采样并简化       |
| **Survey 测绘** | 手持工具物品（默认小麦种子） | 精确放置节点、吸附已有节点、逐段构建路网 |

**自动记录**会自动完成：

- 采样间隔去重（0.5 格内不重复记录）
- 坐标取整（可配置）
- 回溯路径检测与裁剪
- Douglas-Peucker 曲线简化（容差可调）
- 首尾端点自动吸附到已有节点或路段垂足（自动分裂路段）

**Survey 测绘**支持：

- 左键点击方块：放置起点 / 终点
- 右键点击方块：放置中间路径点
- 点击已有节点：**吸附**到现有节点，自动建立连接
- `Ctrl` + 滚轮：切换角落类型（锐角/圆角等）
- `ESC`：取消当前录制
- 工具离手自动暂停，切回自动恢复（数据完整保留）
- 录制路径以末地烛粒子实时可视化

### 游戏内 3D 渲染

手持工具时，路网在三维世界中清晰可见：

- **节点线框**：颜色编码——起点(绿)、终点(红)、中间点(青)、枢纽≥3段(金)、孤立节点(浅红)
- **路径连线**：录制中的节点间显示引导线
- **悬停高亮**：准星对准的节点自动放大高亮
- **光柱指示**：节点上方渲染可配置高度与透明度的光柱，远距离即可定位

### 浏览器编辑器

游戏运行时自动启动本地 HTTP 服务（默认 `http://localhost:7891/`），打开即是一个完整的路网编辑器：

- **Leaflet 地图引擎**：MC 坐标映射为地图经纬度，支持缩放与平移
- **节点拖拽**：沿路段方向轴约束拖动（可切换自由模式），松手即保存
- **描点工具**：点击路段插入新节点；点击交叉处自动检测并插入共享节点（双路段同时分裂）
- **合并工具**：两点合并，智能识别相邻/间隔/同段不同段等情况
- **分合工具**：路段内节点拆分为两段；度数为 2 的端点合并为一路
- **软删除**：删除节点同时保持路段连续性——端点缩短、中心节点对向配对合并
- **撤销 / 重做**：快照式回滚，且只回滚修改一次的实体，**不会覆盖游戏端并发编辑**
- **实时同步**：每秒增量拉取，游戏内与浏览器双向可见
- **冲突保护**：乐观并发控制，版本不一致时返回 409，自动同步最新数据
- **道路分级渲染**：G/S 级道路着色 + 编号徽章，其余白底灰边
- **双语界面**：中文 / English 一键切换

### Xaero 地图叠加

自动检测 Xaero's World Map 并通过反射叠加渲染路网图层，按道路分级着色与设置线宽。无需 Xaero 前置也可正常使用其他功能。

### 数据与存储

- **按世界隔离**：每个存档/服务器独立 `config/wayfarer/<世界名>/roads.json`，自动从旧路径迁移
- **图自动拓扑**：开启后自动将度数 >2 的穿行节点拆为端点，确保 Dijkstra / A* 可直接运行
- **孤立节点清理**：自动删除无路段引用的节点
- **GeoJSON 导出**：完整路网可导出为标准 GeoJSON FeatureCollection

## 支持版本

| Minecraft | 模组版本 |
| --------- | -------- |
| 1.20.1    | 0.3.3    |
| 26.1.1    | 0.3.3    |
| 26.2      | 0.3.3    |

## 快速上手

### 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)（≥ 0.19.2）
2. 安装 **malilib** 前置（对应 MC 版本的 malilib）
3. 下载对应 Minecraft 版本的 Wayfarer JAR
4. 放入 `.minecraft/mods/` 目录
5. 启动游戏

### 按键

| 按键                 | 功能                           |
| -------------------- | ------------------------------ |
| `R`                  | 开始 / 停止自动记录道路        |
| `N`                  | 打开主菜单（路线列表 / 设置）  |
| `Ctrl` + `Alt` + `T` | 将当前手持物品设为 Survey 工具 |

> 所有按键均可在 malilib 配置界面中自定义。

### 自动记录一条路

1. 按 `R`，聊天栏提示"道路记录已开始"
2. 沿道路行走，模组自动采样
3. 再次按 `R`，弹出道路信息界面
4. 输入名称、选择分级（如 G 国道）、填写编号
5. 保存——道路数据写入当前世界的 `roads.json`

### 用 Survey 模式精确测绘

1. 手持任意物品，按 `Ctrl+Alt+T` 将其设为 Survey 工具（默认小麦种子，可在配置中修改）
2. 手持工具时，已有节点以线框和光柱显示
3. 左键点击地面方块：放置起点
4. 右键点击沿途方块：放置中间路径点
5. 左键点击终点方块或已有节点：结束录制
6. 在弹出的道路信息界面填写信息并保存

### 打开浏览器编辑器

游戏运行时，在浏览器访问 `http://localhost:7891/`（如端口被占用自动切换至 7892）。也可在路线列表中点击"在浏览器中预览"。

## 配置

通过 malilib 配置界面（游戏内按键可绑定打开），所有选项均可实时调整：

| 配置项                    | 默认值                  | 说明                       |
| ------------------------- | ----------------------- | -------------------------- |
| `autoIntegral`            | 开                      | 采样坐标自动取整           |
| `autoSnapEndpoints`       | 开                      | 录制结束自动吸附首尾端点   |
| `rdpEpsilon`              | 1.0                     | RDP 简化容差（格数）       |
| `autoDeleteOrphanNodes`   | 开                      | 自动删除孤立节点           |
| `autoGraphify`            | 开                      | 自动将路网转化为标准图拓扑 |
| `toolItem`                | `minecraft:wheat_seeds` | Survey 工具物品            |
| `toolItemEnabled`         | 开                      | 是否启用工具检测           |
| `nodeIndicatorEnabled`    | 开                      | 渲染节点光柱指示物         |
| `nodeIndicatorBeamHeight` | 32.0                    | 光柱高度（格）             |
| `nodeIndicatorBeamAlpha`  | 0.3                     | 光柱透明度                 |
| `webMaxZoom`              | 10                      | 网页地图最大缩放等级       |
| `defaultClassification`   | 无                      | 新建道路默认分级           |

## 依赖

- **[malilib](https://github.com/sakura-ryoko/malilib)**（必需）— 提供配置界面与按键绑定
- **Fabric API** — `fabric-lifecycle-events-v1`、`fabric-key-binding-api-v1`、`fabric-resource-loader-v0`、`fabric-screen-api-v1`、`fabric-rendering-v1`（仅 1.20.x）
- **Xaero's World Map**（可选）— 提供地图叠加底图
- **Java** 17+（26.x 版本需要 Java 25）

## 构建

```bash
# 构建全部 MC 版本
./gradlew buildAndGather -x spotlessCheck

# 构建并启动指定版本（1.20.1）
./gradlew :1.20.1:runClient

# 构建并启动指定版本（26.2）
./gradlew :26.2:runClient
```

构建产物集中在 `build/libs/` 目录。

## 技术架构

<details>
<summary>点击展开技术细节</summary>

- **多版本同源**：基于 [Fallen-Breath/preprocessor](https://github.com/Fallen-Breath/preprocessor) 预处理，一套源码编译三个 MC 版本
- **线程安全数据库**：`ConcurrentHashMap` + 双重检查锁单例，写操作全部 `synchronized`
- **乐观并发控制**：每个实体（Node/Segment/Road）独立版本号，Web↔游戏并发编辑仅同实体冲突返回 409
- **增量同步**：`getDeltaSince(timestamp)` 仅返回修改时间晚于客户端上次同步的实体
- **快照式撤销**：`restoreFromJson` 只回滚版本号恰好 +1 的实体，多次修改的实体保留（游戏端并发安全）
- **轨迹简化**：回溯检测（Z 形路径裁剪）→ Douglas-Peucker（XZ 平面垂直距离递归）
- **图自动拓扑**：`graphify()` 扫描度数 >2 的穿行节点并分裂路段，为路径规划铺路
- **NBT 感知工具匹配**：参考 Litematica 实现，支持 `item@damage{NBT}` 格式，耐久始终忽略
- **反射式地图集成**：通过反射读取 Xaero GuiMap 的 scale/camera 字段，无硬依赖
- **内嵌 HTTP 服务**：`com.sun.net.httpserver`，4 线程池，正则路由分发，端口冲突自动回退
- **yamlang 国际化**：游戏内 zh_cn / en_us 双语；网页端独立 i18n 模块

</details>

## 项目结构

```
src/main/java/com/ecjkim/wayfarer/client/
├── WayfarerClient.java          # 客户端入口，按键调度与世界初始化
├── WayfarerConfig.java          # 配置读取门面
├── ToolItemManager.java         # Survey 工具物品匹配与管理
├── config/                       # malilib 配置项与按键定义
├── road/
│   ├── RoadRecordingManager.java # 自动记录与端点吸附
│   ├── RoadSimplifier.java        # 回溯检测 + Douglas-Peucker
│   ├── record/SurveySession.java # Survey 测绘状态机
│   ├── server/WayfarerHttpServer.java # 内嵌 HTTP 服务 + REST API
│   ├── data/RoadNetworkDatabase.java  # 路网数据库（图操作核心）
│   ├── layer/                    # 地图图层注册
│   ├── model/                    # Node / Segment / Road 数据模型
│   └── XaeroMapOverlay.java      # Xaero 地图叠加渲染
└── render/                       # 3D 渲染（节点线框 / 光柱 / HUD）
```

## 开发文档

项目设计文档位于 `docs/` 目录，涵盖产品需求、实现计划、逆向工程报告等。

## License

[GPL-3.0-only](LICENSE)

## 致谢

- [Fallen-Breath/fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template) — 多版本构建模板
- [malilib](https://github.com/sakura-ryoko/malilib) — 配置与按键系统
- [Leaflet](https://leafletjs.com/) — 网页端地图引擎
- [Litematica](https://github.com/maruohon/litematica) — 工具匹配灵感来源
