// Wayfarer Road Editor — Apple-style frontend
// MC coords (X, Z) map to Leaflet [lat=Z/128, lng=X/128]

const SCALE = 128.0;
let map, selectedSegments = new Set(), selectedNodeId = null, selectedSegmentId = null;
let roadStore = { nodes:{}, segments:{}, roads:{} };
let activeTool = null;          // 'move' | 'point' | 'merge' | 'fenhe' | 'softdelete' | null
let playerPos = null;             // {x,z} 玩家当前位置（来自 /api/nav/state），用于直线距离
let pendingPoint = null;          // 当前信息卡对应的 MC 坐标 {x,z}
let plannedDest = null;           // 已规划但未开始的目的地 {x,z}
let plannedRouteLayer = null;     // 规划预览的路线图层
let pointInfoPopup = null;        // Leaflet 点击信息弹窗
let navigationLayer = null;
let navMode = 'WALK';                 // 'WALK' | 'DRIVE' — sent to server (C1)
let navMarkers = { start: null, end: null, player: null };
let navActive = false;
let viewportDirty = true;            // C2: re-cull only when needed
let renderedSegmentCount = 0;       // C2: perf indicator
let totalSegmentCount = 0;
let toolbarMode = 'compact';   // 'compact' | 'detailed'
let appMode = 'navigation';    // 'navigation' | 'edit' — top-level mode gate
let mergeFirstNodeId = null;   // first node selected in merge tool
const TOOL_TOLERANCE_PX = 12;  // pixel tolerance for point tool segment detection
const INTERSECTION_SNAP_PX = 15;  // pixel tolerance for intersection snapping in point tool

// ——— Manual node drag state ———
let dragState = null;  // { nid, marker, axisDx, axisDz, startMcX, startMcZ }
let dragJustEnded = false;
let constrainDrag = true;  // toggled by popup button
let lastSyncServerTime = 0;  // timestamp from last successful delta sync
let editingEntityId = null;  // entity currently being edited (dragging/saving)
let pendingConflicts = new Map();  // id -> { entity, newData } for conflict resolution

function getNodeDragAxes(nid) {
  const axes = [];
  const seen = new Set();
  const node = roadStore.nodes[nid];
  if (!node) return axes;

  for (const seg of Object.values(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const idx = seg.nodeIds.indexOf(nid);
    if (idx === -1) continue;
    for (const ni of [idx - 1, idx + 1]) {
      if (ni < 0 || ni >= seg.nodeIds.length) continue;
      const nb = roadStore.nodes[seg.nodeIds[ni]];
      if (!nb) continue;
      let dx = node.x - nb.x;
      let dz = node.z - nb.z;
      const len = Math.sqrt(dx * dx + dz * dz);
      if (len < 1e-6) continue;
      dx /= len; dz /= len;
      // Canonicalise direction: ensure first non-zero component is positive
      if (dx < -1e-6 || (Math.abs(dx) < 1e-6 && dz < -1e-6)) { dx = -dx; dz = -dz; }
      const key = dx.toFixed(6) + ',' + dz.toFixed(6);
      if (seen.has(key)) continue;
      seen.add(key);
      axes.push({ dx, dz });
    }
  }
  return axes;
}

function pickAxisFromMouse(axes, nodeMc, mouseMcX, mouseMcZ) {
  // Pick the axis whose direction is closest to the mouse movement from the node
  const mdX = mouseMcX - nodeMc.x;
  const mdZ = mouseMcZ - nodeMc.z;
  let best = axes[0];
  let bestDot = -Infinity;
  for (const a of axes) {
    const dot = Math.abs(mdX * a.dx + mdZ * a.dz);
    if (dot > bestDot) { bestDot = dot; best = a; }
  }
  return best;
}

function getConnectedSegmentIds(nid) {
  const ids = [];
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (seg.nodeIds && seg.nodeIds.includes(nid)) ids.push(sid);
  }
  return ids;
}

function updateConnectedSegmentPolylines(nid, newX, newZ) {
  const connectedSegs = getConnectedSegmentIds(nid);
  for (const sid of connectedSegs) {
    const seg = roadStore.segments[sid];
    const idx = seg.nodeIds.indexOf(nid);
    const newLL = mc2latlng(newX, newZ);

    const line = segmentLines.get(sid);
    if (line) {
      const lls = line.getLatLngs();
      lls[idx] = newLL;
      line.setLatLngs(lls);
    }
    const fill = segmentFills.get(sid);
    if (fill) {
      const lls = fill.getLatLngs();
      lls[idx] = newLL;
      fill.setLatLngs(lls);
    }
  }
}

// ——— Undo / Redo ———
let undoStack = [];
let redoStack = [];
const MAX_UNDO = 50;

function snapshotStore() {
  return JSON.stringify({
    nodes: Object.values(roadStore.nodes),
    segments: Object.values(roadStore.segments),
    roads: Object.values(roadStore.roads)
  });
}

function pushUndo() {
  const snap = snapshotStore();
  // Avoid consecutive identical snapshots
  if (undoStack.length > 0 && undoStack[undoStack.length - 1] === snap) return;
  undoStack.push(snap);
  if (undoStack.length > MAX_UNDO) undoStack.shift();
  redoStack = [];  // new action clears redo history
  undoButtonStyle();
}

async function undo() {
  if (undoStack.length === 0) { showToast(I18N.t('toast.nothingToUndo'), 'info'); return; }
  redoStack.push(snapshotStore());
  editingEntityId = '__undo__';
  try {
    const snap = undoStack.pop();
    const res = await fetch('/api/roads/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: snap
    });
    if (!res.ok) {
      showToast(I18N.t('toast.undoFailed'), 'error');
      return;
    }
    showRestoreResult(await res.json(), 'undo');
    lastSyncServerTime = 0;
    clearSelection();
    mergeFirstNodeId = null;
    await loadData();
    undoButtonStyle();
  } catch (e) { showToast(I18N.t('toast.networkError'), 'error'); }
  finally {
    editingEntityId = null;
  }
}

async function redo() {
  if (redoStack.length === 0) { showToast(I18N.t('toast.nothingToRedo'), 'info'); return; }
  undoStack.push(snapshotStore());
  editingEntityId = '__redo__';
  try {
    const snap = redoStack.pop();
    const res = await fetch('/api/roads/restore', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: snap
    });
    if (!res.ok) {
      showToast(I18N.t('toast.redoFailed'), 'error');
      return;
    }
    showRestoreResult(await res.json(), 'redo');
    lastSyncServerTime = 0;
    clearSelection();
    mergeFirstNodeId = null;
    await loadData();
    undoButtonStyle();
  } catch (e) { showToast(I18N.t('toast.networkError'), 'error'); }
  finally {
    editingEntityId = null;
  }
}

function undoButtonStyle() {
  const ub = document.getElementById('tool-undo');
  const rb = document.getElementById('tool-redo');
  if (ub) ub.style.opacity = undoStack.length === 0 ? '0.35' : '';
  if (rb) rb.style.opacity = redoStack.length === 0 ? '0.35' : '';
}

// Number of entities actually changed by a /api/roads/restore response:
// reverted (state rolled back) + restored (re-created after being deleted)
function restoreChangeCount(result) {
  const reverted = (result.revertedNodes || 0) + (result.revertedSegments || 0) + (result.revertedRoads || 0);
  const restored = (result.restoredNodes || 0) + (result.restoredSegments || 0) + (result.restoredRoads || 0);
  return reverted + restored;
}

function showRestoreResult(result, kind) {
  const changed = restoreChangeCount(result);
  if (result.warning) {
    showToast(result.warning + (changed > 0 ? ' (' + changed + ')' : ''), 'warn');
  } else if (changed === 0) {
    showToast(I18N.t(kind === 'undo' ? 'toast.nothingToUndo' : 'toast.nothingToRedo'), 'info');
  } else {
    showToast(I18N.t(kind === 'undo' ? 'toast.undoSuccess' : 'toast.redoSuccess') + ' (' + changed + ')', 'info');
  }
}

// ——— Toast ———
function showToast(msg, type) {
  const t = document.createElement('div');
  t.className = 'toast';
  t.textContent = msg;
  t.style.cssText = `
    position:fixed; bottom:40px; left:50%; transform:translateX(-50%) translateY(12px);
    background:var(--text-primary); color:#fff; font-size:12px; font-weight:500;
    padding:8px 18px; border-radius:20px; z-index:9999; pointer-events:none;
    opacity:0; transition:opacity 200ms ease, transform 300ms cubic-bezier(0.16,1,0.3,1);
    font-family:var(--font); letter-spacing:-0.01em;
  `;
  if (type === 'error') t.style.background = 'var(--red)';
  document.body.appendChild(t);
  requestAnimationFrame(() => {
    t.style.opacity = '1';
    t.style.transform = 'translateX(-50%) translateY(0)';
  });
  setTimeout(() => {
    t.style.opacity = '0';
    t.style.transform = 'translateX(-50%) translateY(-4px)';
    setTimeout(() => t.remove(), 280);
  }, 2200);
}

// ——— Tool toast (bottom-right) ———
function showToolToast(msg, type) {
  const el = document.getElementById('tool-toast');
  el.textContent = msg;
  el.style.color = type === 'green' ? '#34c759' : 'var(--red)';
  el.classList.add('visible');
  setTimeout(() => el.classList.remove('visible'), 2000);
}

// ——— Sheet (Apple-style confirm) ———
function showSheet(title, message, actions) {
  const backdrop = document.createElement('div');
  backdrop.style.cssText = `
    position:fixed; inset:0; background:rgba(0,0,0,0.32); z-index:10000;
    display:flex; align-items:flex-end; justify-content:center;
    opacity:0; transition:opacity 200ms ease;
  `;

  const sheet = document.createElement('div');
  sheet.style.cssText = `
    background:rgba(255,255,255,0.94);
    backdrop-filter:blur(32px) saturate(180%);
    -webkit-backdrop-filter:blur(32px) saturate(180%);
    border-radius:15px 15px 0 0;
    width:100%; max-width:480px; padding:24px 20px 30px;
    transform:translateY(100%);
    transition:transform 350ms cubic-bezier(0.22,1,0.36,1);
    font-family:var(--font);
  `;

  sheet.innerHTML = '<h2 style="font-size:16px;font-weight:590;margin-bottom:6px;color:var(--text-primary);letter-spacing:-0.01em;">' +
    title + '</h2><p style="font-size:13px;color:var(--text-secondary);margin-bottom:20px;line-height:1.45;">' +
    message + '</p><div style="display:flex;flex-direction:column;gap:0;"></div>';

  const btnContainer = sheet.querySelector('div');
  actions.forEach((a, i) => {
    const btn = document.createElement('button');
    const isDestructive = a.role === 'destructive';
    const isCancel = a.role === 'cancel';
    btn.textContent = a.label;
    btn.style.cssText = `
      width:100%; text-align:center; font-size:15px; font-weight:${isCancel ? '590' : '400'};
      padding:12px 0; border:none; background:none; cursor:pointer;
      border-top:1px solid rgba(0,0,0,0.08);
      color:${isDestructive ? 'var(--red)' : 'var(--blue)'};
      font-family:var(--font); letter-spacing:-0.01em;
      -webkit-user-select:none; user-select:none;
    `;
    if (i === 0 && actions.length === 1) btn.style.borderTop = 'none';
    btn.addEventListener('pointerdown', () => { btn.style.background = 'rgba(0,0,0,0.04)'; });
    btn.addEventListener('pointerup', () => { btn.style.background = 'none'; });
    btn.addEventListener('pointerleave', () => { btn.style.background = 'none'; });
    btn.addEventListener('click', () => dismiss(() => a.action && a.action()));
    btnContainer.appendChild(btn);
  });

  function dismiss(cb) {
    sheet.style.transform = 'translateY(100%)';
    backdrop.style.opacity = '0';
    setTimeout(() => { backdrop.remove(); if (cb) cb(); }, 360);
  }

  backdrop.addEventListener('click', e => { if (e.target === backdrop) dismiss(); });
  backdrop.appendChild(sheet);
  document.body.appendChild(backdrop);
  requestAnimationFrame(() => {
    backdrop.style.opacity = '1';
    sheet.style.transform = 'translateY(0)';
  });
}

// ——— Floating editor panel transitions ———
function showEditor(section) {
  const panel = document.getElementById('editor-panel');
  const noSel = document.getElementById('no-selection');
  const nodeEd = document.getElementById('node-editor');
  const segEd = document.getElementById('segment-editor');

  const sections = [noSel, nodeEd, segEd];
  let target = null;
  if (section === 'no-selection') target = noSel;
  else if (section === 'node-editor') target = nodeEd;
  else if (section === 'segment-editor') target = segEd;

  sections.forEach(el => {
    if (el === target) {
      el.style.display = '';
      el.style.opacity = '0';
      el.style.transform = 'translateY(4px)';
      el.style.transition = 'opacity 180ms ease, transform 240ms cubic-bezier(0.22,1,0.36,1)';
      requestAnimationFrame(() => {
        el.style.opacity = '1';
        el.style.transform = 'translateY(0)';
      });
    } else if (el.style.display !== 'none') {
      el.style.opacity = '0';
      el.style.transform = 'translateY(-4px)';
      el.style.transition = 'opacity 120ms ease, transform 160ms ease';
      setTimeout(() => { if (el.style.opacity === '0') el.style.display = 'none'; }, 150);
    }
  });

  panel.classList.add('visible');
}

function hideEditor() {
  document.getElementById('editor-panel').classList.remove('visible');
}

// ——— Map ———
async function initMap() {
  map = L.map('map', {
    crs: L.CRS.Simple,
    minZoom: -4,
    maxZoom: 20,
    zoomControl: true
  }).setView([0, 0], 5);
  map.on('click', onMapClick);
  await loadConfig();
  await loadData();
  setInterval(loadDelta, 1000);
  setInterval(refreshNavigation, 1000);

  // Global drag handlers (document-level to catch mouse outside map)
  document.addEventListener('mousemove', onGlobalMouseMove);
  document.addEventListener('mouseup', onGlobalMouseUp);

  // C2: re-cull only after the viewport actually changed
  map.on('moveend', () => { viewportDirty = true; maybeRender(); });
  map.on('zoomend', () => { viewportDirty = true; maybeRender(); });
}

function onGlobalMouseMove(e) {
  if (!dragState) return;
  const ds = dragState;
  const mcX = e.clientX;
  const mcY = e.clientY;
  // Convert screen coords to map container point, then to latlng, then to MC
  const container = map.getContainer();
  const rect = container.getBoundingClientRect();
  const cp = L.point(mcX - rect.left, mcY - rect.top);
  const ll = map.containerPointToLatLng(cp);
  let newX = ll.lng * SCALE;
  let newZ = ll.lat * SCALE;

  if (ds.axisDx != null) {
    // Project mouse position onto the axis line through start position
    const dx = newX - ds.startMcX;
    const dz = newZ - ds.startMcZ;
    const proj = dx * ds.axisDx + dz * ds.axisDz;
    newX = ds.startMcX + proj * ds.axisDx;
    newZ = ds.startMcZ + proj * ds.axisDz;
  }

  ds.marker.setLatLng(mc2latlng(newX, newZ));
  updateConnectedSegmentPolylines(ds.nid, newX, newZ);
}

function onGlobalMouseUp(e) {
  if (!dragState) return;
  const ds = dragState;
  dragState = null;
  ds.marker.setStyle({ fillOpacity: 0.92, radius: 5 });
  ds.marker._path.style.cursor = 'grab';
  dragJustEnded = true;
  // Defer reset so the synchronous click event (mouseup→click) sees this flag
  setTimeout(() => { dragJustEnded = false; }, 0);
  onNodeDragEnd(ds.nid, ds.marker);
}

async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    if (!res.ok) return;
    const cfg = await res.json();
    if (cfg.maxZoom && typeof cfg.maxZoom === 'number') {
      map.options.maxZoom = cfg.maxZoom;
    }
    buildStylesFromConfig(cfg);
  } catch (e) { /* use defaults */ }
}

function mc2latlng(x, z) { return [z / SCALE, x / SCALE]; }

// ——— Part C2: viewport culling + polyline simplification ———
function pointInView(lat, lng) {
  if (!map) return true;
  const b = map.getBounds();
  return lat >= b.getSouth() && lat <= b.getNorth() && lng >= b.getWest() && lng <= b.getEast();
}

function segmentInView(pts) {
  if (!map || !pts.length) return true;
  let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity;
  for (const p of pts) {
    // Callers hand in either mc2latlng() tuples ([lat, lng]) or LatLng-like objects, so accept both shapes.
    const lat = Array.isArray(p) ? p[0] : p.lat;
    const lng = Array.isArray(p) ? p[1] : p.lng;
    if (!isFinite(lat) || !isFinite(lng)) continue;
    if (lat < minLat) minLat = lat;
    if (lat > maxLat) maxLat = lat;
    if (lng < minLng) minLng = lng;
    if (lng > maxLng) maxLng = lng;
  }
  if (minLat === Infinity) return true;
  const b = map.getBounds();
  return !(maxLat < b.getSouth() || minLat > b.getNorth() || maxLng < b.getWest() || minLng > b.getEast());
}

// Douglas–Peucker in screen space: keeps geometry visually identical at the current zoom while dropping
// redundant vertices, so a long road with hundreds of points becomes a handful of segments on screen.
function simplifyScreen(pts, epsPx) {
  if (pts.length <= 2) return pts;
  const sp = pts.map(p => map.latLngToContainerPoint(p));
  const out = douglasPeucker(sp, epsPx);
  return out.map(p => map.containerPointToLatLng(p));
}

function douglasPeucker(points, epsilon) {
  if (points.length < 3) return points.slice();
  let maxDist = 0, index = 0;
  const end = points.length - 1;
  for (let i = 1; i < end; i++) {
    const d = perpendicularDistance(points[i], points[0], points[end]);
    if (d > maxDist) { maxDist = d; index = i; }
  }
  if (maxDist > epsilon) {
    const left = douglasPeucker(points.slice(0, index + 1), epsilon);
    const right = douglasPeucker(points.slice(index), epsilon);
    return left.slice(0, -1).concat(right);
  }
  return [points[0], points[end]];
}

function perpendicularDistance(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const len = Math.hypot(dx, dy);
  if (len === 0) return Math.hypot(p.x - a.x, p.y - a.y);
  return Math.abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len;
}

// True when any of the entities just synced from the server falls inside the current map view.
function changedEntitiesInView(data) {
  if (!map) return true;
  if (data.nodes) for (const n of data.nodes) { if (pointInView(n.z / SCALE, n.x / SCALE)) return true; }
  if (data.segments) for (const s of data.segments) {
    if (!s.nodeIds) continue;
    let minLat = Infinity, maxLat = -Infinity, minLng = Infinity, maxLng = -Infinity, ok = false;
    for (const nid of s.nodeIds) {
      const n = roadStore.nodes[nid];
      if (!n) continue;
      ok = true;
      const lat = n.z / SCALE, lng = n.x / SCALE;
      if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat;
      if (lng < minLng) minLng = lng; if (lng > maxLng) maxLng = lng;
    }
    if (ok && segmentInView([{lat: minLat, lng: minLng}, {lat: maxLat, lng: maxLng}])) return true;
  }
  return false;
}

// Only re-cull/re-draw when the viewport moved or an edit touched a visible segment.
function maybeRender() {
  if (viewportDirty) { renderAll(); viewportDirty = false; }
}

// ——— Data ———
async function loadData() {
  try {
    const res = await fetch('/api/roads');
    const data = await res.json();
    roadStore.nodes = {};
    roadStore.segments = {};
    roadStore.roads = {};
    if (data.nodes) {
      for (const n of (Array.isArray(data.nodes) ? data.nodes : Object.values(data.nodes))) {
        roadStore.nodes[n.id] = n;
      }
    }
    if (data.segments) {
      for (const s of (Array.isArray(data.segments) ? data.segments : Object.values(data.segments))) {
        roadStore.segments[s.id] = s;
      }
    }
    if (data.roads) {
      if (Array.isArray(data.roads)) {
        for (const r of data.roads) roadStore.roads[r.id] = r;
      } else {
        roadStore.roads = data.roads;
      }
    }
    renderAll();
  } catch (e) { showToast(I18N.t('toast.loadFailed') + ': ' + e.message, 'error'); }
}

async function loadDelta() {
  try {
    const since = lastSyncServerTime > 0 ? lastSyncServerTime : Math.floor(Date.now() - 5000);
    const res = await fetch('/api/roads/delta?since=' + since);
    if (!res.ok) return;
    const data = await res.json();

    // Update server time for next poll
    if (data.serverTime) {
      lastSyncServerTime = data.serverTime;
    }

    let changed = false;

    // Apply node deltas — skip entities being actively edited
    if (data.nodes && data.nodes.length > 0) {
      for (const n of data.nodes) {
        // Don't overwrite if user is currently editing this node
        if (editingEntityId === n.id) continue;
        roadStore.nodes[n.id] = n;
        changed = true;
      }
    }

    // Apply segment deltas — skip segments being edited
    if (data.segments && data.segments.length > 0) {
      for (const s of data.segments) {
        if (editingEntityId === s.id) continue;
        // Also skip if any node in this segment is being edited
        if (editingEntityId && s.nodeIds && s.nodeIds.includes(editingEntityId)) continue;
        roadStore.segments[s.id] = s;
        changed = true;
      }
    }

    // Apply road deltas
    if (data.roads) {
      for (const [id, r] of Object.entries(data.roads)) {
        if (editingEntityId === id) continue;
        roadStore.roads[id] = r;
        changed = true;
      }
    }

    if (changed && (viewportDirty || changedEntitiesInView(data))) {
      renderAll();
      viewportDirty = false;
    }
  } catch (e) { /* silent */ }
}

// ——— Conflict resolution ———
async function handleConflict(entityId, entityType, serverData, clientData) {
  const entityTypeKey = entityType === '节点' ? 'entity.node' : entityType === '路段' ? 'entity.segment' : 'entity.road';
  const entityTypeStr = I18N.t(entityTypeKey);
  
  const message = I18N.t('sheet.conflict.message', {
    entityType: entityTypeStr,
    serverVersion: serverData.version,
    clientVersion: clientData.expectedVersion
  });

  const choice = prompt(message + '\n\n' + 
    I18N.t('sheet.conflict.optionA') + '\n' + 
    I18N.t('sheet.conflict.optionR'));

  if (choice === null) {
    if (entityType === '节点') {
      roadStore.nodes[entityId] = serverData;
    } else if (entityType === '路段') {
      roadStore.segments[entityId] = serverData;
    } else if (entityType === '道路') {
      roadStore.roads[entityId] = serverData;
    }
    renderAll();
    showToast(I18N.t('toast.acceptedGameVersion'), 'info');
    return 'accepted';
  }

  const choiceLower = choice.toLowerCase();
  if (choiceLower === 'a' || choiceLower === 'accept') {
    if (entityType === '节点') {
      roadStore.nodes[entityId] = serverData;
    } else if (entityType === '路段') {
      roadStore.segments[entityId] = serverData;
    } else if (entityType === '道路') {
      roadStore.roads[entityId] = serverData;
    }
    renderAll();
    showToast(I18N.t('toast.acceptedGameVersion'), 'info');
    return 'accepted';
  } else if (choiceLower === 'r' || choiceLower === 'retry') {
    showToast(I18N.t('toast.retrying'), 'info');
    return 'retry';
  }
  return 'accepted';
}

async function saveNode() {
  const nid = selectedNodeId;
  if (!nid) return;
  const node = roadStore.nodes[nid];
  const x = parseFloat(document.getElementById('node-x').value);
  const z = parseFloat(document.getElementById('node-z').value);

  // Mark as editing to prevent delta overwrite
  editingEntityId = nid;

  try {
    pushUndo();
    const res = await fetch('/api/nodes/' + nid, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, z, expectedVersion: node.version })
    });
    if (res.status === 409) {
      const errData = await res.json();
      showToast(I18N.t('toast.versionConflict') + ': ' + (errData.error || I18N.t('toast.gameModified')), 'error');
      await loadData();
      editingEntityId = null;
      showToast(I18N.t('toast.syncedFromGame'), 'info');
      return;
    }
    if (!res.ok) { showToast(I18N.t('toast.saveFailed'), 'error'); return; }
    const updated = await res.json();
    roadStore.nodes[nid] = updated;
    renderAll();
    showToast(I18N.t('toast.nodeSaved'));
  } catch (e) { showToast(I18N.t('toast.networkError'), 'error'); }
  finally {
    editingEntityId = null;
  }
}

// ——— Direction arrows ———
function renderDirectionArrows(sid, seg, pts) {
  if (pts.length < 2) return;
  const direction = (seg.direction || '').toUpperCase();
  if (direction === 'BIDIRECTIONAL' || direction === '') return;

  // Calculate cumulative distances in MC coordinates
  const cumDist = [0];
  for (let i = 1; i < pts.length; i++) {
    const dx = (pts[i].lng - pts[i - 1].lng) * SCALE;
    const dz = (pts[i].lat - pts[i - 1].lat) * SCALE;
    cumDist.push(cumDist[i - 1] + Math.sqrt(dx * dx + dz * dz));
  }
  const totalLen = cumDist[cumDist.length - 1];
  if (totalLen < 15) return;

  // Determine arrow count and spacing
  const spacing = 60; // MC units between arrows
  const arrowCount = Math.max(1, Math.min(5, Math.floor(totalLen / spacing)));
  const interval = totalLen / (arrowCount + 1);
  const markers = [];

  for (let a = 1; a <= arrowCount; a++) {
    const targetDist = a * interval;

    // Find the segment containing this distance
    let segIdx = 1;
    for (; segIdx < cumDist.length; segIdx++) {
      if (cumDist[segIdx] >= targetDist) break;
    }
    if (segIdx >= cumDist.length) segIdx = cumDist.length - 1;

    const segStartDist = cumDist[segIdx - 1];
    const segLen = cumDist[segIdx] - segStartDist;
    const t = segLen > 0 ? (targetDist - segStartDist) / segLen : 0;

    const p1 = pts[segIdx - 1];
    const p2 = pts[segIdx];
    const lat = p1.lat + t * (p2.lat - p1.lat);
    const lng = p1.lng + t * (p2.lng - p1.lng);

    // Calculate bearing (degrees, 0=N, 90=E)
    // Convert to screen bearing: dx=lng (E-W), dz=lat (N-S in Leaflet)
    const dLng = p2.lng - p1.lng;
    const dLat = p2.lat - p1.lat;
    // In Leaflet CRS.Simple, lng maps to MC X, lat maps to MC Z
    // Bearing: atan2(dLng, dLat) gives angle from north, clockwise
    let bearing = Math.atan2(dLng, dLat) * 180 / Math.PI;

    if (direction === 'BACKWARD') {
      bearing += 180;
    }

    const normalizedBearing = ((bearing % 360) + 360) % 360;

    // Create arrow marker using divIcon
    const icon = L.divIcon({
      className: '',
      html: `<div style="
        transform: rotate(${normalizedBearing}deg);
        color: #BBBBBB;
        font-size: 11px;
        line-height: 1;
        pointer-events: none;
        filter: drop-shadow(0 0 1px rgba(255,255,255,0.7));
      "><i class="fa-solid fa-angle-right"></i></div>`,
      iconSize: [11, 11],
      iconAnchor: [5.5, 5.5],
    });
    const marker = L.marker([lat, lng], { icon, interactive: false }).addTo(map);
    markers.push(marker);
  }

  segmentArrows.set(sid, markers);
}

// ——— Road styling ———
let ROAD_STYLES = {
  G: { lineColor: '#FFC000', lineWeight: 6, badgeBg: '#FFC000', badgeBorder: '#FFFFFF', badgeColor: '#000000' },
  S: { lineColor: '#FFD700', lineWeight: 5, badgeBg: '#FFD700', badgeColor: '#000000' },
};

let CLASSIFICATION_WEIGHTS = {
  X: { edge: 4.5, fill: 3.0, hover: { edge: 5, fill: 3.5 } },
  Y: { edge: 3.5, fill: 2.5, hover: { edge: 4, fill: 3 } },
  C: { edge: 4, fill: 2.5, hover: { edge: 5, fill: 3.5 } },
};

function hexToCssColor(hex) {
  if (!hex) return '#FFFFFF';
  return hex.startsWith('#') ? hex : '#' + hex;
}

function buildStylesFromConfig(cfg) {
  if (!cfg || !cfg.classificationStyles) return;
  const cs = cfg.classificationStyles;

  if (cs.G) {
    const c = hexToCssColor(cs.G.color);
    ROAD_STYLES.G = { lineColor: c, lineWeight: cs.G.width || 6, badgeBg: c, badgeBorder: '#FFFFFF', badgeColor: '#000000' };
  }
  if (cs.S) {
    const c = hexToCssColor(cs.S.color);
    ROAD_STYLES.S = { lineColor: c, lineWeight: cs.S.width || 5, badgeBg: c, badgeColor: '#000000' };
  }
  if (cs.X) {
    const w = cs.X.width || 3.5;
    CLASSIFICATION_WEIGHTS.X = { edge: w + 1, fill: w, hover: { edge: w + 1.5, fill: w + 0.5 } };
  }
  if (cs.Y) {
    const w = cs.Y.width || 3.0;
    CLASSIFICATION_WEIGHTS.Y = { edge: w + 1, fill: w, hover: { edge: w + 1.5, fill: w + 0.5 } };
  }
  if (cs.C) {
    const w = cs.C.width || 3.0;
    const c = hexToCssColor(cs.C.color);
    CLASSIFICATION_WEIGHTS.C = { edge: w + 1, fill: w, hover: { edge: w + 2, fill: w + 1 } };
    CLASSIFICATION_WEIGHTS.C.fillColor = c;
  }
}

let nodeMarkers = new Map();
let segmentLines = new Map();
let segmentFills = new Map();  // fill polylines for dual-layer roads
let segmentArrows = new Map();  // direction arrow markers per segment

function renderAll() {
  nodeMarkers.forEach(m => map.removeLayer(m));
  segmentLines.forEach(l => map.removeLayer(l));
  segmentFills.forEach(l => map.removeLayer(l));
  segmentArrows.forEach(markers => markers.forEach(m => map.removeLayer(m)));
  nodeMarkers.clear();
  segmentLines.clear();
  segmentFills.clear();
  segmentArrows.clear();

  // Clear label markers
  if (!window._roadLabels) window._roadLabels = new Set();
  window._roadLabels.forEach(l => map.removeLayer(l));
  window._roadLabels.clear();

  renderedSegmentCount = 0;
  totalSegmentCount = Object.keys(roadStore.segments).length;

  // Build point arrays helper
  function buildPoints(seg) {
    const pts = [];
    if (!seg.nodeIds) return pts;
    for (const nid of seg.nodeIds) {
      const node = roadStore.nodes[nid];
      if (node && typeof node.x === 'number' && typeof node.z === 'number' && isFinite(node.x) && isFinite(node.z)) {
        pts.push(mc2latlng(node.x, node.z));
      }
    }
    return pts;
  }

  // Group segments by road
  const roadGroups = {};
  const unassigned = [];
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    if (seg.roadId && roadStore.roads[seg.roadId]) {
      const rid = seg.roadId;
      if (!roadGroups[rid]) roadGroups[rid] = { road: roadStore.roads[rid], items: [] };
      roadGroups[rid].items.push({ id: sid, seg });
    } else {
      unassigned.push({ id: sid, seg });
    }
  }

  // Render unassigned (gray edge + white fill)
  for (const { id: sid, seg } of unassigned) {
    let pts = buildPoints(seg);
    if (pts.length < 2) continue;
    if (!segmentInView(pts)) continue;
    pts = simplifyScreen(pts, 1.2);
    renderedSegmentCount++;
    const isSelected = selectedSegments.has(sid);
    const eo = { color: '#BBBBBB', weight: isSelected ? 5.5 : 4.5, opacity: isSelected ? 1 : 0.5, smoothFactor: 0.2 };
    const fo = { color: '#F8F8F8', weight: isSelected ? 3.5 : 3, opacity: isSelected ? 1 : 0.92, smoothFactor: 0.2 };
    const edge = L.polyline(pts, eo).addTo(map);
    const fill = L.polyline(pts, fo).addTo(map);
    [edge, fill].forEach(line => {
      line.on('mouseover', () => { if (!isSelected) { edge.setStyle({ weight: 5.5, opacity: 0.7 }); fill.setStyle({ weight: 4 }); } });
      line.on('mouseout', () => { if (!isSelected) { edge.setStyle(eo); fill.setStyle(fo); } });
      line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (appMode !== 'edit') { openPointInfo(e.latlng); return; } if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
    });
    segmentLines.set(sid, edge);
    segmentFills.set(sid, fill);
    renderDirectionArrows(sid, seg, pts);
  }

  // Render road groups + labels
  for (const [rid, group] of Object.entries(roadGroups)) {
    const road = group.road;
    const raw = road.classification || '';
    const cls = raw.length > 1 && /^[GSXYC]/.test(raw) ? raw.charAt(0) : raw;
    const num = road.number || '';
    const styl = ROAD_STYLES[cls] || null;
    const isGorS = styl != null;
    const cw = CLASSIFICATION_WEIGHTS[cls] || null;

    const allPts = [];

    for (const { id: sid, seg } of group.items) {
      let pts = buildPoints(seg);
      if (pts.length < 2) continue;
      if (!segmentInView(pts)) continue;
      pts = simplifyScreen(pts, 1.2);
      renderedSegmentCount++;
      const mid = pts[Math.floor(pts.length / 2)];
      if (mid && isFinite(mid.lat) && isFinite(mid.lng)) {
        allPts.push(mid);
      }
      const isSelected = selectedSegments.has(sid);

      if (isGorS) {
        // Colored line for G and S
        const c = isSelected ? '#007AFF' : styl.lineColor;
        const opts = { color: c, weight: isSelected ? 6 : styl.lineWeight, opacity: isSelected ? 1 : 0.88, smoothFactor: 0.2 };
        const line = L.polyline(pts, opts).addTo(map);
        line.on('mouseover', () => { if (!isSelected) line.setStyle({ weight: styl.lineWeight + 1, opacity: 1 }); });
        line.on('mouseout', () => { if (!isSelected) line.setStyle({ weight: styl.lineWeight, opacity: 0.88 }); });
        line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (appMode !== 'edit') { openPointInfo(e.latlng); return; } if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
        segmentLines.set(sid, line);
        renderDirectionArrows(sid, seg, pts);
      } else if (cls === 'C') {
        // Gray dual-layer for village roads: dark gray border + config-colored fill
        const fillColor = (cw && cw.fillColor) ? cw.fillColor : '#AAAAAA';
        const eo = { color: '#555555', weight: isSelected ? 5.5 : (cw ? cw.edge : 4), opacity: isSelected ? 1 : 0.75, smoothFactor: 0.2 };
        const fo = { color: fillColor, weight: isSelected ? 3.5 : (cw ? cw.fill : 2.5), opacity: isSelected ? 1 : 0.85, smoothFactor: 0.2 };
        const edge = L.polyline(pts, eo).addTo(map);
        const fill = L.polyline(pts, fo).addTo(map);
        [edge, fill].forEach(line => {
          line.on('mouseover', () => { if (!isSelected) { edge.setStyle({ weight: cw ? cw.hover.edge : 5, opacity: 0.9 }); fill.setStyle({ weight: cw ? cw.hover.fill : 3.5, opacity: 0.95 }); } });
          line.on('mouseout', () => { if (!isSelected) { edge.setStyle(eo); fill.setStyle(fo); } });
          line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (appMode !== 'edit') { openPointInfo(e.latlng); return; } if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
        });
        segmentLines.set(sid, edge);
        segmentFills.set(sid, fill);
        renderDirectionArrows(sid, seg, pts);
      } else {
        // White fill + gray edge for X, Y, and unclassified
        const eo = { color: '#BBBBBB', weight: isSelected ? 5.5 : (cw ? cw.edge : 4.5), opacity: isSelected ? 1 : 0.5, smoothFactor: 0.2 };
        const fo = { color: '#F8F8F8', weight: isSelected ? 3.5 : (cw ? cw.fill : 3), opacity: isSelected ? 1 : 0.92, smoothFactor: 0.2 };
        const edge = L.polyline(pts, eo).addTo(map);
        const fill = L.polyline(pts, fo).addTo(map);
        [edge, fill].forEach(line => {
          line.on('mouseover', () => { if (!isSelected) { edge.setStyle({ weight: cw ? cw.hover.edge : 5.5, opacity: 0.7 }); fill.setStyle({ weight: cw ? cw.hover.fill : 4 }); } });
          line.on('mouseout', () => { if (!isSelected) { edge.setStyle(eo); fill.setStyle(fo); } });
          line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (appMode !== 'edit') { openPointInfo(e.latlng); return; } if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
        });
        segmentLines.set(sid, edge);
        segmentFills.set(sid, fill);
        renderDirectionArrows(sid, seg, pts);
      }
    }

    // Place label at centroid of segment midpoints
    if (allPts.length === 0) continue;
    let sumLat = 0, sumLng = 0;
    for (const pt of allPts) { sumLat += pt.lat; sumLng += pt.lng; }
    const cLat = sumLat / allPts.length, cLng = sumLng / allPts.length;

    const roadName = road.name || '';
    let labelHtml = '';

    if (isGorS) {
      const badgeText = cls + (num || '');
      const showName = roadName && roadName !== badgeText && roadName !== cls && roadName !== num;
      labelHtml = `<div style="position:absolute;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;gap:3px;pointer-events:none;">
        <div style="background:${styl.badgeBg};${styl.badgeBorder ? 'border:1.5px solid ' + styl.badgeBorder + ';' : ''}color:${styl.badgeColor};font-size:11px;font-weight:700;padding:2px 7px;border-radius:4px;white-space:nowrap;letter-spacing:0.02em;line-height:1.3;">${badgeText}</div>`;
      if (showName) {
        labelHtml += `<div style="color:#999;font-size:10px;font-weight:400;text-shadow:0 0 2px #fff;white-space:nowrap;letter-spacing:0.01em;">${roadName}</div>`;
      }
      labelHtml += `</div>`;
    } else if (cls === 'C') {
      if (roadName) {
        labelHtml = `<div style="position:absolute;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;gap:2px;pointer-events:none;">
          <div style="background:rgba(85,85,85,0.85);color:#fff;font-size:10px;font-weight:600;padding:1px 6px;border-radius:3px;white-space:nowrap;letter-spacing:0.02em;line-height:1.3;">C ${roadName}</div>
        </div>`;
      }
    } else if (cls === 'X' || cls === 'Y') {
      const badgeText = cls + (num || '');
      const showName = roadName && roadName !== badgeText && roadName !== cls && roadName !== num;
      labelHtml = `<div style="position:absolute;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;gap:2px;pointer-events:none;">
        <div style="background:#F8F8F8;border:1.5px solid #BBBBBB;color:#333;font-size:10px;font-weight:700;padding:1px 6px;border-radius:3px;white-space:nowrap;letter-spacing:0.02em;line-height:1.3;">${badgeText}</div>`;
      if (showName) {
        labelHtml += `<div style="color:#999;font-size:10px;font-weight:400;text-shadow:0 0 2px #fff,0 0 2px #fff;white-space:nowrap;letter-spacing:0.01em;">${roadName}</div>`;
      }
      labelHtml += `</div>`;
    } else if (roadName) {
      labelHtml = `<div style="position:absolute;transform:translate(-50%,-50%);display:flex;flex-direction:column;align-items:center;pointer-events:none;">
        <div style="color:#999;font-size:10px;font-weight:400;text-shadow:0 0 2px #fff,0 0 2px #fff;white-space:nowrap;letter-spacing:0.01em;">${roadName}</div>
      </div>`;
    }

    if (labelHtml) {
      const icon = L.divIcon({ className: '', html: labelHtml, iconSize: [0, 0], iconAnchor: [0, 0] });
      const marker = L.marker([cLat, cLng], { icon, interactive: false }).addTo(map);
      window._roadLabels.add(marker);
    }
  }

  // Render nodes
  for (const [nid, node] of Object.entries(roadStore.nodes)) {
    const fill = node.source === 'AUTO' ? '#aeaeb2'
      : node.cornerType === 'SHARP' ? '#FF3B30' : '#007AFF';
    const isMergeTarget = activeTool === 'merge' && nid === mergeFirstNodeId;
    if (!pointInView(node.z / SCALE, node.x / SCALE)) continue;
    const marker = L.circleMarker(mc2latlng(node.x, node.z), {
      radius: isMergeTarget ? 7 : 5,
      fillColor: isMergeTarget ? '#FFD60A' : fill,
      color: isMergeTarget ? '#FF9500' : 'rgba(255,255,255,0.9)',
      weight: isMergeTarget ? 3.5 : 2.5,
      fillOpacity: 0.92,
    }).addTo(map);
    if (activeTool === 'move') {
      marker._path.style.cursor = 'grab';
      L.DomEvent.on(marker._path, 'mousedown', (e) => {
        L.DomEvent.stopPropagation(e);
        L.DomEvent.preventDefault(e);
        const m = nodeMarkers.get(nid);
        if (!m) return;
        const node = roadStore.nodes[nid];
        if (!node) return;

        let axisDx = null, axisDz = null;
        if (constrainDrag) {
          // Convert mouse screen→MC to pick the axis closest to drag direction
          const container = map.getContainer();
          const rect = container.getBoundingClientRect();
          const cp = L.point(e.clientX - rect.left, e.clientY - rect.top);
          const ll = map.containerPointToLatLng(cp);
          const axes = getNodeDragAxes(nid);
          if (axes.length > 0) {
            const best = pickAxisFromMouse(axes, node, ll.lng * SCALE, ll.lat * SCALE);
            axisDx = best.dx; axisDz = best.dz;
          }
        }

        dragState = { nid, marker: m, axisDx, axisDz, startMcX: node.x, startMcZ: node.z };
        m.setStyle({ fillOpacity: 0.55, radius: 6.5 });
        m._path.style.cursor = 'grabbing';
      });
    }
    marker.on('mouseover', () => { if (activeTool !== 'move') marker.setRadius(6.5); });
    marker.on('mouseout', () => { if (activeTool !== 'move') marker.setRadius(5); });
    marker.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      if (dragJustEnded) { dragJustEnded = false; return; }
      if (appMode !== 'edit') { openPointInfo(e.latlng); return; }   // 导航模式：点节点也弹信息卡（查看/到这去）
      if (activeTool === 'point') { handlePointTool(e.latlng); return; }
      if (activeTool === 'merge') { handleMergeTool(nid); return; }
      if (activeTool === 'fenhe') { handleFenHeTool(nid); return; }
      if (activeTool === 'softdelete') { handleSoftDeleteTool(nid); return; }
      onNodeClick(nid, e.originalEvent);
    });
    nodeMarkers.set(nid, marker);
  }

  const perfText = document.getElementById('perf-text');
  if (perfText) perfText.textContent = I18N.t('perf.rendered', { n: renderedSegmentCount, total: totalSegmentCount });
}

// ——— Interactions ———
function onMapClick(e) {
  if (appMode === 'edit') {
    // Editing mode: a tool may use the click (e.g. point tool); otherwise just clear.
    if (activeTool === 'point') { handlePointTool(e.latlng); return; }
    clearSelection();
    return;
  }
  // Navigation mode: tap anywhere to inspect the point (distance, nearby roads, 到这去).
  openPointInfo(e.latlng);
}

// ——— Part C1: Navigation ———
function latlng2mc(latlng) {
  return { x: latlng.lng * SCALE, z: latlng.lat * SCALE };
}

// ——— 导航模式：点地图任意处 → 信息卡（直线距离 / 附近道路 / 到这去）———
async function openPointInfo(latlng) {
  const mc = latlng2mc(latlng);
  pendingPoint = mc;

  // 拉取玩家位置用于直线距离
  try {
    const st = await (await fetch('/api/nav/state')).json();
    if (typeof st.playerX === 'number' && typeof st.playerZ === 'number') {
      playerPos = { x: st.playerX, z: st.playerZ };
    }
  } catch (e) { /* 服务器可能未就绪 */ }

  const straight = playerPos
    ? formatDistance(Math.hypot(mc.x - playerPos.x, mc.z - playerPos.z)) + ' ' + I18N.t('nav.meters')
    : '—';

  const roads = nearbyRoads(mc.x, mc.z, 45);
  const roadsHtml = roads.length
    ? roads.map(r => '<div class="pi-road">' + escapeHtml(r) + '</div>').join('')
    : '<div class="pi-empty">' + I18N.t('nav.noNearbyRoad') + '</div>';

  const html =
    '<div class="point-info">' +
      '<div class="pi-row"><span class="pi-k">' + I18N.t('nav.straightDist') + '</span>' +
        '<span class="pi-v">' + straight + '</span></div>' +
      '<div class="pi-k" style="margin:2px 0 6px">' + I18N.t('nav.nearbyRoads') + '</div>' +
      '<div class="pi-roads">' + roadsHtml + '</div>' +
      '<button class="pi-go" id="pi-go-here"><i class="fa-solid fa-location-arrow"></i> ' + I18N.t('nav.goHere') + '</button>' +
    '</div>';

  if (!pointInfoPopup) pointInfoPopup = L.popup({ className: 'wayfarer-popup', closeButton: true, autoPan: true, maxWidth: 260 });
  pointInfoPopup.setLatLng(latlng).setContent(html).openOn(map);
}

function closePointInfo() {
  if (pointInfoPopup) { pointInfoPopup.remove(); pointInfoPopup = null; }
  pendingPoint = null;
}

// 找出点附近一定半径内的道路（按到路段折线的距离），返回路名列表（最多 5 条）
function nearbyRoads(x, z, radius) {
  const found = [];
  const seen = new Set();
  for (const seg of Object.values(roadStore.segments)) {
    if (!seg.nodeIds || seg.nodeIds.length < 2) continue;
    let minD = Infinity;
    let prev = null;
    for (const nid of seg.nodeIds) {
      const n = roadStore.nodes[nid];
      if (!n) continue;
      minD = Math.min(minD, Math.hypot(n.x - x, n.z - z));
      if (prev) minD = Math.min(minD, distToSegment(x, z, prev.x, prev.z, n.x, n.z));
      prev = n;
    }
    if (minD <= radius && seg.roadId && !seen.has(seg.roadId)) {
      seen.add(seg.roadId);
      const road = roadStore.roads[seg.roadId];
      const name = road && (road.name || (road.classification + ' ' + (road.number || '')).trim());
      found.push({ d: minD, name: name || I18N.t('nav.unnamedRoad') });
    }
  }
  found.sort((a, b) => a.d - b.d);
  return found.slice(0, 5).map(r => r.name);
}

function distToSegment(px, pz, ax, az, bx, bz) {
  const dx = bx - ax, dz = bz - az;
  const len2 = dx * dx + dz * dz;
  if (len2 < 1e-9) return Math.hypot(px - ax, pz - az);
  let t = ((px - ax) * dx + (pz - az) * dz) / len2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ——— 规划路线（仅计算，不真正开始导航）———
async function planRoute(x, z) {
  try {
    closePointInfo();
    showToast(I18N.t('nav.planning'));
    const res = await fetch('/api/nav/start', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, z, mode: navMode })
    });
    const data = await res.json();
    // 规划后立刻复位后端会话，避免真正激活导航；等用户点「开始导航」再 start
    fetch('/api/nav/stop', { method: 'POST' }).catch(() => {});
    if (!res.ok || !data.ok) {
      showToast(I18N.t('nav.planFailed') + '：' + routeErrorHint(data.error), 'error');
      return;
    }
    plannedDest = { x, z };
    renderPlannedRoute(data);
    showRouteSummary(data);
  } catch (e) { showToast('规划失败: ' + e.message, 'error'); }
}

function renderPlannedRoute(data) {
  clearPlannedRoute();
  const coords = (data.coordinates && data.coordinates.length > 1) ? data.coordinates : null;
  if (!coords) return;
  const pts = coords.map(c => mc2latlng(c[0], c[1]));
  plannedRouteLayer = L.polyline(pts, {
    color: '#007AFF', weight: 5, opacity: 0.55, dashArray: '2,10', lineCap: 'round', lineJoin: 'round'
  }).addTo(map);
  if (data.destination) {
    plannedRouteLayer._destMarker = L.circleMarker(mc2latlng(data.destination.x, data.destination.z), {
      radius: 7, color: '#fff', weight: 3, fillColor: '#FF3B30', fillOpacity: 1
    }).addTo(map);
  }
}

function clearPlannedRoute() {
  if (plannedRouteLayer) {
    if (plannedRouteLayer._destMarker) map.removeLayer(plannedRouteLayer._destMarker);
    map.removeLayer(plannedRouteLayer);
    plannedRouteLayer = null;
  }
}

function showRouteSummary(data) {
  let dist = 0;
  const coords = data.coordinates || [];
  for (let i = 1; i < coords.length; i++) {
    dist += Math.hypot(coords[i][0] - coords[i - 1][0], coords[i][1] - coords[i - 1][1]);
  }
  document.getElementById('rs-distance').textContent = formatDistance(dist) + ' ' + I18N.t('nav.meters');
  document.getElementById('rs-eta').textContent = formatDuration(data.etaSeconds || 0);
  document.getElementById('route-summary').classList.add('visible');
}

function hideRouteSummary() {
  document.getElementById('route-summary').classList.remove('visible');
}

// 把后端路由失败码翻译成人话，便于定位是「离道路太远」还是「路网不连通」
function routeErrorHint(code) {
  const map = {
    DESTINATION_NOT_NEAR_ROAD: '终点不在道路附近，请点在道路线上',
    START_NOT_NEAR_ROAD: '你当前位置离道路太远，走到路上再试',
    NO_ROAD: '附近没有道路节点',
    NO_ROUTE: '道路之间不连通（路口处节点未合并）',
    INVALID_INPUT: '坐标无效'
  };
  return map[code] || (code || '未知错误');
}

async function startNavigation(x, z) {
  try {
    clearPlannedRoute();
    hideRouteSummary();
    setActiveTool(null);   // navigating must not keep an editing tool armed
    const res = await fetch('/api/nav/start', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ x, z, mode: navMode })
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      showToast(I18N.t('nav.noRoute') + '：' + routeErrorHint(data.error), 'error');
      return;
    }
    navActive = true;
    showNavPanel();
    renderNavigation(data);
    updateNavPanel(data);
  } catch (e) { showToast('导航请求失败: ' + e.message, 'error'); }
}

function clearNavLayers() {
  if (navigationLayer) { map.removeLayer(navigationLayer); navigationLayer = null; }
  for (const k of ['start', 'end', 'player']) {
    if (navMarkers[k]) { map.removeLayer(navMarkers[k]); navMarkers[k] = null; }
  }
}

function renderNavigation(data) {
  clearNavLayers();
  const coords = (data.coordinates && data.coordinates.length > 1) ? data.coordinates : null;
  if (coords) {
    const pts = coords.map(c => mc2latlng(c[0], c[1]));
    navigationLayer = L.polyline(pts, {
      color: '#007AFF', weight: 5, opacity: 0.9, lineCap: 'round', lineJoin: 'round'
    }).addTo(map);
  }
  const dest = data.destination;
  if (dest) {
    navMarkers.end = L.circleMarker(mc2latlng(dest.x, dest.z), {
      radius: 7, color: '#fff', weight: 3, fillColor: '#FF3B30', fillOpacity: 1
    }).addTo(map);
  }
  if (coords) {
    navMarkers.start = L.circleMarker(mc2latlng(coords[0][0], coords[0][1]), {
      radius: 6, color: '#fff', weight: 3, fillColor: '#34C759', fillOpacity: 1
    }).addTo(map);
  }
  if (typeof data.playerX === 'number' && typeof data.playerZ === 'number') {
    navMarkers.player = L.circleMarker(mc2latlng(data.playerX, data.playerZ), {
      radius: 5, color: '#fff', weight: 2, fillColor: '#007AFF', fillOpacity: 0.95
    }).addTo(map);
  }
}

function updateNavPanel(data) {
  const panel = document.getElementById('nav-panel');
  if (!navActive) { panel.classList.remove('visible'); return; }
  panel.classList.add('visible');

  const statusEl = document.getElementById('nav-status');
  if (data.state === 'ARRIVED') { statusEl.textContent = I18N.t('nav.arrived'); statusEl.style.color = '#34c759'; }
  else if (data.offRoute) { statusEl.textContent = I18N.t('nav.offRoute'); statusEl.style.color = 'var(--red)'; }
  else { statusEl.textContent = ''; statusEl.style.color = ''; }

  const rem = data.remainingDistance || 0;
  document.getElementById('nav-remaining').innerHTML =
    formatDistance(rem) + ' <small>' + I18N.t('nav.meters') + '</small>';
  document.getElementById('nav-eta').textContent = formatDuration(data.remainingTime || 0);

  const turnIco = document.getElementById('nav-turn-ico');
  const turnTxt = document.getElementById('nav-turn-txt');
  const turnDist = document.getElementById('nav-turn-dist');
  if (data.nextTurn) {
    const m = {
      LEFT: ['fa-arrow-left', 'nav.turnLeft'],
      RIGHT: ['fa-arrow-right', 'nav.turnRight'],
      UTURN: ['fa-arrow-rotate-left', 'nav.uturn'],
      STRAIGHT: ['fa-arrow-up', 'nav.straight']
    }[data.nextTurn.type] || ['fa-arrow-up', 'nav.straight'];
    turnIco.innerHTML = '<i class="fa-solid ' + m[0] + '"></i>';
    turnTxt.textContent = I18N.t(m[1]);
    turnDist.textContent = formatDistance(data.nextTurn.distance) + ' ' + I18N.t('nav.meters');
  } else {
    turnIco.innerHTML = '<i class="fa-solid fa-arrow-up"></i>';
    turnTxt.textContent = I18N.t('nav.straight');
    turnDist.textContent = '';
  }

  if (data.destination) {
    document.getElementById('nav-dest-coords').textContent =
      Math.round(data.destination.x) + ', ' + Math.round(data.destination.z);
  }

  let pct = 0;
  if (data.coordinates && data.coordinates.length > 1) {
    let totalLen = 0;
    for (let i = 1; i < data.coordinates.length; i++) {
      const dx = data.coordinates[i][0] - data.coordinates[i - 1][0];
      const dz = data.coordinates[i][1] - data.coordinates[i - 1][1];
      totalLen += Math.hypot(dx, dz);
    }
    if (totalLen > 0) pct = Math.max(0, Math.min(1, 1 - rem / totalLen));
  }
  document.getElementById('nav-progress-bar').style.width = (pct * 100).toFixed(1) + '%';

  document.querySelectorAll('#nav-mode button').forEach(b => {
    b.classList.toggle('active', b.dataset.mode === navMode);
  });
}

function showNavPanel() { document.getElementById('nav-panel').classList.add('visible'); }
function hideNavPanel() { document.getElementById('nav-panel').classList.remove('visible'); }

function formatDistance(m) {
  m = Math.max(0, Math.round(m));
  if (m >= 1000) return (m / 1000).toFixed(1) + 'k';
  return String(m);
}
function formatDuration(sec) {
  sec = Math.max(0, Math.round(sec));
  if (sec < 60) return sec + 's';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) return s ? (m + 'm' + s + 's') : (m + 'm');
  const h = Math.floor(m / 60);
  return (h + 'h' + (m % 60) + 'm');
}

async function refreshNavigation() {
  try {
    const res = await fetch('/api/nav/state');
    const data = await res.json();
    if (data.state === 'IDLE') {
      if (navActive) { navActive = false; clearNavLayers(); hideNavPanel(); }
      return;
    }
    navActive = true;
    renderNavigation(data);
    updateNavPanel(data);
  } catch (e) { /* server may be restarting */ }
}

function cancelNavigation() {
  fetch('/api/nav/stop', { method: 'POST' }).then(() => {
    navActive = false; clearNavLayers(); hideNavPanel();
  }).catch(() => {});
  clearPlannedRoute();
  hideRouteSummary();
  closePointInfo();
}

function onNodeClick(nid, event) {
  if (appMode !== 'edit') { clearSelection(); return; }
  if (event.ctrlKey || event.metaKey) return;
  clearSelection();
  selectNode(nid);
}

function onSegmentClick(sid, event) {
  if (appMode !== 'edit') { clearSelection(); return; }
  if (event.ctrlKey || event.metaKey) {
    if (selectedSegments.has(sid)) selectedSegments.delete(sid);
    else selectedSegments.add(sid);
    renderAll();
    updateMergeButton();
    showSegmentEditor(sid);
    return;
  }
  clearSelection();
  selectedSegments.add(sid);
  renderAll();
  updateMergeButton();
  showSegmentEditor(sid);
}

function onNodeDragEnd(nid, marker) {
  const latlng = marker.getLatLng();
  const x = latlng.lng * SCALE;
  const z = latlng.lat * SCALE;
  const node = roadStore.nodes[nid];
  if (!node) return;
  pushUndo();
  fetch('/api/nodes/' + nid, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ x, z, expectedVersion: node.version })
  }).then(r => {
    if (r.status === 409) { showToast(I18N.t('toast.versionConflict') + ' ' + I18N.t('toast.refreshing'), 'error'); loadData(); }
    else if (r.ok) return r.json().then(updated => {
      roadStore.nodes[nid] = updated;
      renderAll();
    });
    else showToast(I18N.t('toast.saveFailed'), 'error');
  }).catch(() => showToast(I18N.t('toast.networkError'), 'error'));
}

function selectNode(nid) {
  selectedNodeId = nid;
  const node = roadStore.nodes[nid];
  if (!node) return;
  showEditor('node-editor');
  document.getElementById('node-id').textContent = nid.substring(0, 4) + '...' + nid.substring(nid.length - 4);
  document.getElementById('node-x').value = node.x;
  document.getElementById('node-z').value = node.z;
  document.getElementById('node-source').textContent = node.source;
}

function showSegmentEditor(sid) {
  selectedSegmentId = sid;
  const seg = roadStore.segments[sid];
  if (!seg) return;
  showEditor('segment-editor');
  document.getElementById('seg-id').textContent = sid.substring(0, 4) + '...' + sid.substring(sid.length - 4);
  document.getElementById('seg-source').textContent = seg.source;
  document.getElementById('seg-status').textContent = seg.status;
  document.getElementById('seg-direction').value = seg.direction || 'BIDIRECTIONAL';

  const road = seg.roadId ? roadStore.roads[seg.roadId] : null;
  document.getElementById('seg-road-name').value = road ? (road.name || '') : '';
  document.getElementById('seg-classification').value = road ? (road.classification || '') : '';
  document.getElementById('seg-number').value = road ? (road.number || '') : '';
}

function clearSelection() {
  selectedSegments.clear();
  selectedNodeId = null;
  selectedSegmentId = null;
  document.getElementById('merge-btn').style.display = 'none';
  hideEditor();
  renderAll();
}

function updateMergeButton() {
  const btn = document.getElementById('merge-btn');
  btn.style.display = selectedSegments.size >= 2 ? '' : 'none';
  const bar = document.getElementById('batch-bar');
  const delLabel = document.getElementById('batch-del-label');
  if (selectedSegments.size >= 1) {
    delLabel.textContent = I18N.t('edit.deleteSelected', { n: selectedSegments.size });
    bar.classList.add('visible');
  } else {
    bar.classList.remove('visible');
  }
}

// ——— Actions ———

async function deleteNode() {
  const nid = selectedNodeId;
  if (!nid) return;
  showSheet(I18N.t('sheet.deleteNode.title'), I18N.t('sheet.deleteNode.message'), [
    { label: I18N.t('sheet.deleteNode.cancel'), role: 'cancel' },
    { label: I18N.t('sheet.deleteNode.confirm'), role: 'destructive', action: async () => {
        pushUndo();
        const res = await fetch('/api/nodes/' + nid, { method: 'DELETE' });
        if (res.ok) { clearSelection(); loadData(); showToast(I18N.t('toast.nodeDeleted')); }
        else showToast(I18N.t('toast.deleteFailed'), 'error');
      }
    }
  ]);
}

async function deleteSegment() {
  const sid = selectedSegmentId;
  if (!sid) return;
  showSheet(I18N.t('sheet.deleteSegment.title'), I18N.t('sheet.deleteSegment.message'), [
    { label: I18N.t('sheet.deleteNode.cancel'), role: 'cancel' },
    { label: I18N.t('sheet.deleteNode.confirm'), role: 'destructive', action: async () => {
        pushUndo();
        const res = await fetch('/api/segments/' + sid, { method: 'DELETE' });
        if (res.ok) { clearSelection(); loadData(); showToast(I18N.t('toast.segmentDeleted')); }
        else showToast(I18N.t('toast.deleteFailed'), 'error');
      }
    }
  ]);
}

async function saveRoad() {
  const sid = selectedSegmentId;
  if (!sid) return;
  const seg = roadStore.segments[sid];
  const name = document.getElementById('seg-road-name').value;
  const classification = document.getElementById('seg-classification').value;
  const number = document.getElementById('seg-number').value;
  const direction = document.getElementById('seg-direction').value;

  // Save segment direction
  if (seg.direction !== direction) {
    pushUndo();
    editingEntityId = sid;
    try {
      const res = await fetch('/api/segments/' + sid + '/direction', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ direction, expectedVersion: seg.version || 0 })
      });
      if (res.ok) {
        seg.direction = direction;
      }
    } catch (e) { showToast(I18N.t('toast.networkError'), 'error'); }
    finally {
      editingEntityId = null;
    }
  }

  const roadId = seg.roadId;
  if (roadId) {
    const road = roadStore.roads[roadId];
    editingEntityId = roadId;
    try {
      pushUndo();
      const res = await fetch('/api/roads/' + roadId, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, classification, number, expectedVersion: road ? road.version : 0 })
      });
      if (res.status === 409) {
        const errData = await res.json();
        showToast(I18N.t('toast.versionConflict') + ': ' + (errData.error || I18N.t('toast.gameModified')), 'error');
        await loadData();
        return;
      }
      if (res.ok) {
        const updated = await res.json();
        roadStore.roads[roadId] = updated;
        renderAll();
        showToast(I18N.t('toast.roadSaved'));
      }
    } catch (e) { showToast(I18N.t('toast.networkError'), 'error'); }
    finally {
      editingEntityId = null;
    }
  } else {
    // Still render direction change even if no road linked
    renderAll();
    showToast(I18N.t('toast.segmentNotLinked'), 'error');
  }
}

async function mergeSegments() {
  if (selectedSegments.size < 2) return;
  pushUndo();
  const res = await fetch('/api/merge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ segmentIds: [...selectedSegments] })
  });
  if (res.ok) { clearSelection(); loadData(); showToast(I18N.t('toast.segmentMerged')); }
  else showToast(I18N.t('toast.mergeFailed'), 'error');
}

// ——— Part C3: batch delete ———
async function deleteSelectedSegments() {
  if (selectedSegments.size === 0) return;
  const ids = [...selectedSegments];
  showSheet(I18N.t('sheet.deleteSegment.title'), I18N.t('sheet.deleteSegment.message'), [
    { label: I18N.t('sheet.deleteNode.cancel'), role: 'cancel' },
    { label: I18N.t('sheet.deleteNode.confirm'), role: 'destructive', action: async () => {
        pushUndo();
        for (const sid of ids) {
          await fetch('/api/segments/' + sid, { method: 'DELETE' });
        }
        clearSelection(); loadData();
        showToast(I18N.t('toast.segmentDeleted'));
      } }
  ]);
}

// ——— Toolbar ———
function initToolbar() {
  document.getElementById('tool-move').addEventListener('click', () => toggleTool('move'));
  document.getElementById('tool-move').addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    const popup = document.getElementById('constrain-popup');
    const moveBtn = document.getElementById('tool-move');
    const toolbar = document.getElementById('toolbar');
    const moveRect = moveBtn.getBoundingClientRect();
    const tbRect = toolbar.getBoundingClientRect();
    popup.style.top = (moveRect.top - tbRect.top) + 'px';
    popup.classList.toggle('visible');
  });
  document.getElementById('constrain-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    constrainDrag = !constrainDrag;
    const btn = document.getElementById('constrain-btn');
    if (constrainDrag) {
      btn.classList.add('active');
      btn.title = I18N.t('toolbar.constrain.extend');
    } else {
      btn.classList.remove('active');
      btn.title = I18N.t('toolbar.constrain.free');
    }
    document.getElementById('constrain-popup').classList.remove('visible');
  });
  // Close popup on any outside click
  document.addEventListener('click', (e) => {
    const popup = document.getElementById('constrain-popup');
    if (!popup.classList.contains('visible')) return;
    if (!popup.contains(e.target) && e.target.id !== 'tool-move' && !document.getElementById('tool-move').contains(e.target)) {
      popup.classList.remove('visible');
    }
  });
  document.getElementById('tool-point').addEventListener('click', () => toggleTool('point'));
  document.getElementById('tool-merge').addEventListener('click', () => toggleTool('merge'));
  document.getElementById('tool-fenhe').addEventListener('click', () => toggleTool('fenhe'));
  document.getElementById('tool-softdelete').addEventListener('click', () => toggleTool('softdelete'));
  document.getElementById('tool-merge-nearby').addEventListener('click', mergeNearbyNodes);
  // 导航模式：「目的地」按钮 → 以地图中心弹信息卡（主要流程是直接在地图上点任意处）
  document.getElementById('tool-navigation').addEventListener('click', () => {
    openPointInfo(map.getCenter());
  });
  // 路线预览卡：开始导航 / 取消
  document.getElementById('rs-start').addEventListener('click', () => {
    if (plannedDest) startNavigation(plannedDest.x, plannedDest.z);
  });
  document.getElementById('rs-cancel').addEventListener('click', cancelNavigation);
  // 「到这去」按钮在 Leaflet 弹窗内动态重建，用委托监听
  document.addEventListener('click', (e) => {
    if (e.target && e.target.closest && e.target.closest('#pi-go-here') && pendingPoint) {
      planRoute(pendingPoint.x, pendingPoint.z);
    }
  });
  document.getElementById('nav-cancel').addEventListener('click', cancelNavigation);
  document.querySelectorAll('#nav-mode button').forEach(b => {
    b.addEventListener('click', () => { navMode = b.dataset.mode; if (navActive) updateNavPanel({}); });
  });
  document.getElementById('tool-navigation-stop').addEventListener('click', cancelNavigation);
  document.getElementById('batch-delete').addEventListener('click', deleteSelectedSegments);
  document.getElementById('tool-undo').addEventListener('click', undo);
  document.getElementById('tool-redo').addEventListener('click', redo);
  document.getElementById('tool-mode-toggle').addEventListener('click', toggleToolbarMode);
  undoButtonStyle();
}

function toggleTool(tool) {
  if (activeTool === tool) {
    setActiveTool(null);
  } else {
    setActiveTool(tool);
  }
}

function setActiveTool(tool) {
  activeTool = tool;
  mergeFirstNodeId = null;
  // Cancel any in-progress drag
  if (dragState) {
    dragState.marker.setStyle({ fillOpacity: 0.92, radius: 5 });
    dragState.marker._path.style.cursor = 'grab';
    dragState = null;
  }
  document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('selected'));
  if (tool) {
    document.getElementById('tool-' + tool).classList.add('selected');
  }
  // Disable map dragging in move/merge so it doesn't conflict
  if (tool === 'move' || tool === 'merge' || tool === 'fenhe' || tool === 'softdelete') {
    map.dragging.disable();
  } else {
    map.dragging.enable();
  }
  renderAll();
}

// ——— App mode (navigation vs edit) ———
// The mode gate controls whether editing tools / editor panel are reachable.
// Exiting edit mode into navigation mode MUST deselect every active tool, otherwise a
// leftover tool (e.g. 'point') would still fire when the user clicks a node/segment
// while they only meant to navigate.
function enterMode(mode) {
  appMode = mode;
  const tb = document.getElementById('toolbar');
  tb.classList.remove('mode-nav', 'mode-edit');
  tb.classList.add(mode === 'edit' ? 'mode-edit' : 'mode-nav');

  if (mode === 'navigation') {
    setActiveTool(null);   // 关键修复：退出编辑时取消所有工具选择
    clearSelection();
  }
  updateModeSwitch();
  try { if (map) map.invalidateSize(); } catch (e) {}
}

function toggleMode() {
  enterMode(appMode === 'edit' ? 'navigation' : 'edit');
}

function updateModeSwitch() {
  const pill = document.getElementById('mode-switch');
  const ico = document.getElementById('mode-switch-ico');
  const label = document.getElementById('mode-switch-label');
  if (!pill) return;
  pill.classList.remove('hidden');
  // Pill always shows the mode you would switch INTO (the current action).
  if (appMode === 'edit') {
    ico.className = 'fa-solid fa-route ms-ico';
    label.textContent = I18N.t('mode.nav.title');
    pill.title = I18N.t('mode.switchToNav');
  } else {
    ico.className = 'fa-solid fa-pen-to-square ms-ico';
    label.textContent = I18N.t('mode.edit.title');
    pill.title = I18N.t('mode.switchToEdit');
  }
}

function initModeUI() {
  document.getElementById('mode-switch').addEventListener('click', toggleMode);
}

function toggleToolbarMode() {
  const tb = document.getElementById('toolbar');
  const mapEl = document.getElementById('map');
  const toggleLabel = document.querySelector('#tool-mode-toggle .tool-label');
  if (toolbarMode === 'compact') {
    toolbarMode = 'detailed';
    tb.classList.remove('toolbar-compact');
    tb.classList.add('toolbar-detailed');
    mapEl.style.left = '148px';
    if (toggleLabel) toggleLabel.textContent = I18N.t('toolbar.contract.label');
  } else {
    toolbarMode = 'compact';
    tb.classList.remove('toolbar-detailed');
    tb.classList.add('toolbar-compact');
    mapEl.style.left = '44px';
    if (toggleLabel) toggleLabel.textContent = I18N.t('toolbar.expand.label');
  }
  if (map) { map.invalidateSize(); }
}

// ——— Point tool ———
function handlePointTool(latlng) {
  // 1. Check for intersection snap
  const inter = findNearestIntersection(latlng);
  if (inter) {
    insertNodeAtIntersection(inter);
    return;
  }
  // 2. Fall back to segment snap
  const hit = findNearestSegment(latlng, TOOL_TOLERANCE_PX);
  if (!hit) {
    showToolToast(I18N.t('toast.noSegmentNearby'));
    return;
  }
  insertNodeOnSegment(hit.segmentId, hit.insertIndex, latlng);
}

function findNearestSegment(latlng, tolerancePx) {
  const clickPt = map.latLngToContainerPoint(latlng);
  let best = null;
  let bestDist = tolerancePx;

  for (const [sid, line] of segmentLines) {
    const latlngs = line.getLatLngs();
    if (!latlngs || latlngs.length < 2) continue;
    for (let i = 0; i < latlngs.length - 1; i++) {
      const p1 = map.latLngToContainerPoint(latlngs[i]);
      const p2 = map.latLngToContainerPoint(latlngs[i + 1]);
      const info = pointToSegmentInfo(clickPt, p1, p2);
      if (info.dist < bestDist) {
        bestDist = info.dist;
        best = { segmentId: sid, insertIndex: i + 1 };
      }
    }
  }
  return best;
}

function pointToSegmentInfo(p, p1, p2) {
  const dx = p2.x - p1.x;
  const dy = p2.y - p1.y;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return { dist: p.distanceTo(p1), t: 0 };
  let t = ((p.x - p1.x) * dx + (p.y - p1.y) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  const proj = L.point(p1.x + t * dx, p1.y + t * dy);
  return { dist: p.distanceTo(proj), t };
}

// ——— Intersection math (MC coordinate space) ———
function lineIntersection(x1, z1, x2, z2, x3, z3, x4, z4) {
  const denom = (x1 - x2) * (z3 - z4) - (z1 - z2) * (x3 - x4);
  if (Math.abs(denom) < 1e-10) return null;
  const t = ((x1 - x3) * (z3 - z4) - (z1 - z3) * (x3 - x4)) / denom;
  const u = -((x1 - x2) * (z1 - z3) - (z1 - z2) * (x1 - x3)) / denom;
  if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
    return { x: x1 + t * (x2 - x1), z: z1 + t * (z2 - z1) };
  }
  return null;
}

function findNearestIntersection(latlng) {
  const clickPt = map.latLngToContainerPoint(latlng);
  let best = null;
  let bestDist = INTERSECTION_SNAP_PX;
  const segs = Object.entries(roadStore.segments);
  for (let i = 0; i < segs.length; i++) {
    const [sidA, segA] = segs[i];
    if (!segA.nodeIds || segA.nodeIds.length < 2) continue;
    for (let j = i + 1; j < segs.length; j++) {
      const [sidB, segB] = segs[j];
      if (!segB.nodeIds || segB.nodeIds.length < 2) continue;
      for (let ai = 0; ai < segA.nodeIds.length - 1; ai++) {
        const na1 = roadStore.nodes[segA.nodeIds[ai]];
        const na2 = roadStore.nodes[segA.nodeIds[ai + 1]];
        if (!na1 || !na2) continue;
        for (let bi = 0; bi < segB.nodeIds.length - 1; bi++) {
          const nb1 = roadStore.nodes[segB.nodeIds[bi]];
          const nb2 = roadStore.nodes[segB.nodeIds[bi + 1]];
          if (!nb1 || !nb2) continue;
          const pt = lineIntersection(na1.x, na1.z, na2.x, na2.z, nb1.x, nb1.z, nb2.x, nb2.z);
          if (!pt) continue;
          const intPt = map.latLngToContainerPoint(L.latLng(pt.z / SCALE, pt.x / SCALE));
          const dist = clickPt.distanceTo(intPt);
          if (dist < bestDist) {
            bestDist = dist;
            best = {
              x: pt.x, z: pt.z,
              segmentIdA: sidA, insertIndexA: ai + 1,
              segmentIdB: sidB, insertIndexB: bi + 1
            };
          }
        }
      }
    }
  }
  return best;
}

async function insertNodeOnSegment(segId, insertIndex, latlng) {
  const seg = roadStore.segments[segId];
  if (!seg) return;
  const x = latlng.lng * SCALE;
  const z = latlng.lat * SCALE;
  try {
    pushUndo();
    const res = await fetch('/api/segments/' + segId + '/insert', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, z, insertIndex, expectedVersion: seg.version })
    });
    if (res.status === 409) {
      showToast(I18N.t('toast.versionConflict') + ' ' + I18N.t('toast.refreshing'), 'error');
      loadData();
      return;
    }
    if (!res.ok) {
      showToast(I18N.t('toast.insertFailed'), 'error');
      return;
    }
    clearSelection();
    loadData();
    showToast(I18N.t('toast.nodeInserted'));
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

async function insertNodeAtIntersection(data) {
  const segA = roadStore.segments[data.segmentIdA];
  const segB = roadStore.segments[data.segmentIdB];
  if (!segA || !segB) return;
  try {
    pushUndo();
    const res = await fetch('/api/segments/intersection', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        x: data.x,
        z: data.z,
        segmentIdA: data.segmentIdA,
        insertIndexA: data.insertIndexA,
        segmentIdB: data.segmentIdB,
        insertIndexB: data.insertIndexB,
        expectedVersionA: segA.version,
        expectedVersionB: segB.version
      })
    });
    if (res.status === 409) {
      showToast(I18N.t('toast.versionConflict') + ' ' + I18N.t('toast.refreshing'), 'error');
      loadData();
      return;
    }
    if (!res.ok) {
      showToast(I18N.t('toast.intersectionInsertFailed'), 'error');
      return;
    }
    clearSelection();
    loadData();
    showToolToast(I18N.t('toast.nodeInsertedIntersection'));
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

function getDegree(nid) {
  const neighbors = new Set();
  for (const seg of Object.values(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const idx = seg.nodeIds.indexOf(nid);
    if (idx === -1) continue;
    if (idx > 0) neighbors.add(seg.nodeIds[idx - 1]);
    if (idx < seg.nodeIds.length - 1) neighbors.add(seg.nodeIds[idx + 1]);
  }
  return neighbors.size;
}

function detectMergeSpecialCase(nid1, nid2) {
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const i1 = seg.nodeIds.indexOf(nid1);
    const i2 = seg.nodeIds.indexOf(nid2);
    if (i1 === -1 || i2 === -1) continue;
    const dist = Math.abs(i1 - i2);
    if (dist === 1) {
      return { type: 'adjacent', segmentId: sid, segmentOnlyTwoNodes: seg.nodeIds.length === 2 };
    }
    if (dist > 1) {
      const minI = Math.min(i1, i2);
      const maxI = Math.max(i1, i2);
      return { type: 'separated', segmentId: sid, intermediateNodes: seg.nodeIds.slice(minI + 1, maxI) };
    }
  }
  return null;
}

async function handleMergeTool(nid) {
  if (!mergeFirstNodeId) {
    mergeFirstNodeId = nid;
    renderAll();
    showToolToast(I18N.t('toast.selectedNode', { id: nid.substring(nid.length - 4) }));
    return;
  }

  if (nid === mergeFirstNodeId) {
    mergeFirstNodeId = null;
    renderAll();
    showToolToast(I18N.t('toast.cancelledSelection'));
    return;
  }

  const specialCase = detectMergeSpecialCase(mergeFirstNodeId, nid);

  if (specialCase && specialCase.type === 'separated') {
    const allDegree2 = specialCase.intermediateNodes.every(mid => getDegree(mid) === 2);
    if (!allDegree2) {
      showToolToast(I18N.t('toast.cannotMerge'));
      mergeFirstNodeId = null;
      renderAll();
      return;
    }
    showSheet(I18N.t('sheet.mergeNode.title'), I18N.t('sheet.mergeNode.message'), [
      {
        label: I18N.t('sheet.mergeNode.cancel'), role: 'cancel', action: () => { mergeFirstNodeId = null; renderAll(); }
      },
      {
        label: I18N.t('sheet.mergeNode.confirm'), role: 'destructive', action: async () => {
          await doMergeClean(mergeFirstNodeId, nid, specialCase);
        }
      }
    ]);
    return;
  }

  if (specialCase && specialCase.type === 'adjacent' && specialCase.segmentOnlyTwoNodes) {
    pushUndo();
    await fetch(`/api/segments/${specialCase.segmentId}`, { method: 'DELETE' });
  } else {
    pushUndo();
  }

  await doMerge(mergeFirstNodeId, nid);
}

async function doMerge(nodeToDeleteId, targetNodeId) {
  try {
    const res = await fetch('/api/nodes/merge', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeToDeleteId, targetNodeId })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(I18N.t('toast.mergeFailed') + ': ' + (err.error || res.status), 'error');
      return;
    }
    clearSelection();
    mergeFirstNodeId = null;
    loadData();
    showToolToast(I18N.t('toast.nodeMerged'));
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

async function doMergeClean(nodeToDeleteId, targetNodeId, specialCase) {
  pushUndo();
  try {
    const res = await fetch('/api/nodes/merge-clean', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nodeToDeleteId,
        targetNodeId,
        segmentId: specialCase.segmentId,
        intermediateNodeIds: specialCase.intermediateNodes
      })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast(I18N.t('toast.mergeFailed') + ': ' + (err.error || res.status), 'error');
      return;
    }
    clearSelection();
    mergeFirstNodeId = null;
    loadData();
    showToolToast(I18N.t('toast.nodeMerged'));
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

async function handleSoftDeleteTool(nid) {
  pushUndo();
  try {
    const res = await fetch('/api/nodes/soft-delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId: nid })
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      const msg = data.message || data.error || I18N.t('toast.unknownError');
      showToolToast(msg);
      return;
    }
    clearSelection();
    loadData();
    const label = data.action === 'endpoint_shortened' ? I18N.t('toast.endpointSoftDeleted') : I18N.t('toast.nodeSoftDeleted');
    showToolToast(label);
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

// ——— 一键合并重合节点 + 连通块检查 ———
async function mergeNearbyNodes() {
  if (appMode !== 'edit') return;
  showToast(I18N.t('toolbar.mergeNearby.working'));
  try {
    const res = await fetch('/api/nodes/merge-nearby', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ threshold: 1.0 })
    });
    if (!res.ok) {
      showToast(I18N.t('toast.networkError'), 'error');
      return;
    }
    const data = await res.json();
    loadData();
    showToast(I18N.t('toolbar.mergeNearby.result', {
      merged: data.mergedNodes,
      components: data.components,
      isolated: data.isolated,
      nodes: data.nodeCount
    }), data.mergedNodes > 0 ? '' : 'info');
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}

// ——— 分合 tool ———
function findRoadForSegment(segId) {
  for (const [rid, road] of Object.entries(roadStore.roads)) {
    if (road.segmentIds && road.segmentIds.includes(segId)) return road;
  }
  return null;
}

async function handleFenHeTool(nid) {
  // 1. Try split: node is interior of a segment
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const idx = seg.nodeIds.indexOf(nid);
    if (idx >= 1 && idx < seg.nodeIds.length - 1) {
      pushUndo();
      const res = await fetch('/api/split/' + seg.id, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodeIndex: idx, expectedVersion: seg.version })
      });
      if (res.ok) {
        clearSelection();
        await loadData();
        showToolToast(I18N.t('toast.splitSuccess'));
      } else {
        showToolToast(I18N.t('toast.splitFailed'));
      }
      return;
    }
  }

  if (getDegree(nid) !== 2) {
    showToolToast(I18N.t('toast.cannotUseTool'));
    return;
  }

  const endpointSegs = [];
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds || seg.nodeIds.length < 2) continue;
    if (seg.nodeIds[0] === nid || seg.nodeIds[seg.nodeIds.length - 1] === nid) {
      endpointSegs.push(seg);
    }
  }

  if (endpointSegs.length !== 2) {
    showToolToast(I18N.t('toast.cannotUseTool'));
    return;
  }

  const road1 = findRoadForSegment(endpointSegs[0].id);
  const road2 = findRoadForSegment(endpointSegs[1].id);
  if (road1 && road2 && road1.id !== road2.id) {
    showToolToast(I18N.t('toast.differentRoads'));
    return;
  }

  pushUndo();
  try {
    const res = await fetch('/api/nodes/merge-segments', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId: nid })
    });
    if (res.ok) {
      clearSelection();
      await loadData();
      showToolToast(I18N.t('toast.mergeSuccess'), 'green');
    } else {
      const err = await res.json();
      showToolToast(I18N.t('toast.mergeFailed') + ': ' + (err.error || ''));
    }
  } catch (e) {
    showToast(I18N.t('toast.networkError'), 'error');
  }
}
window.addEventListener('load', () => {
  I18N.init();
  initToolbar();
  initMap();
  initModeUI();

  // 默认进入即导航视图：直接看到道路地图（类似导航软件），编辑工具隐藏。
  // 想编辑时点顶部「编辑」胶囊即可切换；退出编辑会自动取消所有工具（见 enterMode）。
  enterMode('navigation');

  // Language change handler
  document.getElementById('tool-language').addEventListener('click', () => {
    I18N.toggleLanguage();
  });

  document.addEventListener('languagechange', () => {
    updateDynamicI18n();
  });
});

function updateDynamicI18n() {
  // Re-apply constraint button title
  const constrainBtn = document.getElementById('constrain-btn');
  if (constrainBtn) {
    constrainBtn.title = constrainDrag ? 
      I18N.t('toolbar.constrain.extend') : 
      I18N.t('toolbar.constrain.free');
  }
  
  // Re-apply toolbar toggle label based on current mode
  const modeToggleLabel = document.querySelector('#tool-mode-toggle .tool-label');
  if (modeToggleLabel) {
    modeToggleLabel.textContent = toolbarMode === 'compact' ? 
      I18N.t('toolbar.expand.label') : 
      I18N.t('toolbar.contract.label');
  }
  
  // Re-apply all data-i18n attributes
  I18N.applyToDOM();

  // Mode switch pill label/icon are set imperatively — refresh them too.
  updateModeSwitch();
}

document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;
  if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key.toLowerCase() === 'z') {
    e.preventDefault();
    redo();
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault();
    undo();
  }
});
