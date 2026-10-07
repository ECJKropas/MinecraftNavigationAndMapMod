# Navigation Context（导航上下文）

Wayfarer 在已有路网（Node / Segment / Road 图）之上提供"现实导航式"路线规划与引导。本文档仅定义术语，不含实现细节。

## 路线与图

**Route（路线）**：从起点到终点的有序节点 / 路段序列，由 Router 在一张路网图上计算得出。

**Edge（图边）**：路网图的真实连通单元，即某条 Segment 内相邻两个 Node 之间的连接；其代价由所在 Road 的等级速度决定。

**Road Classification（道路等级）**：Road 的分类，取值 `G`(国道) / `S`(省道) / `Y`(县道) / `X`(乡道) / `C`(村道)，未归类为空串。决定该路段在路由中的代价与 ETA。

## 导航会话

**Navigation（导航）**：选定目的地后，在路网图上计算并持续引导玩家从当前位置前往目的地的功能。

**Start（起点）**：导航开始时的玩家位置，自动吸附到 navSnapRadius 内最近 Node。

**Destination（目的地 / 终点）**：导航目标点，可由浏览器地图点选或坐标输入给出，自动吸附到 navSnapRadius 内最近 Node。

**Snap Radius（吸附半径 / navSnapRadius）**：起点或终点离路网超过此半径（建议 32 格）时，视为"附近无道路"，拒绝导航。

**Arrival Radius（到达半径 / navArrivalRadius）**：玩家进入终点此半径（建议 5 格）内即判定到达，自动结束导航。

## 路由代价

**Class Speed（等级速度 / navSpeed[G|S|Y|X|C]）**：每个道路等级假想的步行速度（格/秒）。路网本无真实速度差，用作代价与 ETA 的建模：段代价 = 段长度 ÷ 等级速度。

**Distance Gate（距离门控 / navDistanceGate）**：起终点直线距离阈值（默认 200 格）。低于阈值时弱化等级偏好（接近纯距离、走小路）；高于阈值时启用强等级偏好（长途走 G/S 大路）。

**Auto-Reroute（自动重规划 / navRerouteThreshold）**：玩家偏离当前路线超过此阈值（建议 8 格）时，从当前位置重新计算路线。

## 展示

**HUD**：游戏内叠加层，显示剩余总距离、距下个路口距离与转向箭头、当前道路等级/名称、预计总耗时（ETA）。不在 3D 世界中绘制路线。

_Avoid_：路径（path）、轨迹（track，指记录阶段采样点）、waypoint（路点，v1 未做命名收藏）。
