# 道路轨迹简化算法实现计划

> 日期：2026-07-22 | 状态：待审核

## 目标

在道路录制完成后，对原始轨迹点做两步后处理：
1. **回退路径剪枝**：去除物理采集中的"Z字形回退"（如 A→B→C→D，若 C 与 A 接近，舍去 B→C，保留 A→D）
2. **RDP 曲线简化**：用最少的关键点表示道路形状，保持折线，不做平滑插值

## 新增文件

```
src/main/java/com/ecjkim/wayfarer/client/road/RoadSimplifier.java
```

## 设计决策

| 决策 | 理由 |
|---|---|
| 静态工具类 | 无状态，纯函数式，可被多处复用 |
| 操作 (x, z) 平面 | Minecraft 水平面是 XZ，Y 轴是高度，道路在水平面上投影 |
| 保留原始 RoadPoint（含 y/tick） | 简化后点仍保留原始的 y 和 tick，确保数据不丢失 |
| threshold 默认 = 采样间距 × 3 | 与 RoadRecordingManager 的 0.5 格采样间距配合，默认 1.5 |
| epsilon 默认 = 1.0（格） | 与 Minecraft 格对齐，保留形状关键拐点 |
| 保持折线 | 不做平滑插值，输出即为存储的关键点集 |
| epsilon 可配置 | 纳入 WayfarerConfig / WayfarerConfigs，用户可在设置中调整 |

## API 设计

```java
public final class RoadSimplifier {

    /**
     * 去除回退路径。按时间顺序扫描，当新点落入历史旧点（非最近点）的邻域内时，
     * 截断该旧点之后的所有点，丢弃当前回退点。
     * @param points 原始轨迹点（按 tick 排序）
     * @param threshold 判定"回访"的距离阈值（x-z 平面欧氏距离）
     * @return 清洗后的轨迹点列表
     */
    public static List<RoadPoint> removeBacktracking(List<RoadPoint> points, double threshold);

    /**
     * Douglas-Peucker 曲线简化。
     * @param points 清洗后的轨迹点
     * @param epsilon 最大允许偏差
     * @return 简化后的关键点列表
     */
    public static List<RoadPoint> douglasPeucker(List<RoadPoint> points, double epsilon);

    /**
     * 完整流水线：回退去除 → RDP 简化。
     * @param points 原始轨迹点
     * @param backtrackThreshold 回退检测阈值
     * @param rdpEpsilon RDP 简化容差
     * @return 简化后的关键点列表
     */
    public static List<RoadPoint> simplify(List<RoadPoint> points, double backtrackThreshold, double rdpEpsilon);
}
```

## 集成方案

在 `RoadRecordingManager` 录制结束、用户确认保存元数据后，在 `RoadDataStore.addRoad()` 之前调用：

```java
// RoadRecordingManager 中的伪代码
List<RoadPoint> rawPoints = getRecordedPoints();
double epsilon = WayfarerConfig.getRdpEpsilon(); // 从配置读取
List<RoadPoint> simplified = RoadSimplifier.simplify(rawPoints, 1.5, epsilon);
roadPath.setPoints(simplified);
```

不在 `RoadDataStore.addRoad()` 内部自动简化，保持数据层的纯粹性——录制层决定是否简化。

## 算法细节

### 1. removeBacktracking

```
输入: [(0,0), (1,1), (2,0), (0.1,0.1), (3,1)], threshold=0.3
结果: [(0,0), (0.1,0.1), (3,1)]  // (1,1)和(2,0)被(0.1,0.1)回退命中截断

算法：
- 维护 cleaned 列表
- 遍历每个新点 curr：
  1. 若 curr 与 cleaned[-1] 距离 < threshold*0.1 → 去重跳过
  2. 扫描 cleaned[:-1]（不检查最后一个，避免误判正常前进）
  3. 若 curr 命中某历史点 cleaned[j] → cleaned = cleaned[:j+1]，跳过 curr
  4. 否则 cleaned.append(curr)
- 输出 cleaned
```

### 2. douglasPeucker

标准递归 RDP：
- 首尾连线，找最大偏差点
- 若偏差 > epsilon → 递归处理左右两段
- 否则 → 只保留首尾两点

### 3. （无）

## 影响范围

- **新增**：`RoadSimplifier.java`（~150 行）
- **修改**：`RoadRecordingManager.java`（1 处调用，约 5 行）
- **配置**：`WayfarerConfig` / `WayfarerConfigs` 增加 RDP epsilon 配置项
- **无影响**：数据模型、存储层、UI、HTTP 服务、Xaero 叠加均无需改动

## 后续扩展考虑

- epsilon 已在配置中暴露，用户可调
- 未来若需要可视化平滑（预览地图、Leaflet 渲染），可增加 Catmull-Rom 插值作为独立工具方法
