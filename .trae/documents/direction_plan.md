# 方向类型功能实施计划

## 概述
根据用户需求，需要：
1. 删除路段的 DRAFT/CONFIRMED 状态（Status 枚举）
2. 删除节点的 SHARP/ROUND 拐角类型（CornerType 枚举）
3. 新增方向类型功能：区分双向路和单向路（起点→终点 / 终点→起点）

## 新方向枚举设计
```java
public enum Direction {
    BIDIRECTIONAL,  // 双向
    FORWARD,        // 起点→终点
    BACKWARD        // 终点→起点
}
```

## 修改文件列表

### 1. 模型层
| 文件 | 操作 | 说明 |
|------|------|------|
| `model/Status.java` | 删除 | 不再需要 |
| `model/CornerType.java` | 删除 | 不再需要 |
| `model/Direction.java` | 新建 | 新增方向枚举 |
| `model/Segment.java` | 修改 | 删除 status 字段，新增 direction 字段（默认 BIDIRECTIONAL） |
| `model/Node.java` | 修改 | 删除 cornerType 字段及相关 getter/setter |

### 2. 业务逻辑层
| 文件 | 操作 | 说明 |
|------|------|------|
| `road/record/SurveySession.java` | 修改 | 删除 CornerType 相关逻辑，改为 Direction 切换 |
| `road/RoadRecordingManager.java` | 修改 | 删除 CornerType、Status 引用，使用 Direction |
| `data/RoadNetworkDatabase.java` | 修改 | 删除 CornerType、Status 引用，使用 Direction |
| `road/server/WayfarerHttpServer.java` | 修改 | 删除 Status 引用，使用 Direction |

### 3. UI 层
| 文件 | 操作 | 说明 |
|------|------|------|
| `render/SurveyHud.java` | 修改 | 将拐角类型显示改为方向类型显示 |
| `road/RoadListScreen.java` | 修改 | 删除 CornerType/Status 显示，改为 Direction |
| `road/RoadMetadataScreen.java` | 修改 | 可能需要更新（如果显示状态） |
| `mixin/MouseScrollMixin.java` | 修改 | 将 Ctrl+滚轮切换从 CornerType 改为 Direction |

### 4. 国际化
| 文件 | 操作 | 说明 |
|------|------|------|
| `lang/zh_cn.yml` | 修改 | 更新翻译文本 |
| `lang/en_us.yml` | 修改 | 更新翻译文本 |

## 详细实施步骤

### 步骤 1：创建 Direction 枚举
在 `model/Direction.java` 中创建新枚举类。

### 步骤 2：修改模型类
- Segment.java：删除 status 字段，添加 direction 字段
- Node.java：删除 cornerType 字段

### 步骤 3：删除旧枚举
- 删除 Status.java
- 删除 CornerType.java

### 步骤 4：更新 SurveySession.java
- 删除 CornerType 字段和相关方法
- 添加 Direction 字段和切换方法
- 修改 onLeftClick/onRightClick 等方法不再设置 cornerType
- 更新 finishRecording 创建 Segment 时使用 Direction

### 步骤 5：更新 SurveyHud.java
- 将 cornerType 显示改为 direction 显示
- 更新图标和颜色

### 步骤 6：更新 MouseScrollMixin.java
- 将 cycleCornerType 改为 cycleDirection
- 更新提示信息

### 步骤 7：更新 RoadListScreen.java
- 删除 CornerType/Status 相关显示
- 添加 Direction 显示

### 步骤 8：更新 RoadNetworkDatabase.java
- 删除 CornerType 引用
- 更新 Segment 创建使用 Direction
- 更新 Node 创建不再需要 CornerType

### 步骤 9：更新其他文件
- RoadRecordingManager.java
- WayfarerHttpServer.java

### 步骤 10：更新国际化
- zh_cn.yml：更新提示文本、方向名称
- en_us.yml：更新提示文本、方向名称

## 风险处理
1. **数据兼容性**：由于 Segment 的 status 字段被删除，现有 roads.json 中的 status 字段在加载时会被忽略（Gson 默认行为），不会导致错误
2. **Node cornerType**：同样，现有数据中的 cornerType 字段会被忽略
3. **旧数据更新**：现有路段的 direction 字段默认为 BIDIRECTIONAL，符合大多数道路的实际情况
4. **测试**：需要测试录制流程、HUD 显示、保存/加载功能

## 注意事项
- 序列化/反序列化由 Gson 处理，字段缺失会自动使用默认值
- Direction 字段默认值为 BIDIRECTIONAL，向后兼容
- 保持现有的代码风格和组织方式
