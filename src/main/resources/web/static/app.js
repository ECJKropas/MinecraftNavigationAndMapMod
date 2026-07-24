// Wayfarer Road Editor — Apple-style frontend
// MC coords (X, Z) map to Leaflet [lat=Z/128, lng=X/128]

const SCALE = 128.0;
let map, selectedSegments = new Set(), selectedNodeId = null, selectedSegmentId = null;
let roadStore = { nodes:{}, segments:{}, roads:{} };
let activeTool = null;          // 'move' | 'point' | 'merge' | null
let toolbarMode = 'compact';   // 'compact' | 'detailed'
let mergeFirstNodeId = null;   // first node selected in merge tool
const TOOL_TOLERANCE_PX = 12;  // pixel tolerance for point tool segment detection
const INTERSECTION_SNAP_PX = 15;  // pixel tolerance for intersection snapping in point tool

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
  if (undoStack.length === 0) return;
  redoStack.push(snapshotStore());
  const snap = undoStack.pop();
  await fetch('/api/roads/restore', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: snap
  });
  clearSelection();
  mergeFirstNodeId = null;
  await loadData();
  undoButtonStyle();
}

async function redo() {
  if (redoStack.length === 0) return;
  undoStack.push(snapshotStore());
  const snap = redoStack.pop();
  await fetch('/api/roads/restore', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: snap
  });
  clearSelection();
  mergeFirstNodeId = null;
  await loadData();
  undoButtonStyle();
}

function undoButtonStyle() {
  const ub = document.getElementById('tool-undo');
  const rb = document.getElementById('tool-redo');
  if (ub) ub.style.opacity = undoStack.length === 0 ? '0.35' : '';
  if (rb) rb.style.opacity = redoStack.length === 0 ? '0.35' : '';
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
function showToolToast(msg) {
  const el = document.getElementById('tool-toast');
  el.textContent = msg;
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
function initMap() {
  map = L.map('map', {
    crs: L.CRS.Simple,
    minZoom: -4,
    maxZoom: 20,
    zoomControl: true
  }).setView([0, 0], 5);
  map.on('click', onMapClick);
  loadConfig();
  loadData();
  setInterval(loadDelta, 2000);
}

async function loadConfig() {
  try {
    const res = await fetch('/api/config');
    if (!res.ok) return;
    const cfg = await res.json();
    if (cfg.maxZoom && typeof cfg.maxZoom === 'number') {
      map.options.maxZoom = cfg.maxZoom;
    }
  } catch (e) { /* use defaults */ }
}

function mc2latlng(x, z) { return [z / SCALE, x / SCALE]; }

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
  } catch (e) { showToast('加载数据失败: ' + e.message, 'error'); }
}

async function loadDelta() {
  try {
    const since = Math.floor(Date.now() - 5000);
    const res = await fetch('/api/roads/delta?since=' + since);
    const data = await res.json();
    let changed = false;
    if (data.nodes && data.nodes.length > 0) {
      for (const n of data.nodes) { roadStore.nodes[n.id] = n; }
      changed = true;
    }
    if (data.segments && data.segments.length > 0) {
      // Always updates but skip renderAll unless node count changed
      // (segment/road metadata changes don't affect polyline geometry)
      const oldSegCount = Object.keys(roadStore.segments).length;
      for (const s of data.segments) { roadStore.segments[s.id] = s; }
      const newSegCount = Object.keys(roadStore.segments).length;
      if (newSegCount !== oldSegCount) changed = true;
    }
    if (data.roads) {
      const oldRoadCount = Object.keys(roadStore.roads).length;
      for (const r of data.roads) { roadStore.roads[r.id] = r; }
      const newRoadCount = Object.keys(roadStore.roads).length;
      if (newRoadCount !== oldRoadCount) changed = true;
    }
    if (changed) renderAll();
  } catch (e) { /* silent */ }
}

// ——— Road styling ———
const ROAD_STYLES = {
  G: { lineColor: '#DD3800', lineWeight: 3.5, badgeBg: '#DD0000', badgeBorder: '#FFFFFF', badgeColor: '#FFFFFF' },
  S: { lineColor: '#E89200', lineWeight: 3, badgeBg: '#FFD700', badgeColor: '#000000' },
};

let nodeMarkers = new Map();
let segmentLines = new Map();
let segmentFills = new Map();  // fill polylines for dual-layer roads

function renderAll() {
  nodeMarkers.forEach(m => map.removeLayer(m));
  segmentLines.forEach(l => map.removeLayer(l));
  segmentFills.forEach(l => map.removeLayer(l));
  nodeMarkers.clear();
  segmentLines.clear();
  segmentFills.clear();

  // Clear label markers
  if (!window._roadLabels) window._roadLabels = new Set();
  window._roadLabels.forEach(l => map.removeLayer(l));
  window._roadLabels.clear();

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
    const pts = buildPoints(seg);
    if (pts.length < 2) continue;
    const isSelected = selectedSegments.has(sid);
    const eo = { color: '#BBBBBB', weight: isSelected ? 3.5 : 2.5, opacity: isSelected ? 1 : 0.5, smoothFactor: 0.2 };
    const fo = { color: '#F8F8F8', weight: isSelected ? 2 : 1.5, opacity: isSelected ? 1 : 0.92, smoothFactor: 0.2 };
    const edge = L.polyline(pts, eo).addTo(map);
    const fill = L.polyline(pts, fo).addTo(map);
    [edge, fill].forEach(line => {
      line.on('mouseover', () => { if (!isSelected) { edge.setStyle({ weight: 3.5, opacity: 0.7 }); fill.setStyle({ weight: 2.5 }); } });
      line.on('mouseout', () => { if (!isSelected) { edge.setStyle(eo); fill.setStyle(fo); } });
      line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
    });
    segmentLines.set(sid, edge);
    segmentFills.set(sid, fill);
  }

  // Render road groups + labels
  for (const [rid, group] of Object.entries(roadGroups)) {
    const road = group.road;
    const raw = road.classification || '';
    const cls = raw.length > 1 && /^[GSXYC]/.test(raw) ? raw.charAt(0) : raw;
    const num = road.number || '';
    const styl = ROAD_STYLES[cls] || null;
    const isGorS = styl != null;

    const allPts = [];

    for (const { id: sid, seg } of group.items) {
      const pts = buildPoints(seg);
      if (pts.length < 2) continue;
      const mid = pts[Math.floor(pts.length / 2)];
      if (mid && isFinite(mid.lat) && isFinite(mid.lng)) {
        allPts.push(mid);
      }
      const isSelected = selectedSegments.has(sid);

      if (isGorS) {
        // Colored line
        const c = isSelected ? '#007AFF' : styl.lineColor;
        const opts = { color: c, weight: isSelected ? 4.5 : styl.lineWeight, opacity: isSelected ? 1 : 0.88, smoothFactor: 0.2 };
        const line = L.polyline(pts, opts).addTo(map);
        line.on('mouseover', () => { if (!isSelected) line.setStyle({ weight: styl.lineWeight + 1.5, opacity: 1 }); });
        line.on('mouseout', () => { if (!isSelected) line.setStyle({ weight: styl.lineWeight, opacity: 0.88 }); });
        line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
        segmentLines.set(sid, line);
      } else {
        // White fill + gray edge
        const eo = { color: '#BBBBBB', weight: isSelected ? 3.5 : 2.5, opacity: isSelected ? 1 : 0.5, smoothFactor: 0.2 };
        const fo = { color: '#F8F8F8', weight: isSelected ? 2 : 1.5, opacity: isSelected ? 1 : 0.92, smoothFactor: 0.2 };
        const edge = L.polyline(pts, eo).addTo(map);
        const fill = L.polyline(pts, fo).addTo(map);
        [edge, fill].forEach(line => {
          line.on('mouseover', () => { if (!isSelected) { edge.setStyle({ weight: 3.5, opacity: 0.7 }); fill.setStyle({ weight: 2.5 }); } });
          line.on('mouseout', () => { if (!isSelected) { edge.setStyle(eo); fill.setStyle(fo); } });
          line.on('click', (e) => { L.DomEvent.stopPropagation(e); if (activeTool === 'point') { handlePointTool(e.latlng); return; } onSegmentClick(sid, e.originalEvent); });
        });
        segmentLines.set(sid, edge);
        segmentFills.set(sid, fill);
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
    const marker = L.circleMarker(mc2latlng(node.x, node.z), {
      radius: isMergeTarget ? 7 : 5,
      fillColor: isMergeTarget ? '#FFD60A' : fill,
      color: isMergeTarget ? '#FF9500' : 'rgba(255,255,255,0.9)',
      weight: isMergeTarget ? 2.5 : 1.5,
      fillOpacity: 0.92,
    }).addTo(map);
    if (activeTool === 'move') {
      marker.pm.enableLayerDrag({ snappable: false, snapDistance: 0 });
      marker.on('pm:dragstart', () => { marker.setStyle({ fillOpacity: 0.55, radius: 6.5 }); });
      marker.on('pm:dragend', () => { marker.setStyle({ fillOpacity: 0.92, radius: 5 }); onNodeDragEnd(nid, marker); });
    }
    marker.on('mouseover', () => { if (activeTool !== 'move') marker.setRadius(6.5); });
    marker.on('mouseout', () => { if (activeTool !== 'move') marker.setRadius(5); });
    marker.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      if (activeTool === 'point') { handlePointTool(e.latlng); return; }
      if (activeTool === 'merge') { handleMergeTool(nid); return; }
      onNodeClick(nid, e.originalEvent);
    });
    nodeMarkers.set(nid, marker);
  }
}

// ——— Interactions ———
function onMapClick(e) {
  if (activeTool === 'point') {
    handlePointTool(e.latlng);
    return;
  }
  clearSelection();
}

function onNodeClick(nid, event) {
  if (event.ctrlKey || event.metaKey) return;
  clearSelection();
  selectNode(nid);
}

function onSegmentClick(sid, event) {
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
    if (r.status === 409) { showToast('版本冲突，正在刷新...', 'error'); loadData(); }
    else if (r.ok) return r.json().then(updated => {
      roadStore.nodes[nid] = updated;
      renderAll();
    });
    else showToast('保存失败', 'error');
  }).catch(() => showToast('网络错误', 'error'));
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

  let inSegment = false;
  for (const seg of Object.values(roadStore.segments)) {
    if (seg.nodeIds && seg.nodeIds.includes(nid)) { inSegment = true; break; }
  }
  document.getElementById('node-split-btn').style.display = inSegment ? '' : 'none';
}

function showSegmentEditor(sid) {
  selectedSegmentId = sid;
  const seg = roadStore.segments[sid];
  if (!seg) return;
  showEditor('segment-editor');
  document.getElementById('seg-id').textContent = sid.substring(0, 8) + '...';
  document.getElementById('seg-source').textContent = seg.source;
  document.getElementById('seg-status').textContent = seg.status;

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
}

// ——— Actions ———
async function saveNode() {
  const nid = selectedNodeId;
  if (!nid) return;
  const node = roadStore.nodes[nid];
  const x = parseFloat(document.getElementById('node-x').value);
  const z = parseFloat(document.getElementById('node-z').value);
  try {
    pushUndo();
    const res = await fetch('/api/nodes/' + nid, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, z, expectedVersion: node.version })
    });
    if (res.status === 409) { showToast('版本冲突, 正在刷新...', 'error'); loadData(); return; }
    if (!res.ok) { showToast('保存失败', 'error'); return; }
    const updated = await res.json();
    roadStore.nodes[nid] = updated;
    renderAll();
    showToast('节点已保存');
  } catch (e) { showToast('网络错误', 'error'); }
}

async function deleteNode() {
  const nid = selectedNodeId;
  if (!nid) return;
  showSheet('删除节点', '此操作将级联删除关联路段，确定要删除吗？', [
    { label: '取消', role: 'cancel' },
    { label: '删除', role: 'destructive', action: async () => {
        pushUndo();
        const res = await fetch('/api/nodes/' + nid, { method: 'DELETE' });
        if (res.ok) { clearSelection(); loadData(); showToast('节点已删除'); }
        else showToast('删除失败', 'error');
      }
    }
  ]);
}

async function deleteSegment() {
  const sid = selectedSegmentId;
  if (!sid) return;
  showSheet('删除路段', '确定要删除此路段吗？', [
    { label: '取消', role: 'cancel' },
    { label: '删除', role: 'destructive', action: async () => {
        pushUndo();
        const res = await fetch('/api/segments/' + sid, { method: 'DELETE' });
        if (res.ok) { clearSelection(); loadData(); showToast('路段已删除'); }
        else showToast('删除失败', 'error');
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
  const roadId = seg.roadId;
  if (roadId) {
    const road = roadStore.roads[roadId];
    pushUndo();
    const res = await fetch('/api/roads/' + roadId, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, classification, number, expectedVersion: road ? road.version : 0 })
    });
    if (res.status === 409) { showToast('版本冲突, 正在刷新...', 'error'); loadData(); return; }
    if (res.ok) {
      const updated = await res.json();
      roadStore.roads[roadId] = updated;
      renderAll();
      showToast('道路已保存');
    }
  } else {
    showToast('该路段未关联道路', 'error');
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
  if (res.ok) { clearSelection(); loadData(); showToast('路段已合并'); }
  else showToast('合并失败', 'error');
}

async function splitSegment() {
  const nid = selectedNodeId;
  if (!nid) return;
  let targetSeg = null, nodeIdx = -1;
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const idx = seg.nodeIds.indexOf(nid);
    if (idx >= 1 && idx < seg.nodeIds.length - 1) { targetSeg = seg; nodeIdx = idx; break; }
  }
  if (!targetSeg) { showToast('未找到可拆分的路段（节点需位于路段中间）', 'error'); return; }
  pushUndo();
  const res = await fetch('/api/split/' + targetSeg.id, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ nodeIndex: nodeIdx, expectedVersion: targetSeg.version })
  });
  if (res.ok) { clearSelection(); loadData(); showToast('路段已拆分'); }
  else showToast('拆分失败', 'error');
}

// ——— Toolbar ———
function initToolbar() {
  document.getElementById('tool-move').addEventListener('click', () => toggleTool('move'));
  document.getElementById('tool-point').addEventListener('click', () => toggleTool('point'));
  document.getElementById('tool-merge').addEventListener('click', () => toggleTool('merge'));
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
  document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('selected'));
  if (tool) {
    document.getElementById('tool-' + tool).classList.add('selected');
  }
  // Move: Geoman handles map-drag conflict internally; Merge: disable map dragging
  if (tool === 'merge') {
    map.dragging.disable();
  } else {
    map.dragging.enable();
  }
  renderAll();
}

function toggleToolbarMode() {
  const tb = document.getElementById('toolbar');
  const mapEl = document.getElementById('map');
  if (toolbarMode === 'compact') {
    toolbarMode = 'detailed';
    tb.classList.remove('toolbar-compact');
    tb.classList.add('toolbar-detailed');
    mapEl.style.left = '148px';
  } else {
    toolbarMode = 'compact';
    tb.classList.remove('toolbar-detailed');
    tb.classList.add('toolbar-compact');
    mapEl.style.left = '44px';
  }
  // Propagate map resize to Leaflet so tiles/controls reposition
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
    showToolToast('附近没有路段，无法插入孤立节点');
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
      showToast('版本冲突，正在刷新...', 'error');
      loadData();
      return;
    }
    if (!res.ok) {
      showToast('插入失败', 'error');
      return;
    }
    clearSelection();
    loadData();
    showToast('节点已插入');
  } catch (e) {
    showToast('网络错误', 'error');
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
      showToast('版本冲突，正在刷新...', 'error');
      loadData();
      return;
    }
    if (!res.ok) {
      showToast('交点插入失败', 'error');
      return;
    }
    clearSelection();
    loadData();
    showToolToast('已在交点插入节点');
  } catch (e) {
    showToast('网络错误', 'error');
  }
}

async function handleMergeTool(nid) {
  if (!mergeFirstNodeId) {
    mergeFirstNodeId = nid;
    renderAll(); // re-render to highlight the selected node
    showToolToast('已选中节点 ' + nid.substring(nid.length - 4) + '，再点击目标节点完成合并');
    return;
  }

  if (nid === mergeFirstNodeId) {
    mergeFirstNodeId = null;
    renderAll();
    showToolToast('已取消选择');
    return;
  }

  try {
    pushUndo();
    const res = await fetch('/api/nodes/merge', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nodeToDeleteId: mergeFirstNodeId,
        targetNodeId: nid
      })
    });
    if (!res.ok) {
      const err = await res.json();
      showToast('合并失败：' + (err.error || res.status), 'error');
      return;
    }
    clearSelection();
    mergeFirstNodeId = null;
    loadData();
    showToolToast('节点已合并');
  } catch (e) {
    showToast('网络错误', 'error');
  }
}

window.addEventListener('load', () => { initToolbar(); initMap(); });

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
