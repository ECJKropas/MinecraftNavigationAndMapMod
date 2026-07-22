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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Road trajectory simplifier.
 *
 * <p>
 * Two-step post-processing pipeline:
 * <ol>
 * <li><b>Backtrack removal</b>: detect and remove Z-shaped backtrack paths</li>
 * <li><b>Douglas-Peucker simplification</b>: reduce points to key shape vertices</li>
 * </ol>
 *
 * <p>
 * All distance calculations are on the XZ plane (Minecraft horizontal). Y (height) is preserved but does not
 * participate in simplification decisions.
 *
 * <p>
 * Points are represented as {@code double[]} arrays of length 3: {@code [x, y, z]}.
 */
public final class RoadSimplifier {

    private static final Logger LOGGER = Logger.getLogger("Wayfarer");

    private RoadSimplifier() {}

    // ──────────────────────────────────────────────
    // Formula evaluation
    // ──────────────────────────────────────────────

    /**
     * Evaluate an epsilon formula string.
     *
     * <p>
     * Supports placeholders {@code [RW]} (Road Width) and {@code [DW]} (Default Width). Supports basic arithmetic (+ -
     * * /) and parentheses. Falls back to {@code rw / 2.0} on parse failure.
     */
    public static double evaluateFormula(String formula, double rw, double dw) {
        if (formula == null || formula.trim().isEmpty()) {
            return rw / 2.0;
        }
        try {
            String expr = formula.replace("[RW]", Double.toString(rw)).replace("[DW]", Double.toString(dw));
            double result = parseExpression(expr);
            return Math.max(0.0, result);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                "[Wayfarer] Failed to evaluate RDP formula '" + formula + "', falling back to RW/2", e);
            return rw / 2.0;
        }
    }

    // ── Recursive descent parser ──

    private static String expr;
    private static int pos;

    private static double parseExpression(String s) {
        expr = s.replaceAll("\\s+", "");
        pos = 0;
        return parseExpressionInternal();
    }

    private static double parseExpressionInternal() {
        double val = parseTerm();
        while (pos < expr.length()) {
            char op = expr.charAt(pos);
            if (op == '+' || op == '-') {
                pos++;
                double rhs = parseTerm();
                val = (op == '+') ? val + rhs : val - rhs;
            } else {
                break;
            }
        }
        return val;
    }

    private static double parseTerm() {
        double val = parseFactor();
        while (pos < expr.length()) {
            char op = expr.charAt(pos);
            if (op == '*' || op == '/') {
                pos++;
                double rhs = parseFactor();
                val = (op == '*') ? val * rhs : val / rhs;
            } else {
                break;
            }
        }
        return val;
    }

    private static double parseFactor() {
        if (pos >= expr.length()) {
            throw new IllegalArgumentException("Unexpected end of expression");
        }
        char ch = expr.charAt(pos);
        if (ch == '(') {
            pos++;
            double val = parseExpressionInternal();
            if (pos < expr.length() && expr.charAt(pos) == ')') {
                pos++;
            }
            return val;
        }
        int start = pos;
        if (ch == '-')
            pos++;
        while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
            pos++;
        }
        return Double.parseDouble(expr.substring(start, pos));
    }

    // ──────────────────────────────────────────────
    // Public API — uses double[]{x, y, z}
    // ──────────────────────────────────────────────

    /** Accessor helpers to avoid magic indices. */
    public static double x(double[] pt) {
        return pt[0];
    }

    public static double y(double[] pt) {
        return pt[1];
    }

    public static double z(double[] pt) {
        return pt[2];
    }

    /**
     * Remove backtracking walks.
     *
     * <p>
     * Scans trajectory points in temporal order. When a new point falls within the neighbourhood of a historical point
     * (excluding the top of the stack), the walk is considered backtracked: truncates all points after the matched one
     * and discards the current point.
     */
    public static List<double[]> removeBacktracking(List<double[]> points, double threshold) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }

        List<double[]> cleaned = new ArrayList<>();
        cleaned.add(points.get(0));

        for (int i = 1; i < points.size(); i++) {
            double[] curr = points.get(i);
            double[] last = cleaned.get(cleaned.size() - 1);

            // dedup: distance < threshold*0.1 is treated as duplicate sample
            if (distXZ(curr, last) < threshold * 0.1) {
                continue;
            }

            // backtrack detection: scan all historical points except stack top
            int hitIndex = -1;
            for (int j = 0; j < cleaned.size() - 1; j++) {
                if (distXZ(curr, cleaned.get(j)) < threshold) {
                    hitIndex = j;
                    break;
                }
            }

            if (hitIndex >= 0) {
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
     * Douglas-Peucker curve simplification (XZ plane).
     */
    public static List<double[]> douglasPeucker(List<double[]> points, double epsilon) {
        if (points.size() <= 2) {
            return new ArrayList<>(points);
        }
        return dpRecursive(points, 0, points.size() - 1, epsilon);
    }

    /**
     * Full pipeline: backtrack removal → RDP simplification.
     *
     * @param points raw trajectory points
     * @param backtrackThreshold backtrack detection threshold (blocks)
     * @param epsilonFormula epsilon formula string, e.g. {@code "[RW]/2"}
     * @param rw road width
     * @param dw default width
     * @return simplified key-point list
     */
    public static List<double[]> simplify(List<double[]> points, double backtrackThreshold, String epsilonFormula,
        double rw, double dw) {
        double epsilon = evaluateFormula(epsilonFormula, rw, dw);
        List<double[]> cleaned = removeBacktracking(points, backtrackThreshold);
        return douglasPeucker(cleaned, epsilon);
    }

    // ──────────────────────────────────────────────
    // Internal implementation
    // ──────────────────────────────────────────────

    private static List<double[]> dpRecursive(List<double[]> points, int start, int end, double epsilon) {
        if (end - start <= 1) {
            List<double[]> result = new ArrayList<>();
            result.add(points.get(start));
            if (end > start) {
                result.add(points.get(end));
            }
            return result;
        }

        double[] startPt = points.get(start);
        double[] endPt = points.get(end);

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
            List<double[]> left = dpRecursive(points, start, idx, epsilon);
            List<double[]> right = dpRecursive(points, idx, end, epsilon);
            left.remove(left.size() - 1);
            left.addAll(right);
            return left;
        } else {
            List<double[]> result = new ArrayList<>();
            result.add(startPt);
            result.add(endPt);
            return result;
        }
    }

    private static double perpendicularDistanceXZ(double[] point, double[] lineStart, double[] lineEnd) {
        double dx = lineEnd[0] - lineStart[0];
        double dz = lineEnd[2] - lineStart[2];
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0.0) {
            return distXZ(point, lineStart);
        }

        double t = ((point[0] - lineStart[0]) * dx + (point[2] - lineStart[2]) * dz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double projX = lineStart[0] + t * dx;
        double projZ = lineStart[2] + t * dz;
        double pdx = point[0] - projX;
        double pdz = point[2] - projZ;
        return Math.sqrt(pdx * pdx + pdz * pdz);
    }

    private static double distXZ(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dz * dz);
    }
}
