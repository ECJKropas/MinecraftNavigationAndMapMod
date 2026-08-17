# Wayfarer 业务逻辑流程图

本文档整理了 Wayfarer 模组的完整业务逻辑，包括用户交互、数据流向、版本差异等。

---

## 一、Survey 勘测录制流程

```mermaid
stateDiagram-v2
    [*] --> IDLE: 初始化 / 进入世界
    IDLE --> RECORDING: 左键点击方块(空气)\n(创建起点节点)
    IDLE --> RECORDING: 点击已有节点\n(吸附作为起点)
    RECORDING --> IDLE: 左键点击方块(空气)\n(创建终点节点) → 完成录制
    RECORDING --> IDLE: 点击已有节点\n(吸附作为终点) → 完成录制
    RECORDING --> RECORDING: 右键点击方块\n(添加航点中间节点)
    RECORDING --> RECORDING: 右键点击已有节点\n(吸附作为航点)
    RECORDING --> PAUSED: 移除工具物品
    PAUSED --> RECORDING: 拾取工具物品\n(验证数据完整性)
    PAUSED --> IDLE: 数据完整性校验失败\n(取消录制)
    IDLE --> IDLE: 取消录制\n(清除所有数据)
    RECORDING --> IDLE: 取消录制\n(清除所有数据)
    IDLE --> RECORDING: 快捷键强制开始
    RECORDING --> IDLE: 快捷键强制结束

    note right of IDLE
      状态说明:
      IDLE: 空闲，等待用户操作
      RECORDING: 录制中，粒子特效显示路径
      PAUSED: 暂停，工具物品被移除
    end note
```

```mermaid
flowchart TD
    subgraph 用户输入
        A1[左键点击方块] --> B{是否已有节点?}
        A2[右键点击方块] --> C[创建航点节点]
        A3[快捷键操作] --> D{操作类型}
    end

    subgraph SurveySession 处理
        B -->|附近有节点| B1[吸附到现有节点]
        B -->|无附近节点| B2[创建新节点]
        B1 --> E{当前状态?}
        B2 --> E
        C --> F[仅在 RECORDING 状态有效]
        F --> F1[创建航点节点并添加到录制列表]
        D -->|forceStart| D1[在玩家位置创建起点节点]
        D -->|forceStop| D2[在玩家位置创建终点节点并完成录制]
        D -->|cancel| D3[调用 cancelRecording]
    end

    subgraph 完成录制流程
        E -->|IDLE + 首次点击| G[设为 RECORDING 状态]
        E -->|RECORDING + 末次点击| H[调用 finishRecording]
        H --> I{节点数量 >= 2?}
        I -->|否| J[清理孤立数据 提示节点太少]
        I -->|是| K[创建 Segment 并添加到数据库]
        K --> L{保存成功?}
        L -->|否| M[回滚 Segment 和节点]
        L -->|是| N[打开 RoadMetadataScreen]
    end

    subgraph RoadMetadataScreen
        N --> O{选择操作}
        O --> P[选择已有道路]
        O --> Q[创建新道路]
        O --> R[Discard 放弃]
        P --> S[关联 Segment 到已有 Road]
        Q --> T[填写道路名称/分类/编号 创建新 Road]
        R --> U[删除 Segment 及其专属节点]
        S --> V[保存并返回]
        T --> V
    end

    subgraph 吸附逻辑
        direction LR
        W[左键点击] --> X{自动吸附开启?}
        X -->|是| Y{snapToSegment}
        Y -->|找到最近折线| Z[在垂足插入节点 分裂路段]
        Y -->|无目标| AA[创建新节点]
        X -->|否| AA
    end
```

---

## 二、Web 编辑器与 API 流程

```mermaid
flowchart TD
    subgraph 初始化
        A[用户访问 HTTP 服务器\n端口 7891/7892] --> B[加载 index.html]
        B --> C[initMap 异步初始化]
        C --> D[GET /api/config\n加载分类样式]
        D --> E[GET /api/roads\n加载完整数据]
        E --> F[渲染所有线段/节点/道路标签]
        F --> G[setInterval 每1秒\n调用 loadDelta]
    end

    subgraph 增量同步
        G --> H[GET /api/roads/delta?since=时间戳]
        H --> I{有变更?}
        I -->|是| J[更新 roadStore 数据]
        I -->|否| K[保持现状]
        J --> L[重新渲染变更的实体]
        J --> M{正在编辑的实体?}
        M -->|是| N[跳过该实体更新\n防止覆盖用户编辑]
        M -->|否| L
    end

    subgraph 节点操作
        direction LR
        subgraph 移动工具
            T1[拖拽节点] --> T2[PUT /api/nodes/id\n更新坐标]
            T2 --> T3{版本匹配?}
            T3 -->|是| T4[保存成功 更新UI]
            T3 -->|否 409| T5[版本冲突 重新加载]
        end

        subgraph 点工具
            P1[点击地图] --> P2{最近交叉点?}
            P2 -->|是| P3[POST /api/segments/intersection\n在交叉点插入节点]
            P2 -->|否| P4[POST /api/segments/id/insert\n在路段上插入节点]
        end

        subgraph 删除
            D1[选中节点] --> D2[DELETE /api/nodes/id]
        end
    end

    subgraph 路段操作
        direction LR
        subgraph 方向编辑
            S1[选择路段] --> S2[修改方向下拉框]
            S2 --> S3[PATCH /api/segments/id/direction]
        end

        subgraph 合并
            M1[Ctrl+点击多选路段] --> M2[POST /api/merge\n合并选中路段]
        end

        subgraph 分裂
            SP1[POST /api/split/id\n在指定节点分裂路段]
        end

        subgraph 软删除
            SD1[选中节点] --> SD2[POST /api/nodes/soft-delete]
            SD2 --> SD3{节点度数?}
            SD3 -->|1度 端点| SD4[缩短路段 移除端点]
            SD3 -->|偶数度 中间| SD5[配对反向路段 合并删除中心]
            SD3 -->|奇数度| SD6[不支持软删除]
        end
    end

    subgraph 道路操作
        direction LR
        R1[编辑道路名称/分类/编号] --> R2[PATCH /api/roads/id]
        R2 --> R3{版本匹配?}
        R3 -->|是| R4[保存成功]
        R3 -->|否 409| R5[版本冲突 提示重新加载]
    end

    subgraph 撤销/重做
        U1[Undo 操作] --> U2[POST /api/roads/restore\n发送快照回滚]
        U2 --> U3[服务器版本对比\n智能回滚]
        U3 --> U4[返回差异统计]
        U4 --> U5[重新加载数据]
    end
```

---

## 三、数据生命周期流程

```mermaid
flowchart TD
    subgraph 持久化
        A[操作触发] --> B{数据库状态变更?}
        B -->|是| C[markDirty 设置脏标志]
        C --> D[saveToDisk\n序列化到 JSON]
        D --> E[写入 wayfarer/worldKey/roads.json]
        B -->|否| F[无操作]
    end

    subgraph 世界切换
        G[玩家切换世界] --> H[setWorldKey]
        H --> I{当前数据 dirty?}
        I -->|是| J[自动保存当前世界数据]
        I -->|否| K[跳过保存]
        J --> L[切换到新世界 key]
        K --> L
        L --> M[创建新目录结构]
        M --> N[loadFromDisk\n加载新世界数据]
        N --> O[autoGraphify\n自动图化处理]
    end

    subgraph 自动处理管线
        P[CRUD 操作后] --> Q[maybeCleanupOrphans]
        Q --> R{孤立节点清理开启?}
        R -->|是| S[删除无引用的节点]
        R -->|否| T[跳过]
        P --> U[maybeGraphify]
        U --> V{自动图化开启?}
        V -->|是| W[graphify]
        W --> X[对度数大于2的非端点节点\n分裂路段使其成为端点]
        V -->|否| Y[跳过]
    end

    subgraph 版本控制
        Z[实体操作] --> Z1[递增 version 计数器]
        Z1 --> Z2[更新 modifiedAt 时间戳]
        Z2 --> Z3[Web 编辑器发送请求\n携带 expectedVersion]
        Z3 --> Z4{版本匹配?}
        Z4 -->|是| Z5[执行操作]
        Z4 -->|否| Z6[返回 409 Conflict\n提示重新加载]
    end

    subgraph 冲突解决
        AA[Web 端收到 409] --> AB[显示冲突提示]
        AB --> AC[接受游戏版本\n使用服务器数据覆盖]
        AB --> AD[重试\n重新获取最新版本号]
        AC --> AE[重新加载数据]
        AD --> AE
    end
```

---

## 四、数据模型关系

```mermaid
erDiagram
    ROAD ||--o{ SEGMENT : contains
    SEGMENT ||--o{ NODE : references
    ROAD {
        UUID id
        String name
        String classification
        String number
        String color
        int version
        long modifiedAt
    }
    SEGMENT {
        UUID id
        list nodeIds
        UUID roadId
        Direction direction
        Source source
        int version
        long modifiedAt
    }
    NODE {
        UUID id
        double x
        double y
        double z
        Source source
        int version
        long modifiedAt
    }
```

---

## 五、系统架构

```mermaid
flowchart LR
    subgraph Minecraft 游戏内
        direction TB
        MC1[Survey 勘测模式] --> MC2[SurveySession 状态机]
        MC2 --> MC3[RoadRecordingManager\n自动录制管线]
        MC2 --> MC4[RoadListScreen\n道路列表管理]
        MC2 --> MC5[RoadMetadataScreen\n道路元数据编辑]
        MC6[XaeroMapOverlay\n地图渲染]
        MC7[SurveyHUD\n双栏状态栏]
        MC8[NodeIndicatorRenderer\n节点光柱]
        MC9[WayfarerHttpServer\n内嵌 HTTP 服务]
    end

    subgraph 数据层
        direction TB
        DB1[RoadNetworkDatabase\n单例数据库]
        DB2[roads.json\n按世界独立存储]
        DB3[WayfarerConfigs\nMalilib 配置]
        DB4[WayfarerConfig\n运行时配置]
        MC2 --> DB1
        MC3 --> DB1
        MC4 --> DB1
        MC5 --> DB1
        MC6 --> DB1
        MC9 --> DB1
        DB1 --> DB2
        DB3 --> DB4
        DB4 --> MC2
        DB4 --> MC3
        DB4 --> MC9
    end

    subgraph Web 前端
        direction TB
        WE1[Leaflet 地图] --> WE2[渲染线段/节点/标签]
        WE2 --> WE3[工具栏操作]
        WE3 --> WE4[Undo/Redo]
        WE4 --> WE5[冲突处理]
    end

    MC9 -->|REST API| WE1
    WE1 -->|HTTP 请求| MC9
```

---

## 六、版本差异对比

| 功能模块 | mainProject (最新版) | versions/26.2 | versions/26.1.1 | versions/1.20.1 |
|---------|---------------------|---------------|-----------------|-----------------|
| **SurveySession 状态机** | ✓ 完整实现 | ✗ | ✗ | ✗ |
| **RoadRecordingManager** | ✓ 完整实现 | ✓ 部分实现 | ✓ 部分实现 | ✗ |
| **RoadListScreen** | ✓ 三栏布局+拖拽+横向滚动 | ✓ 基础版 | ✗ | ✗ |
| **RoadMetadataScreen** | ✓ CREATE/EDIT 双模式 | ✓ 基础版 | ✗ | ✗ |
| **HTTP 服务器** | ✓ 完整实现 | ✗ | ✗ | ✗ |
| **Web 前端编辑器** | ✓ Leaflet 全功能 | ✗ | ✗ | ✗ |
| **Delta 增量同步** | ✓ 每秒更新 | ✗ | ✗ | ✗ |
| **版本冲突检测** | ✓ 409 响应+解决 | ✗ | ✗ | ✗ |
| **Soft Delete** | ✓ 度数感知软删除 | ✗ | ✗ | ✗ |
| **Graphify 自动图化** | ✓ 分裂非端点节点 | ✗ | ✗ | ✗ |
| **自动吸附** | ✓ 节点+折线吸附 | 部分 | 部分 | ✗ |
| **方向系统** | ✓ 三种方向+下拉选择 | 部分 | ✗ | ✗ |
| **道路分类样式** | ✓ G/S/X/Y/C 可配置 | ✗ | ✗ | ✗ |
| **Xaero 地图渲染** | ✓ 分类颜色/线宽 | ✓ 基础版 | ✓ 基础版 | ✗ |
| **分类徽章** | ✓ 彩色/白色双样式 | ✗ | ✗ | ✗ |
| **Undo/Redo** | ✓ 基于快照回滚 | ✗ | ✗ | ✗ |
| **多世界存储** | ✓ 按世界独立 JSON | ✗ | ✗ | ✗ |
| **配置系统** | ✓ Malilib 集成 | ✓ 基础版 | ✗ | ✗ |
| **节点指示光柱** | ✓ 可配置 | ✓ 基础版 | ✗ | ✗ |

---

## 七、配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|--------|------|
| autoIntegral | Boolean | true | 坐标自动取整到整数 |
| autoSnapEndpoints | Boolean | true | 自动吸附首尾端点 |
| rdpEpsilon | Double | 1.0 | RDP 简化容差（格数） |
| autoDeleteOrphanNodes | Boolean | true | 自动删除孤立节点 |
| autoGraphify | Boolean | true | 自动图化路段网络 |
| webMaxZoom | Integer | 10 | 网页地图最大缩放等级 |
| toolItem | String | minecraft:wheat_seeds | Survey 工具物品 |
| showKeyHints | Boolean | true | 按键提示开关 |
| gColor / gWidth | String / Double | FFC000 / 6.0 | 国道颜色和线宽 |
| sColor / sWidth | String / Double | FFD700 / 4.5 | 省道颜色和线宽 |
| xColor / xWidth | String / Double | FFFFFF / 3.5 | 乡道颜色和线宽 |
| yColor / yWidth | String / Double | FFFFFF / 3.0 | 县道颜色和线宽 |
| cColor / cWidth | String / Double | 888888 / 3.0 | 村道颜色和线宽 |
