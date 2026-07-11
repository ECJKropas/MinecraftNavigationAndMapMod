package com.cjkim.mcnav.client.road;

import com.cjkim.mcnav.client.road.model.RoadPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            server.start();
            LOGGER.info("MC Nav preview server started on http://localhost:" + PORT + "/ and http://127.0.0.1:" + PORT + "/");
        } catch (IOException exception) {
            server = null;
            LOGGER.log(Level.WARNING, "Failed to start preview server on port " + PORT, exception);
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

    private void sendText(HttpExchange exchange, int statusCode, String content, String contentType) throws IOException {
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
                    :root { color-scheme: dark; }
                    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; background: #0f1115; color: #e6e6e6; }
                    header { padding: 14px 20px; border-bottom: 1px solid #2a2f3a; display: flex; gap: 14px; align-items: center; flex-wrap: wrap; background: #131720; }
                    header strong { font-size: 18px; }
                    header .meta { color: #9ca6b5; font-size: 13px; }
                    main { display: grid; grid-template-columns: 1fr 320px; height: calc(100vh - 65px); }
                    #canvasWrap { position: relative; min-width: 0; }
                    canvas { width: 100%%; height: 100%%; display: block; background: #11151d; }
                    aside { border-left: 1px solid #2a2f3a; padding: 16px; overflow: auto; }
                    .hint { color: #99a3b3; font-size: 13px; }
                    .chip { display: inline-block; padding: 3px 8px; border-radius: 999px; background: #202635; margin-right: 6px; margin-bottom: 6px; font-size: 12px; }
                    ul { padding-left: 18px; }
                    .road { margin: 0 0 14px 0; }
                    .road h3 { margin: 0 0 4px 0; font-size: 15px; }
                    .road p { margin: 2px 0; color: #b7c0cf; font-size: 13px; }
                    a { color: #7db7ff; }
                    button { background: #1f2430; color: #e6e6e6; border: 1px solid #343b4c; border-radius: 6px; padding: 7px 10px; cursor: pointer; }
                    button:hover { background: #283041; }
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
                  <script>window.__mcnavContextLabel = %s; window.__mcnavDataFile = %s; window.__roads = %s;</script>
                  <script>
                    const canvas = document.getElementById('map');
                    const ctx = canvas.getContext('2d');
                    const stats = document.getElementById('stats');
                    const roadsDiv = document.getElementById('roads');
                    const palette = ['#66d9ef','#a6e22e','#fd971f','#f92672','#ae81ff','#e6db74','#6fcf97','#56ccf2'];

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
                      ctx.fillStyle = '#11151d';
                      ctx.fillRect(0, 0, width, height);

                      if (!roads.length) {
                        ctx.fillStyle = '#b7c0cf';
                        ctx.font = '16px sans-serif';
                        ctx.fillText('暂无道路数据。请在当前实例里按 R 记录一条道路。', 30, 40);
                        stats.textContent = `0 条道路 · 当前实例：${window.__mcnavContextLabel}`;
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

                        const color = palette[index %% palette.length];
                        ctx.strokeStyle = color;
                        ctx.lineWidth = Math.max(2, (road.width || 7) * scale / 3);
                        ctx.beginPath();
                        ctx.moveTo(tx(points[0].x), ty(points[0].z));
                        for (let i = 1; i < points.length; i++) {
                          ctx.lineTo(tx(points[i].x), ty(points[i].z));
                        }
                        ctx.stroke();

                        ctx.fillStyle = color;
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
                """.formatted(contextLabel, dataFile, contextLabelLiteral, dataFileLiteral, roadJson, roadCards);
    }

    private String escapeHtml(String text) {
        return String.valueOf(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String jsStringLiteral(String text) {
        return "\"" + String.valueOf(text)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
