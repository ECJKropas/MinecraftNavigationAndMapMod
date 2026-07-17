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
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Failed to render page", exception);
            sendText(exchange, 500, "Internal error: " + exception.getClass().getSimpleName());
        }
    }

    /** GET /api/roads — legacy JSON array (backward-compat) */
    private void handleRoadsLegacy(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        try {
            roadDataStore.reloadFromDisk();
            sendText(exchange, 200, roadDataStore.toJson(), "application/json; charset=utf-8");
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Legacy roads error", exception);
            sendText(exchange, 500, jsonError("INTERNAL_ERROR", exception.getMessage()));
        }
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
        try {
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
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Road by ID error", exception);
            sendText(exchange, 500, jsonError("INTERNAL_ERROR", exception.getMessage()));
        }
    }

    /** GET /api/layers */
    private void handleLayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        try {
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
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Layers error", exception);
            sendText(exchange, 500, jsonError("INTERNAL_ERROR", exception.getMessage()));
        }
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
                html,body{height:100%;overflow:hidden;background:#f0f4f8;color:#333;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
                #app{display:flex;height:100%}
                #map{flex:1;min-width:0;background:#fff}
                #sidebar{width:340px;background:#fff;border-left:1px solid #4a90d9;display:flex;flex-direction:column;overflow:hidden;box-shadow:-2px 0 8px rgba(0,0,0,0.04)}
                #sidebar-header{padding:14px 16px;border-bottom:1px solid #4a90d9;background:#f8f9fb}
                #sidebar-header h2{font-size:16px;color:#1a1a2e;margin-bottom:6px}
                #sidebar-header .meta{font-size:11px;color:#656d76;line-height:1.6}
                #search-box{margin:10px 16px}
                #search-box input{width:100%;padding:8px 12px;border-radius:6px;border:1px solid #4a90d9;background:#fff;color:#333;font-size:13px;outline:none}
                #search-box input:focus{border-color:#4a90d9;box-shadow:0 0 0 3px rgba(74,144,217,0.15)}
                #road-list{flex:1;overflow-y:auto;padding:0 8px 16px}
                .road-item{padding:10px 12px;margin:2px 0;border-radius:6px;cursor:pointer;transition:background .15s;border-left:3px solid transparent}
                .road-item:hover{background:rgba(74,144,217,0.06)}
                .road-item.active{background:rgba(74,144,217,0.1);border-left-color:#4a90d9}
                .road-item .road-name{font-size:13px;font-weight:600;color:#1a1a2e}
                .road-item .road-meta{font-size:11px;color:#656d76;margin-top:2px}
                .badge{display:inline-block;padding:1px 6px;border-radius:10px;font-size:10px;font-weight:700;margin-right:6px}
                .badge-G{background:#E85D2C;color:#fff}
                .badge-S{background:#F0A030;color:#fff}
                .badge-X{background:#6c757d;color:#fff}
                .badge-Y{background:#8899aa;color:#fff}
                .badge-C{background:#a0b0c0;color:#fff}
                #info-card{display:none;position:absolute;bottom:16px;left:16px;z-index:1000;background:#fff;border:1px solid #4a90d9;border-radius:8px;padding:14px 16px;max-width:280px;font-size:12px;color:#333;box-shadow:0 2px 12px rgba(0,0,0,0.08)}
                #info-card h3{font-size:14px;color:#1a1a2e;margin-bottom:8px}
                #info-card .close-btn{position:absolute;top:6px;right:10px;background:none;border:none;color:#656d76;cursor:pointer;font-size:18px}
                #info-card p{margin:3px 0}
                #stats-bar{padding:6px 16px;border-top:1px solid #4a90d9;font-size:11px;color:#656d76;background:#f8f9fb}
                .leaflet-control-layers{background:#fff !important;border:1px solid #4a90d9 !important;border-radius:6px !important;color:#333 !important;box-shadow:0 2px 6px rgba(0,0,0,0.06) !important}
                .leaflet-control-layers label{color:#333 !important}
                .leaflet-control-layers-overlays label span{color:#333 !important}
                .leaflet-control-zoom a{background:#fff !important;color:#333 !important;border-color:#4a90d9 !important}
                .leaflet-popup-content-wrapper{background:#fff !important;color:#333 !important;border:1px solid #4a90d9 !important;border-radius:8px !important;box-shadow:0 2px 12px rgba(0,0,0,0.1) !important}
                .leaflet-popup-tip{background:#fff !important}
                .leaflet-container{background:#fff !important}
                #cdn-error{display:none;position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);background:rgba(217,67,43,0.9);color:#fff;padding:12px 20px;border-radius:8px;font-size:14px;z-index:2000;text-align:center}
                #cdn-error a{color:#fff;text-decoration:underline}
              </style>
            </head>
            <body>
              <div id="app">
                <div id="map"></div>
                <div id="cdn-error">
                  无法加载 Leaflet 地图库（CDN 不可用）。<br>
                  请检查网络连接后刷新页面。
                </div>
                <div id="sidebar">
                  <div id="sidebar-header">
                    <h2>Wayfarer 路网预览</h2>
                    <div class="meta">
                      实例：{{CTX}}<br>
                      数据：{{FILE}}
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
              <script>
              // CDN fallback detection
              if(typeof L==='undefined'){
                document.getElementById('cdn-error').style.display='block';
                document.getElementById('stats-bar').textContent='CDN 加载失败';
              } else {
              (function(){
                var CLASS_COLORS = {
                  G:{color:'#D9432B',weight:4,opacity:0.9},
                  S:{color:'#F0A030',weight:3,opacity:0.85},
                  X:{color:'#5a6a7a',weight:2,opacity:0.7},
                  Y:{color:'#8899aa',weight:1.5,opacity:0.6},
                  C:{color:'#a0b0c0',weight:1.2,opacity:0.55}
                };
                var CLASS_ORDER = {G:0,S:1,X:2,Y:3,C:4};

                var crs = L.Util.extend({}, L.CRS.Simple, {
                  transformation: new L.Transformation(1, 0, 1, 0),
                  infinite: true
                });
                var map = L.map('map',{crs:crs,zoomControl:true,attributionControl:false});

                var recenterBtn = L.control({position:'topleft'});
                recenterBtn.onAdd = function(){
                  var div = L.DomUtil.create('div','');
                  div.innerHTML = '<button class="recenter-btn">回正</button>';
                  L.DomEvent.on(div,'click',function(e){
                    L.DomEvent.stopPropagation(e);
                    if(allFeatures.length>0){
                      var b=geoJsonLayer.getBounds();
                      if(b.isValid()){map.fitBounds(b.pad(0.15),{maxZoom:14});}
                    }
                  });
                  return div;
                };
                recenterBtn.addTo(map);

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
                    // horizontal road labels (no rotation)
                    var labelHtml='';
                    var number=p.number||'';
                    var coords=feature.geometry.coordinates;
                    var midIdx=Math.floor(coords.length/2);
                    var mid=coords[midIdx];
                    var latlng=[mid[1],mid[0]];
                    if(cls==='G'||cls==='S'){
                      var labelText=cls+number;
                      if(p.name===number){
                        labelHtml='<div style="color:#aaa;font-size:10px;white-space:nowrap;text-shadow:0 0 3px #fff">'+labelText+'</div>';
                      }else{
                        if(cls==='G'){
                          labelHtml='<div style="background:#E85D2C;color:#fff;padding:2px 8px;border-radius:4px;border:2px solid #fff;font-weight:bold;font-size:12px;white-space:nowrap;box-shadow:0 2px 4px rgba(0,0,0,0.25)">'+labelText+'</div>';
                        }else{
                          labelHtml='<div style="background:#F0A030;color:#000;padding:2px 8px;border-radius:4px;border:2px solid #D98A20;font-weight:bold;font-size:12px;white-space:nowrap;box-shadow:0 2px 4px rgba(0,0,0,0.25)">'+labelText+'</div>';
                        }
                        if(p.name&&p.name!==number){
                          labelHtml+='<div style="color:#777;font-size:10px;white-space:nowrap;margin-top:2px;text-shadow:0 0 3px #fff">'+p.name+'</div>';
                        }
                      }
                    }else{
                      labelHtml='<div style="color:#555;font-size:10px;white-space:nowrap;text-shadow:0 0 3px #fff">'+(p.name||p.number||'')+'</div>';
                    }
                    if(labelHtml){
                      L.marker(latlng,{icon:L.divIcon({className:'road-label-icon',html:labelHtml,iconSize:null,iconAnchor:[0,0]}),interactive:false}).addTo(labelLayer);
                    }
                  }
                }).addTo(map);

                var labelLayer = L.layerGroup().addTo(map);

                var intersectionLayer = L.layerGroup().addTo(map);

                // Light grid tile layer
                L.gridLayer({maxZoom:18,tileSize:256,
                  createTile:function(c){
                    var t=L.DomUtil.create('canvas','');
                    t.width=256;t.height=256;
                    var ctx=t.getContext('2d');
                    ctx.fillStyle='#fff';ctx.fillRect(0,0,256,256);
                    ctx.strokeStyle='rgba(180,190,200,0.4)';ctx.lineWidth=0.5;
                    var gs=256;
                    if(c.z>=14) gs=16; else if(c.z>=12) gs=32; else if(c.z>=10) gs=64; else if(c.z>=8) gs=128;
                    for(var x=gs;x<256;x+=gs){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,256);ctx.stroke();}
                    for(var y=gs;y<256;y+=gs){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(256,y);ctx.stroke();}
                    if(c.z>=12){
                      ctx.fillStyle='rgba(100,110,120,0.35)';ctx.font='8px monospace';
                      var nwX=c.x*256,nwY=c.y*256,seX=(c.x+1)*256,seY=(c.y+1)*256;
                      ctx.fillText(nwX.toFixed(0)+','+nwY.toFixed(0),4,14);
                      ctx.fillText(seX.toFixed(0)+','+seY.toFixed(0),4,246);
                    }
                    return t;
                  }
                }).addTo(map);

                // Chunk grid background layer (administrative reference lines)
                var chunkGridLayer = L.layerGroup({zIndex:50});
                chunkGridLayer._layerId = 'administrative';

                // Draw 16x16 block (1 chunk) grid lines within bounds, padded by 2 chunks
                function renderChunkGrid(layer, bounds){
                  layer.clearLayers();
                  if(!bounds || !bounds.isValid()) return;
                  var CHUNK = 16;          // 1 chunk = 16 blocks
                  var PAD = 2 * CHUNK;     // 2-chunk margin
                  var sw = bounds.getSouthWest();
                  var ne = bounds.getNorthEast();
                  var minX = Math.floor((sw.lng - PAD) / CHUNK) * CHUNK;
                  var maxX = Math.ceil((ne.lng + PAD) / CHUNK) * CHUNK;
                  var minY = Math.floor((sw.lat - PAD) / CHUNK) * CHUNK;
                  var maxY = Math.ceil((ne.lat + PAD) / CHUNK) * CHUNK;
                  var lineStyle = {color:'rgba(180,190,200,0.3)', weight:1, dashArray:'2,4', interactive:false};
                  // vertical lines (constant X)
                  for(var x = minX; x <= maxX; x += CHUNK){
                    L.polyline([[minY, x],[maxY, x]], lineStyle).addTo(layer);
                  }
                  // horizontal lines (constant Y)
                  for(var y = minY; y <= maxY; y += CHUNK){
                    L.polyline([[y, minX],[y, maxX]], lineStyle).addTo(layer);
                  }
                }

                L.control.layers(null,{
                  '道路路网':geoJsonLayer,
                  '交叉口':intersectionLayer,
                  '区块网格':chunkGridLayer
                },{position:'topright',collapsed:false}).addTo(map);

                // data
                var allFeatures=[];
                var roadNameIndex={};

                // intersection markers
                function renderIntersections(features){
                  intersectionLayer.clearLayers();
                  var seen={};
                  var count=0;
                  features.forEach(function(f){
                    var det=f.properties.intersectionDetails;
                    if(!det)return;
                    det.forEach(function(is){
                      if(!is.position){return;}
                      var key=is.position.x+','+is.position.z;
                      if(seen[key])return;seen[key]=true;
                      count++;
                      L.circleMarker([is.position.z,is.position.x],{
                        radius:4,fillColor:'#4a90d9',color:'#3678b5',weight:1.5,fillOpacity:0.7
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
                    return `<div class="road-item" id="road-${p.id}" onclick="zoomToRoad('${p.id}')">
                      <span class="badge badge-${cls}">${cls}</span>
                      <span class="road-name">${p.name||'未命名道路'}</span>
                      <div class="road-meta">${p.number||''} \u00B7 ${(p.length||0).toFixed(0)}格 \u00B7 ${p.intersectionCount||0}个交叉口</div>
                    </div>`;
                  }).join('');
                  document.getElementById('stats-bar').textContent=features.length+' 条道路';
                }

                function showInfoCard(feature){
                  var p=feature.properties,cls=p.classification||'C';
                  document.getElementById('info-title').innerHTML=`<span class="badge badge-${cls}">${cls}</span>${p.name||'未命名道路'}`;
                  document.getElementById('info-body').innerHTML=
                    `<p>编号：${p.number||'-'}</p>
                    <p>等级：${cls}道</p>
                    <p>宽度：${p.width||'-'} 格</p>
                    <p>长度：${(p.length||0).toFixed(0)} 格</p>
                    <p>交叉口：${p.intersectionCount||0} 个</p>`;
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
                    .then(function(r){
                      if(!r.ok)throw new Error('HTTP '+r.status);
                      return r.json();
                    })
                    .then(function(geo){
                      allFeatures=geo.features||[];
                      geoJsonLayer.clearLayers();
                      labelLayer.clearLayers();
                      geoJsonLayer.addData(geo);
                      var layerCount=geoJsonLayer.getLayers().length;
                      renderIntersections(allFeatures);
                      renderSidebar(allFeatures);
                      if(allFeatures.length>0){
                        var b=geoJsonLayer.getBounds();
                        if(b.isValid()){
                          map.fitBounds(b.pad(0.15),{maxZoom:14});
                          renderChunkGrid(chunkGridLayer, b);
                        }
                      }else{
                      }
                    })
                    .catch(function(err){
                      console.error('Wayfarer load error',err);
                      document.getElementById('stats-bar').textContent='加载失败: '+err.message;
                    });
                }

                loadData();
              })();
              } // end CDN check
              </script>
            </body>
            </html>
            """.replace("{{CTX}}", contextLabel).replace("{{FILE}}", dataFile);
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
