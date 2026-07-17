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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class RoadPreviewServer {
    private static final Logger LOGGER = Logger.getLogger("MC Nav Preview");
    private static final int PORT = 7891;

    private final RoadDataStore roadDataStore;
    private HttpServer server;

    public RoadPreviewServer(RoadDataStore roadDataStore) {
        this.roadDataStore = roadDataStore;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", this::handleRoot);
            server.createContext("/api/roads", this::handleRoads);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MC Nav Preview");
                thread.setDaemon(true);
                return thread;
            }));
            Thread startThread = new Thread(() -> {
                server.start();
                LOGGER.info("MC Nav preview server started on http://localhost:" + PORT + "/ and http://127.0.0.1:"
                    + PORT + "/");
            }, "MC Nav Preview Starter");
            startThread.setDaemon(true);
            startThread.start();
        } catch (IOException exception) {
            server = null;
            LOGGER.log(Level.WARNING, "Failed to start preview server on port " + PORT, exception);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.info("MC Nav preview server stopped");
        }
    }

    public String getUrl() {
        return "http://localhost:" + PORT + "/";
    }

    public String getFallbackUrl() {
        return "http://127.0.0.1:" + PORT + "/";
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        try {
            sendText(exchange, 200, createHtmlPage(), "text/html; charset=utf-8");
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Failed to render preview page", exception);
            sendText(exchange, 500, "Preview page error: " + exception.getMessage(), "text/plain; charset=utf-8");
        }
    }

    private void handleRoads(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        roadDataStore.reloadFromDisk();
        sendText(exchange, 200, roadDataStore.toJson(), "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int statusCode, String content, String contentType)
        throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String createHtmlPage() {
        roadDataStore.reloadFromDisk();
        String roadJson = roadDataStore.toJson();
        String contextLabel = escapeHtml(roadDataStore.getContextLabel());
        String contextLabelLiteral = jsStringLiteral(roadDataStore.getContextLabel());
        String dataFile = escapeHtml(String.valueOf(roadDataStore.getDataFile()));
        String dataFileLiteral = jsStringLiteral(String.valueOf(roadDataStore.getDataFile()));
        StringBuilder roadCardsBuilder = new StringBuilder();
        for (RoadPath road : roadDataStore.getRoads()) {
            String roadName = road.name == null || road.name.isBlank() ? "未命名道路" : road.name;
            int pointCount = road.points == null ? 0 : road.points.size();
            int intersectionCount = road.intersections == null ? 0 : road.intersections.size();
            roadCardsBuilder.append("""
                <div class="road">
                  <h3>%s</h3>
                  <p>宽度：%s 格</p>
                  <p>轨迹点：%d</p>
                  <p>交叉点：%d</p>
                </div>
                """.formatted(escapeHtml(roadName), road.width, pointCount, intersectionCount));
        }
        String roadCards = roadCardsBuilder.toString();
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>MC Nav Preview</title>
              <style>
                :root { color-scheme: light; }
                body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; background: #f8f9fa; color: #1a1a2e; }
                header { padding: 14px 20px; border-bottom: 1px solid #dee2e6; display: flex; gap: 14px; align-items: center; flex-wrap: wrap; background: #fff; }
                header strong { font-size: 18px; color: #16213e; }
                header .meta { color: #495057; font-size: 13px; }
                main { display: grid; grid-template-columns: 1fr 320px; height: calc(100vh - 65px); }
                #canvasWrap { position: relative; min-width: 0; }
                canvas { width: 100%%; height: 100%%; display: block; background: #ffffff; }
                aside { border-left: 1px solid #dee2e6; padding: 16px; overflow: auto; background: #fff; }
                .hint { color: #6c757d; font-size: 13px; }
                .chip { display: inline-block; padding: 3px 8px; border-radius: 999px; background: #e9ecef; margin-right: 6px; margin-bottom: 6px; font-size: 12px; color: #0d6efd; }
                ul { padding-left: 18px; }
                .road { margin: 0 0 14px 0; }
                .road h3 { margin: 0 0 4px 0; font-size: 15px; color: #16213e; }
                .road p { margin: 2px 0; color: #6c757d; font-size: 13px; }
                a { color: #0d6efd; }
                button { background: #0d6efd; color: #fff; border: 1px solid #0b5ed7; border-radius: 6px; padding: 7px 10px; cursor: pointer; }
                button:hover { background: #0b5ed7; }
              </style>
            </head>
            <body>
              <header>
                <strong>MC Nav 网页预览</strong>
                <span class="meta">当前实例：%s</span>
                <span class="meta">数据文件：%s</span>
                <span class="hint">地址：<a href="http://localhost:7891/" target="_blank" rel="noreferrer">http://localhost:7891/</a> · <a href="http://127.0.0.1:7891/" target="_blank" rel="noreferrer">127.0.0.1</a></span>
                <button id="refresh">刷新</button>
              </header>
              <main>
                <div id="canvasWrap"><canvas id="map"></canvas></div>
                <aside>
                  <div id="stats" class="hint">载入中…</div>
                  <div id="roads">%s</div>
                </aside>
              </main>
              <script>window.__wayfarerContextLabel = %s; window.__wayfarerDataFile = %s; window.__roads = %s;</script>
              <script>
                const canvas = document.getElementById('map');
                const ctx = canvas.getContext('2d');
                const stats = document.getElementById('stats');
                const roadsDiv = document.getElementById('roads');
                // --- Road styling by classification ---
                function getRoadStyle(classification) {
                  if (!classification) return { stroke: '#adb5bd', fill: '#ffffff', edgeWidth: 3, bodyWidth: 1, textColor: '#6c757d', labelBg: '#ffffff', labelColor: '#495057', labelBorder: '#adb5bd' };
                  const cls = classification.charAt(0).toUpperCase();
                  if (cls === 'G') return { stroke: '#D9432B', fill: '#D9432B', edgeWidth: 0, bodyWidth: 1, textColor: '#ffffff', labelBg: '#D9432B', labelColor: '#ffffff', labelBorder: '#ffffff' };
                  if (cls === 'S') return { stroke: '#F0A030', fill: '#F0A030', edgeWidth: 0, bodyWidth: 1, textColor: '#000000', labelBg: '#FFE066', labelColor: '#000000', labelBorder: '#F0A030' };
                  return { stroke: '#adb5bd', fill: '#ffffff', edgeWidth: 3, bodyWidth: 1, textColor: '#6c757d', labelBg: '#ffffff', labelColor: '#495057', labelBorder: '#adb5bd' };
                }

                function shouldDimName(road) {
                  if (!road.classification || !road.name) return false;
                  const cls = road.classification.charAt(0).toUpperCase();
                  return cls === 'G' && /^[Gg]\\d+$/.test(road.name.trim());
                }

                function getDisplayName(road) {
                  return road.name || road.number || '';
                }

                function resizeCanvas() {
                  const rect = canvas.getBoundingClientRect();
                  const scale = window.devicePixelRatio || 1;
                  canvas.width = Math.max(1, Math.floor(rect.width * scale));
                  canvas.height = Math.max(1, Math.floor(rect.height * scale));
                  render(window.__roads || []);
                }

                function boundsOf(roads) {
                  const points = roads.flatMap(r => r.points || []);
                  if (!points.length) return null;
                  let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
                  for (const point of points) {
                    minX = Math.min(minX, point.x);
                    minZ = Math.min(minZ, point.z);
                    maxX = Math.max(maxX, point.x);
                    maxZ = Math.max(maxZ, point.z);
                  }
                  return { minX, minZ, maxX, maxZ };
                }

                function render(roads) {
                  const width = canvas.width;
                  const height = canvas.height;
                  ctx.clearRect(0, 0, width, height);
                  ctx.fillStyle = '#ffffff';
                  ctx.fillRect(0, 0, width, height);

                  if (!roads.length) {
                    ctx.fillStyle = '#495057';
                    ctx.font = '16px sans-serif';
                    ctx.fillText('暂无道路数据。请在当前实例里按 R 记录一条道路。', 30, 40);
                    stats.textContent = `0 条道路 · 当前实例：${window.__wayfarerContextLabel}`;
                    roadsDiv.innerHTML = '<p class="hint">当前实例还没有保存的道路。</p>';
                    return;
                  }

                  const bounds = boundsOf(roads);
                  const pad = 40 * (window.devicePixelRatio || 1);
                  const dx = Math.max(1, bounds.maxX - bounds.minX);
                  const dz = Math.max(1, bounds.maxZ - bounds.minZ);
                  const scale = Math.min((width - pad * 2) / dx, (height - pad * 2) / dz);
                  const centerX = (bounds.minX + bounds.maxX) / 2;
                  const centerZ = (bounds.minZ + bounds.maxZ) / 2;

                  function tx(x) { return width / 2 + (x - centerX) * scale; }
                  function ty(z) { return height / 2 - (z - centerZ) * scale; }

                  ctx.lineCap = 'round';
                  ctx.lineJoin = 'round';

                  roads.forEach((road, index) => {
                    const points = road.points || [];
                    if (points.length < 2) return;

                    const style = getRoadStyle(road.classification);
                    const roadWidth = Math.max(2, (road.width || 7) * scale / 3);

                    // Draw road body
                    if (style.edgeWidth > 0) {
                      // X/Y/C/普通: grey edge + white fill
                      ctx.strokeStyle = '#adb5bd';
                      ctx.lineWidth = roadWidth + style.edgeWidth;
                      ctx.beginPath();
                      ctx.moveTo(tx(points[0].x), ty(points[0].z));
                      for (let i = 1; i < points.length; i++) {
                        ctx.lineTo(tx(points[i].x), ty(points[i].z));
                      }
                      ctx.stroke();
                      ctx.strokeStyle = '#ffffff';
                      ctx.lineWidth = roadWidth;
                      ctx.beginPath();
                      ctx.moveTo(tx(points[0].x), ty(points[0].z));
                      for (let i = 1; i < points.length; i++) {
                        ctx.lineTo(tx(points[i].x), ty(points[i].z));
                      }
                      ctx.stroke();
                    } else {
                      // G/S roads: solid thick line
                      ctx.strokeStyle = style.fill;
                      ctx.lineWidth = roadWidth * 1.8;
                      ctx.beginPath();
                      ctx.moveTo(tx(points[0].x), ty(points[0].z));
                      for (let i = 1; i < points.length; i++) {
                        ctx.lineTo(tx(points[i].x), ty(points[i].z));
                      }
                      ctx.stroke();
                    }

                    // Draw road name label at midpoint
                    const displayName = getDisplayName(road);
                    if (displayName) {
                      const midIdx = Math.floor(points.length / 2);
                      const midX = tx(points[midIdx].x);
                      const midY = ty(points[midIdx].z);
                      const fontSize = Math.max(10, Math.min(16, roadWidth * 1.2));
                      ctx.font = `${fontSize}px system-ui, sans-serif`;
                      const metrics = ctx.measureText(displayName);
                      const textW = metrics.width;
                      const textH = fontSize;

                      if (shouldDimName(road)) {
                        // Gxxx format: dim grey text on road center
                        ctx.fillStyle = 'rgba(180,180,180,0.55)';
                        ctx.fillText(displayName, midX - textW / 2, midY + textH / 3);
                      } else {
                        // Standard label: colored pill with border
                        const padX = 6;
                        const padY = 3;
                        const rx = midX - textW / 2 - padX;
                        const ry = midY - textH / 2 - padY;
                        const rw = textW + padX * 2;
                        const rh = textH + padY * 2;
                        const radius = 4;

                        ctx.fillStyle = style.labelBg;
                        ctx.beginPath();
                        ctx.moveTo(rx + radius, ry);
                        ctx.lineTo(rx + rw - radius, ry);
                        ctx.arcTo(rx + rw, ry, rx + rw, ry + radius, radius);
                        ctx.lineTo(rx + rw, ry + rh - radius);
                        ctx.arcTo(rx + rw, ry + rh, rx + rw - radius, ry + rh, radius);
                        ctx.lineTo(rx + radius, ry + rh);
                        ctx.arcTo(rx, ry + rh, rx, ry + rh - radius, radius);
                        ctx.lineTo(rx, ry + radius);
                        ctx.arcTo(rx, ry, rx + radius, ry, radius);
                        ctx.closePath();
                        ctx.fill();

                        ctx.strokeStyle = style.labelBorder;
                        ctx.lineWidth = 1.5;
                        ctx.stroke();

                        ctx.fillStyle = style.labelColor;
                        ctx.fillText(displayName, midX - textW / 2, midY + textH / 3);
                      }
                    }

                    // Start point marker
                    ctx.fillStyle = style.fill;
                    const first = points[0];
                    ctx.beginPath();
                    ctx.arc(tx(first.x), ty(first.z), 4 * (window.devicePixelRatio || 1), 0, Math.PI * 2);
                    ctx.fill();
                  });

                  stats.textContent = `${roads.length} 条道路 · ${roads.reduce((sum, road) => sum + (road.points ? road.points.length : 0), 0)} 个采样点`;
                  roadsDiv.innerHTML = roads.map((road, index) => {
                    const intersections = road.intersections ? road.intersections.length : 0;
                    return `<div class="road"><h3><span class="chip">${index + 1}</span>${escapeHtml(road.name || '未命名道路')}</h3><p>宽度：${road.width ?? 7} 格</p><p>轨迹点：${(road.points || []).length}</p><p>交叉点：${intersections}</p></div>`;
                  }).join('');
                }

                function escapeHtml(text) {
                  return String(text)
                    .replaceAll('&', '&amp;')
                    .replaceAll('<', '&lt;')
                    .replaceAll('>', '&gt;')
                    .replaceAll('"', '&quot;')
                    .replaceAll("'", '&#39;');
                }

                async function load() {
                  try {
                    const response = await fetch('/api/roads', { cache: 'no-store' });
                    window.__roads = await response.json();
                  } catch (error) {
                    console.warn('Failed to refresh roads from API', error);
                  }
                  render(window.__roads || []);
                }

                document.getElementById('refresh').addEventListener('click', load);
                window.addEventListener('resize', resizeCanvas);
                setInterval(load, 3000);
                resizeCanvas();
                load();
              </script>
            </body>
            </html>
            """
            .formatted(contextLabel, dataFile, contextLabelLiteral, dataFileLiteral, roadJson, roadCards);
    }

    private String escapeHtml(String text) {
        return String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String jsStringLiteral(String text) {
        return "\""
            + String.valueOf(text).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
            + "\"";
    }
}
