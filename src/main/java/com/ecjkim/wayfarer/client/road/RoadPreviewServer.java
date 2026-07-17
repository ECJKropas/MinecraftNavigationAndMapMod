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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ecjkim.wayfarer.client.road.layer.LayerManager;
import com.ecjkim.wayfarer.client.road.layer.MapLayer;
import com.ecjkim.wayfarer.client.road.model.RoadPath;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class RoadPreviewServer {
    private static final Logger LOGGER = Logger.getLogger("Wayfarer|Preview");
    private static final int PORT = 7891;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final RoadDataStore roadDataStore;
    private final LayerManager layerManager;
    private HttpServer server;

    public RoadPreviewServer(RoadDataStore roadDataStore) {
        this.roadDataStore = roadDataStore;
        this.layerManager = new LayerManager();
    }

    public synchronized void start() {
        if (server != null) return;

        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", this::handleRoot);
            server.createContext("/api/roads", this::handleRoadsLegacy);
            server.createContext("/api/roads/geojson", this::handleRoadsGeoJson);
            server.createContext("/api/roads/v2/geojson", this::handleRoadsGeoJson);
            server.createContext("/api/roads/", this::handleRoadById);
            server.createContext("/api/layers", this::handleLayers);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Wayfarer Preview");
                thread.setDaemon(true);
                return thread;
            }));
            Thread startThread = new Thread(() -> {
                server.start();
                LOGGER.info("Wayfarer preview server started on http://localhost:" + PORT + "/");
            }, "Wayfarer Preview Starter");
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
            LOGGER.info("Wayfarer preview server stopped");
        }
    }

    public String getUrl() {
        return "http://localhost:" + PORT + "/";
    }

    public String getFallbackUrl() {
        return "http://127.0.0.1:" + PORT + "/";
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    /** GET / — Leaflet SPA */
    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        try {
            sendText(exchange, 200, createLeafletPage(), "text/html; charset=utf-8");
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Failed to render page", exception);
            sendText(exchange, 500, "Internal error");
        }
    }

    /** GET /api/roads — legacy JSON array (backward-compat) */
    private void handleRoadsLegacy(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        roadDataStore.reloadFromDisk();
        sendText(exchange, 200, roadDataStore.toJson(), "application/json; charset=utf-8");
    }

    /** GET /api/roads/geojson?classification=G,S&bbox=minX,minZ,maxX,maxZ */
    private void handleRoadsGeoJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        roadDataStore.reloadFromDisk();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        Map<String, String> queryParams = parseQueryParams(rawQuery);

        try {
            String geoJson = roadDataStore.toGeoJson();
            if (!queryParams.isEmpty()) {
                String clsFilter = queryParams.get("classification");
                String bboxStr  = queryParams.get("bbox");
                if (clsFilter != null || bboxStr != null) {
                    double[] bbox = null;
                    if (bboxStr != null) {
                        bbox = parseBbox(bboxStr);
                    }
                    geoJson = applyQueryFilters(geoJson, clsFilter, bbox);
                }
            }
            sendText(exchange, 200, geoJson, "application/json; charset=utf-8");
        } catch (IllegalArgumentException ex) {
            sendText(exchange, 400, jsonError("INVALID_PARAM", ex.getMessage()));
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "GeoJSON error", ex);
            sendText(exchange, 500, jsonError("INTERNAL_ERROR", ex.getMessage()));
        }
    }

    /** GET /api/roads/{id} */
    private void handleRoadById(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        roadDataStore.reloadFromDisk();
        String path = exchange.getRequestURI().getPath();
        // strip "/api/roads/"
        String id = path.substring("/api/roads/".length());
        if (id.isEmpty() || id.contains("/")) {
            sendText(exchange, 400, jsonError("INVALID_PARAM", "missing road id"));
            return;
        }
        String feature = roadDataStore.toGeoJsonFeature(id);
        if (feature == null) {
            sendText(exchange, 404, jsonError("NOT_FOUND", "road not found: " + id));
        } else {
            sendText(exchange, 200, feature, "application/json; charset=utf-8");
        }
    }

    /** GET /api/layers */
    private void handleLayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        List<MapLayer> layers = layerManager.getAllLayers();
        Map<String, Object> result = new HashMap<>();
        result.put("layers", layers.stream().map(layer -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", layer.getId());
            m.put("displayName", layer.getDisplayName());
            m.put("zIndex", layer.getZIndex());
            m.put("visible", layer.isVisible());
            return m;
        }).toList());
        sendText(exchange, 200, GSON.toJson(result), "application/json; charset=utf-8");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void sendText(HttpExchange exchange, int statusCode, String content) throws IOException {
        sendText(exchange, statusCode, content, "text/plain; charset=utf-8");
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

    private static String jsonError(String error, String message) {
        return "{\"error\":\"" + escapeJson(error) + "\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && !kv[0].isEmpty()) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private static double[] parseBbox(String bboxStr) {
        String[] parts = bboxStr.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox format: minX,minZ,maxX,maxZ");
        }
        try {
            return new double[] {
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bbox must be numeric");
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Apply classification and/or bbox filters to a GeoJSON string.
     * Pure string-based filter — no JSON parse overhead.
     */
    private static String applyQueryFilters(String geoJson, String clsFilter, double[] bbox) {
        int fcStart = geoJson.indexOf("\"features\": [");
        if (fcStart < 0) fcStart = geoJson.indexOf("\"features\":[");
        if (fcStart < 0) return geoJson;

        String prefix = geoJson.substring(0, geoJson.indexOf('[', fcStart) + 1);
        String rest   = geoJson.substring(prefix.length());

        StringBuilder filtered = new StringBuilder(geoJson.length());
        filtered.append(prefix);

        String[] allowedCls = clsFilter != null ? clsFilter.split(",") : null;

        boolean firstOut = true;
        int idx = 0;
        int len = rest.length();

        while (idx < len) {
            // skip whitespace / commas / ]
            while (idx < len) {
                char c = rest.charAt(idx);
                if (c == ' ' || c == '\n' || c == '\r' || c == ',') {
                    idx++;
                } else if (c == ']') {
                    idx = len; // reached end of features array
                    break;
                } else {
                    break;
                }
            }
            if (idx >= len) break;
            if (rest.charAt(idx) != '{') break;

            // find matching }
            int depth = 0;
            boolean inString = false;
            int start = idx;
            while (idx < len) {
                char c = rest.charAt(idx);
                if (inString) {
                    if (c == '"') inString = false;
                    else if (c == '\\') idx++;
                } else {
                    if (c == '"') inString = true;
                    else if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) { idx++; break; }
                    }
                }
                idx++;
            }

            String feature = rest.substring(start, idx);
            if (passesFilter(feature, allowedCls, bbox)) {
                if (!firstOut) filtered.append(',');
                firstOut = false;
                filtered.append(feature);
            }
        }

        int closeIdx = Math.max(
            geoJson.lastIndexOf("]}"),
            geoJson.lastIndexOf("] }")
        );
        if (closeIdx > fcStart) {
            filtered.append(geoJson.substring(closeIdx));
        } else {
            filtered.append("]}");
        }
        return filtered.toString();
    }

    private static boolean passesFilter(String feature, String[] allowedCls, double[] bbox) {
        if (allowedCls != null) {
            boolean match = false;
            for (String cls : allowedCls) {
                String needle = "\"classification\": \"" + cls.trim() + "\"";
                if (feature.contains(needle)) { match = true; break; }
                // compact form
                needle = "\"classification\":\"" + cls.trim() + "\"";
                if (feature.contains(needle)) { match = true; break; }
            }
            if (!match) return false;
        }

        if (bbox != null) {
            int coordsStart = feature.indexOf("\"coordinates\": [");
            if (coordsStart < 0) coordsStart = feature.indexOf("\"coordinates\":[");
            if (coordsStart >= 0) {
                String coords = feature.substring(coordsStart + 14);
                int pairStart = -1;
                for (int i = 0; i < coords.length(); i++) {
                    if (coords.charAt(i) == '[') { pairStart = i + 1; }
                    else if (coords.charAt(i) == ']' && pairStart >= 0) {
                        String[] nums = coords.substring(pairStart, i).split(",");
                        if (nums.length >= 2) {
                            try {
                                double x = Double.parseDouble(nums[0].trim());
                                double z = Double.parseDouble(nums[1].trim());
                                if (x >= bbox[0] && x <= bbox[2] && z >= bbox[1] && z <= bbox[3]) {
                                    return true;
                                }
                            } catch (NumberFormatException ignored) { }
                        }
                        pairStart = -1;
                    }
                }
                return false;
            }
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Leaflet SPA page
    // ------------------------------------------------------------------

    private String createLeafletPage() {
        roadDataStore.reloadFromDisk();
        String contextLabel = escapeHtml(roadDataStore.getContextLabel());
        String dataFile = escapeHtml(String.valueOf(roadDataStore.getDataFile()));

        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Wayfarer 路网预览</title>
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
              <style>
                *{margin:0;padding:0;box-sizing:border-box}
                html,body{height:100%;overflow:hidden;background:#0d1117;color:#c9d1d9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
                #app{display:flex;height:100%}
                #map{flex:1;min-width:0;background:#0d1117}
                #sidebar{width:340px;background:rgba(22,27,34,0.95);border-left:1px solid #30363d;display:flex;flex-direction:column;overflow:hidden}
                #sidebar-header{padding:14px 16px;border-bottom:1px solid #30363d}
                #sidebar-header h2{font-size:16px;color:#e6edf3;margin-bottom:6px}
                #sidebar-header .meta{font-size:11px;color:#8b949e;line-height:1.6}
                #search-box{margin:10px 16px}
                #search-box input{width:100%;padding:8px 12px;border-radius:6px;border:1px solid #30363d;background:#0d1117;color:#c9d1d9;font-size:13px;outline:none}
                #search-box input:focus{border-color:#58a6ff}
                #road-list{flex:1;overflow-y:auto;padding:0 8px 16px}
                .road-item{padding:10px 12px;margin:2px 0;border-radius:6px;cursor:pointer;transition:background .15s;border-left:3px solid transparent}
                .road-item:hover{background:rgba(88,166,255,0.08)}
                .road-item.active{background:rgba(88,166,255,0.12);border-left-color:#58a6ff}
                .road-item .road-name{font-size:13px;font-weight:600;color:#e6edf3}
                .road-item .road-meta{font-size:11px;color:#8b949e;margin-top:2px}
                .badge{display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:700;margin-right:6px}
                .badge-G{background:#D9432B;color:#fff}
                .badge-S{background:#F0A030;color:#000}
                .badge-X{background:#fff;color:#333;border:1px solid #999}
                .badge-Y{background:#adb5bd;color:#000}
                .badge-C{background:#dee2e6;color:#555}
                #info-card{display:none;position:absolute;bottom:16px;left:16px;z-index:1000;background:rgba(22,27,34,0.95);border:1px solid #30363d;border-radius:8px;padding:14px 16px;max-width:280px;font-size:12px;color:#c9d1d9}
                #info-card h3{font-size:14px;color:#e6edf3;margin-bottom:8px}
                #info-card .close-btn{position:absolute;top:6px;right:10px;background:none;border:none;color:#8b949e;cursor:pointer;font-size:18px}
                #info-card p{margin:3px 0}
                #stats-bar{padding:6px 16px;border-top:1px solid #30363d;font-size:11px;color:#8b949e}
                .leaflet-control-layers{background:rgba(22,27,34,0.95) !important;border:1px solid #30363d !important;border-radius:6px !important;color:#c9d1d9 !important}
                .leaflet-control-layers label{color:#c9d1d9 !important}
                .leaflet-control-layers-overlays label span{color:#c9d1d9 !important}
                .leaflet-control-zoom a{background:rgba(22,27,34,0.9) !important;color:#c9d1d9 !important;border-color:#30363d !important}
                .leaflet-popup-content-wrapper{background:rgba(22,27,34,0.95) !important;color:#c9d1d9 !important;border:1px solid #30363d !important;border-radius:8px !important}
                .leaflet-popup-tip{background:rgba(22,27,34,0.95) !important}
                .leaflet-container{background:#0d1117 !important}
              </style>
            </head>
            <body>
              <div id="app">
                <div id="map"></div>
                <div id="sidebar">
                  <div id="sidebar-header">
                    <h2>Wayfarer 路网预览</h2>
                    <div class="meta">
                      实例：%s<br>
                      数据：%s
                    </div>
                  </div>
                  <div id="search-box">
                    <input type="text" id="search-input" placeholder="搜索道路名称 / 编号..." />
                  </div>
                  <div id="road-list"></div>
                  <div id="stats-bar">加载中…</div>
                </div>
                <div id="info-card">
                  <button class="close-btn" onclick="document.getElementById('info-card').style.display='none'">&times;</button>
                  <h3 id="info-title"></h3>
                  <div id="info-body"></div>
                </div>
              </div>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <script src="https://unpkg.com/leaflet-textpath@1.2.3/leaflet.textpath.js"></script>
              <script>
              (function(){
                var CLASS_COLORS = {
                  G:{color:'#D9432B',weight:4,opacity:0.9},
                  S:{color:'#F0A030',weight:3,opacity:0.85},
                  X:{color:'#ffffff',weight:2,opacity:0.75},
                  Y:{color:'#adb5bd',weight:1.2,opacity:0.6},
                  C:{color:'#dee2e6',weight:0.8,opacity:0.45}
                };
                var CLASS_ORDER = {G:0,S:1,X:2,Y:3,C:4};

                var map = L.map('map',{zoomControl:true,attributionControl:false}).setView([0,0],8);

                var geoJsonLayer = L.geoJSON(null,{
                  style:function(f){
                    var cls=f.properties.classification||'C';
                    var s=CLASS_COLORS[cls]||CLASS_COLORS.C;
                    var rs=f.properties.style||{};
                    return {
                      color:rs.color||s.color,
                      weight:rs.lineWidth||s.weight,
                      opacity:s.opacity,
                      dashArray:rs.dashPattern||null
                    };
                  },
                  onEachFeature:function(feature,layer){
                    var p=feature.properties;
                    var cls=p.classification||'C';
                    // popup
                    layer.bindPopup(
                      '<div style="font-size:13px">'+
                      '<strong><span class="badge badge-'+cls+'">'+cls+'</span>'+(p.name||'未命名道路')+'</strong><br>'+
                      '编号: '+(p.number||'-')+' &nbsp; 长度: '+(p.length||0).toFixed(0)+' 格'+
                      '</div>'
                    );
                    // click
                    layer.on('click',function(e){
                      L.DomEvent.stopPropagation(e);
                      showInfoCard(feature);
                      highlightSidebar(p.id);
                      map.flyTo(e.latlng,Math.max(map.getZoom(),12));
                    });
                    // textpath
                    var label=p.name||p.number||'';
                    if(label){
                      if(cls==='G') layer.setText(label,{repeat:true,center:true,attributes:{fill:'#fff','font-weight':'bold','font-size':'13px'}});
                      else if(cls==='S') layer.setText(label,{repeat:true,center:true,attributes:{fill:'#000','font-weight':'bold','font-size':'11px'}});
                      else if(cls==='X') layer.setText(label,{repeat:true,center:true,attributes:{fill:'#fff','font-size':'10px',stroke:'#999','stroke-width':'1px'}});
                    }
                  }
                }).addTo(map);

                var intersectionLayer = L.layerGroup().addTo(map);

                // Dark grid tile layer
                L.gridLayer({maxZoom:18,tileSize:256,
                  createTile:function(c){
                    var t=L.DomUtil.create('canvas','');
                    t.width=256;t.height=256;
                    var ctx=t.getContext('2d');
                    ctx.fillStyle='#0d1117';ctx.fillRect(0,0,256,256);
                    ctx.strokeStyle='rgba(48,54,61,0.35)';ctx.lineWidth=0.5;
                    var gs=256;
                    if(c.z>=14) gs=16; else if(c.z>=12) gs=32; else if(c.z>=10) gs=64; else if(c.z>=8) gs=128;
                    for(var x=gs;x<256;x+=gs){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,256);ctx.stroke();}
                    for(var y=gs;y<256;y+=gs){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(256,y);ctx.stroke();}
                    if(c.z>=12){
                      ctx.fillStyle='rgba(139,148,158,0.3)';ctx.font='8px monospace';
                      var nw=L.CRS.EPSG3857.pointToLatLng(L.point(c.x*256,c.y*256),c.z);
                      var se=L.CRS.EPSG3857.pointToLatLng(L.point((c.x+1)*256,(c.y+1)*256),c.z);
                      ctx.fillText(nw.lng.toFixed(1)+','+nw.lat.toFixed(1),4,14);
                      ctx.fillText(se.lng.toFixed(1)+','+se.lat.toFixed(1),4,246);
                    }
                    return t;
                  }
                }).addTo(map);

                L.control.layers(null,{
                  '道路路网':geoJsonLayer,
                  '交叉口':intersectionLayer
                },{position:'topright',collapsed:false}).addTo(map);

                // data
                var allFeatures=[];
                var roadNameIndex={};

                // intersection markers
                function renderIntersections(features){
                  intersectionLayer.clearLayers();
                  var seen={};
                  features.forEach(function(f){
                    var det=f.properties.intersectionDetails;
                    if(!det)return;
                    det.forEach(function(is){
                      var key=is.position.x+','+is.position.z;
                      if(seen[key])return;seen[key]=true;
                      L.circleMarker([is.position.z,is.position.x],{
                        radius:4,fillColor:'#58a6ff',color:'#1f6feb',weight:1.5,fillOpacity:0.7
                      }).bindPopup(is.name||is.type||'交叉口').addTo(intersectionLayer);
                    });
                  });
                }

                // sidebar
                function highlightSidebar(id){
                  [].forEach.call(document.querySelectorAll('.road-item'),function(el){el.classList.remove('active')});
                  var el=document.getElementById('road-'+id);
                  if(el){el.classList.add('active');el.scrollIntoView({behavior:'smooth',block:'nearest'});}
                }

                function renderSidebar(features){
                  var list=document.getElementById('road-list');
                  var sorted=features.slice().sort(function(a,b){
                    return (CLASS_ORDER[a.properties.classification||'C']||4)-(CLASS_ORDER[b.properties.classification||'C']||4);
                  });
                  list.innerHTML=sorted.map(function(f){
                    var p=f.properties,cls=p.classification||'C';
                    return '<div class="road-item" id="road-'+p.id+'" onclick="zoomToRoad(\''+p.id+'\')">'+
                      '<span class="badge badge-'+cls+'">'+cls+'</span>'+
                      '<span class="road-name">'+(p.name||'未命名道路')+'</span>'+
                      '<div class="road-meta">'+(p.number||'')+' \u00B7 '+(p.length||0).toFixed(0)+'格 \u00B7 '+(p.intersectionCount||0)+'个交叉口</div>'+
                    '</div>';
                  }).join('');
                  document.getElementById('stats-bar').textContent=features.length+' 条道路';
                }

                function showInfoCard(feature){
                  var p=feature.properties,cls=p.classification||'C';
                  document.getElementById('info-title').innerHTML='<span class="badge badge-'+cls+'">'+cls+'</span>'+(p.name||'未命名道路');
                  document.getElementById('info-body').innerHTML=
                    '<p>编号：'+(p.number||'-')+'</p>'+
                    '<p>等级：'+cls+'道</p>'+
                    '<p>宽度：'+(p.width||'-')+' 格</p>'+
                    '<p>长度：'+(p.length||0).toFixed(0)+' 格</p>'+
                    '<p>交叉口：'+(p.intersectionCount||0)+' 个</p>';
                  document.getElementById('info-card').style.display='block';
                }

                window.zoomToRoad=function(id){
                  var f=allFeatures.find(function(f){return f.properties.id===id;});
                  if(!f)return;
                  var layer=geoJsonLayer.getLayers().find(function(l){return l.feature===f;});
                  if(layer){map.flyToBounds(layer.getBounds().pad(0.3),{duration:0.6});showInfoCard(f);highlightSidebar(id);}
                };

                document.getElementById('search-input').addEventListener('input',function(e){
                  var q=e.target.value.toLowerCase().trim();
                  [].forEach.call(document.querySelectorAll('.road-item'),function(el){
                    el.style.display=el.textContent.toLowerCase().indexOf(q)>=0?'':'none';
                  });
                  if(q&&allFeatures.length>0){
                    var m=allFeatures.find(function(f){
                      return ((f.properties.name||'')+(f.properties.number||'')).toLowerCase().indexOf(q)>=0;
                    });
                    if(m)window.zoomToRoad(m.properties.id);
                  }
                });

                // load
                function loadData(){
                  fetch('/api/roads/geojson',{cache:'no-store'})
                    .then(function(r){return r.json();})
                    .then(function(geo){
                      allFeatures=geo.features||[];
                      geoJsonLayer.clearLayers();
                      geoJsonLayer.addData(geo);
                      renderIntersections(allFeatures);
                      renderSidebar(allFeatures);
                      if(allFeatures.length>0){
                        var b=geoJsonLayer.getBounds();
                        if(b.isValid())map.fitBounds(b.pad(0.15),{maxZoom:14});
                      }
                    })
                    .catch(function(err){
                      console.error('Wayfarer load error',err);
                      document.getElementById('stats-bar').textContent='加载失败';
                    });
                }

                setInterval(loadData,5000);
                loadData();
              })();
              </script>
            </body>
            </html>
            """.formatted(contextLabel, dataFile);
    }

    private static String escapeHtml(String text) {
        return String.valueOf(text)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
