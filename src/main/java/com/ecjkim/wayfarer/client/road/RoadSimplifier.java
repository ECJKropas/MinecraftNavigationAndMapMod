/*
 * Copyright (C) 2025  MinecraftNavigationAndMapMod contributors
 * https://github.com/ECJKropas/MinecraftNavigationAndMapMod

 * MinecraftNavigationAndMapMod is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.

 * MinecraftNavigationAndMapMod is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with MinecraftNavigationAndMapMod.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.ecjkim.wayfarer.client.road;

import java.util.ArrayList;
import java.util.List;

import com.ecjkim.wayfarer.client.road.model.RoadPoint;

/**
 * 道路轨迹简化工具。
 *
 * <p>
 * 提供两步后处理流水线：
 * <ol>
 * <li><b>回退路径剪枝</b>：检测并移除物理采集中的"Z字形回退"路径</li>
 * <li><b>Douglas-Peucker 简化</b>：用最少的关键点表示道路折线形状</li>
 * </ol>
 *
 * <p>
 * 所有距离计算在 XZ 平面（Minecraft 水平面）上进行，Y 轴（高度）不参与简化判定， 但保留原始 RoadPoint 的 y 和 tick 字段不变。
 */
public final class RoadSimplifier {

    private RoadSimplifier() {}

    // ──────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────

    /**
     * 去除回退路径。
     *
     * <p>
     * 按时间顺序扫描轨迹点。当新点落入历史旧点（除栈顶外）的邻域内时， 认定发生了物理回退：截断该旧点之后的所有点（舍弃第一次前进路径）， 并丢弃当前回退点，等待后续真正前进的点到来。
     *
     * <p>
     * 示例：A(0,0) → B(1,1) → C(2,0) → D(0.1,0.1)（D 回访到 A 附近） → 输出 A(0,0)，B(1,1) 和 C(2,0) 被裁剪，D 也不保留。
     *
     * @param points 原始轨迹点（按 tick 时间排序）
     * @param threshold 判定"回访到旧点"的距离阈值（XZ 平面，单位：格）
     * @return 清洗后的轨迹点列表
     */
    public static List<RoadPoint> removeBacktracking(List<RoadPoint> points, double threshold) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }

        List<RoadPoint> cleaned = new ArrayList<>();
        cleaned.add(points.get(0));

        for (int i = 1; i < points.size(); i++) {
            RoadPoint curr = points.get(i);
            RoadPoint last = cleaned.get(cleaned.size() - 1);

            // 去重：与栈顶距离 < threshold*0.1 视为重复采样，直接跳过
            if (distXZ(curr, last) < threshold * 0.1) {
                continue;
            }

            // 回溯检测：扫描 cleaned 中除栈顶外的所有历史点
            int hitIndex = -1;
            for (int j = 0; j < cleaned.size() - 1; j++) {
                if (distXZ(curr, cleaned.get(j)) < threshold) {
                    hitIndex = j;
                    break;
                }
            }

            if (hitIndex >= 0) {
                // 命中旧点 → 截断 hitIndex 之后的所有点，不添加 curr
                while (cleaned.size() > hitIndex + 1) {
                    cleaned.remove(cleaned.size() - 1);
                }
            } else {
                cleaned.add(curr);
            }
        }

        return cleaned;
    }

    /**
     * Douglas-Peucker 曲线简化（XZ 平面）。
     *
     * <p>
     * 递归剔除对整体形状贡献小的点，输出最少的"拐点"集合。 epsilon 越大保留点越少。
     *
     * @param points 清洗后的轨迹点
     * @param epsilon 最大允许偏差（XZ 平面，单位：格）
     * @return 简化后的关键点列表（至少包含首尾两点）
     */
    public static List<RoadPoint> douglasPeucker(List<RoadPoint> points, double epsilon) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }
        return dpRecursive(points, 0, points.size() - 1, epsilon);
    }

    /**
     * 完整流水线：回退去除 → RDP 简化。
     *
     * @param points 原始轨迹点
     * @param backtrackThreshold 回退检测阈值（格）
     * @param rdpEpsilon RDP 简化容差（格）
     * @return 简化后的关键点列表
     */
    public static List<RoadPoint> simplify(List<RoadPoint> points, double backtrackThreshold, double rdpEpsilon) {
        List<RoadPoint> cleaned = removeBacktracking(points, backtrackThreshold);
        return douglasPeucker(cleaned, rdpEpsilon);
    }

    // ──────────────────────────────────────────────
    // 内部实现
    // ──────────────────────────────────────────────

    private static List<RoadPoint> dpRecursive(List<RoadPoint> points, int start, int end, double epsilon) {
        if (end - start <= 1) {
            List<RoadPoint> result = new ArrayList<>();
            result.add(points.get(start));
            if (end > start) {
                result.add(points.get(end));
            }
            return result;
        }

        RoadPoint startPt = points.get(start);
        RoadPoint endPt = points.get(end);

        double dmax = 0.0;
        int idx = start;

        for (int i = start + 1; i < end; i++) {
            double d = perpendicularDistanceXZ(points.get(i), startPt, endPt);
            if (d > dmax) {
                dmax = d;
                idx = i;
            }
        }

        if (dmax > epsilon) {
            List<RoadPoint> left = dpRecursive(points, start, idx, epsilon);
            List<RoadPoint> right = dpRecursive(points, idx, end, epsilon);
            // 合并：去掉左段尾点（与右段首点重复）
            left.remove(left.size() - 1);
            left.addAll(right);
            return left;
        } else {
            List<RoadPoint> result = new ArrayList<>();
            result.add(startPt);
            result.add(endPt);
            return result;
        }
    }

    /**
     * 计算点到线段（XZ 平面投影）的垂直距离。
     */
    private static double perpendicularDistanceXZ(RoadPoint point, RoadPoint lineStart, RoadPoint lineEnd) {
        double dx = lineEnd.x - lineStart.x;
        double dz = lineEnd.z - lineStart.z;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0.0) {
            return distXZ(point, lineStart);
        }

        double t = ((point.x - lineStart.x) * dx + (point.z - lineStart.z) * dz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double projX = lineStart.x + t * dx;
        double projZ = lineStart.z + t * dz;
        double pdx = point.x - projX;
        double pdz = point.z - projZ;
        return Math.sqrt(pdx * pdx + pdz * pdz);
    }

    private static double distXZ(RoadPoint a, RoadPoint b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
