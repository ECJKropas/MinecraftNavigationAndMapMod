// Wayfarer Road Editor — Apple-style frontend
// MC coords (X, Z) map to Leaflet [lat=Z/128, lng=X/128]

const SCALE = 128.0;
let map, selectedSegments = new Set(), selectedNodeId = null, selectedSegmentId = null;
let roadStore = { nodes:{}, segments:{}, roads:{} };
let activeTool = null;          // 'move' | 'point' | null
let toolbarMode = 'compact';   // 'compact' | 'detailed'
const TOOL_TOLERANCE_PX = 12;  // pixel tolerance for point tool segment detection

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
    maxZoom: 10,
    zoomControl: true
  }).setView([0, 0], 5);
  map.on('click', onMapClick);
  loadData();
  setInterval(loadDelta, 2000);
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
      for (const s of data.segments) { roadStore.segments[s.id] = s; }
      changed = true;
    }
    if (data.roads) {
      for (const [k, v] of Object.entries(data.roads)) { roadStore.roads[k] = v; }
      changed = true;
    }
    if (changed) renderAll();
  } catch (e) { /* silent */ }
}

// ——— Rendering ———
let nodeMarkers = new Map();
let segmentLines = new Map();

function renderAll() {
  nodeMarkers.forEach(m => map.removeLayer(m));
  segmentLines.forEach(l => map.removeLayer(l));
  nodeMarkers.clear();
  segmentLines.clear();

  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    const pts = [];
    if (!seg.nodeIds) continue;
    for (const nid of seg.nodeIds) {
      const node = roadStore.nodes[nid];
      if (node) pts.push(mc2latlng(node.x, node.z));
    }
    if (pts.length < 2) continue;

    const road = seg.roadId ? roadStore.roads[seg.roadId] : null;
    const color = road ? road.color : '#CCCCCC';
    const isSelected = selectedSegments.has(sid);

    const opts = {
      color: isSelected ? '#007AFF' : color,
      weight: isSelected ? 4 : 2,
      opacity: isSelected ? 1 : 0.7,
      smoothFactor: 0.2,
    };
    if (seg.source === 'AUTO') {
      opts.dashArray = '6,4';
      if (!isSelected) opts.color = '#aeaeb2';
    }

    const line = L.polyline(pts, opts).addTo(map);
    line.on('mouseover', () => {
      if (!selectedSegments.has(sid)) line.setStyle({ weight: 3, opacity: 0.9 });
    });
    line.on('mouseout', () => {
      if (!selectedSegments.has(sid)) {
        line.setStyle({ weight: 2, opacity: seg.source === 'AUTO' ? 0.5 : 0.7 });
      }
    });
    line.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      onSegmentClick(sid, e.originalEvent);
    });
    segmentLines.set(sid, line);
  }

  for (const [nid, node] of Object.entries(roadStore.nodes)) {
    const fill = node.source === 'AUTO' ? '#aeaeb2'
      : node.cornerType === 'SHARP' ? '#FF3B30' : '#007AFF';
    const marker = L.circleMarker(mc2latlng(node.x, node.z), {
      radius: 5, fillColor: fill, color: 'rgba(255,255,255,0.9)',
      weight: 1.5, fillOpacity: 0.92,
      draggable: activeTool === 'move',
    }).addTo(map);
    marker.on('mouseover', () => marker.setRadius(6.5));
    marker.on('mouseout', () => marker.setRadius(5));
    marker.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      onNodeClick(nid, e.originalEvent);
    });
    marker.on('dragend', () => onNodeDragEnd(nid, marker));
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
  document.getElementById('node-id').textContent = nid.substring(0, 8) + '...';
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
  document.getElementById('seg-color').value = road ? (road.color || '#007AFF') : '#007AFF';
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
  const color = document.getElementById('seg-color').value;
  const roadId = seg.roadId;
  if (roadId) {
    const road = roadStore.roads[roadId];
    const res = await fetch('/api/roads/' + roadId, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, color, expectedVersion: road ? road.version : 0 })
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
  document.getElementById('tool-mode-toggle').addEventListener('click', toggleToolbarMode);
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
  document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('selected'));
  if (tool) {
    document.getElementById('tool-' + tool).classList.add('selected');
  }
  renderAll();
}

function toggleToolbarMode() {
  const tb = document.getElementById('toolbar');
  if (toolbarMode === 'compact') {
    toolbarMode = 'detailed';
    tb.classList.remove('toolbar-compact');
    tb.classList.add('toolbar-detailed');
  } else {
    toolbarMode = 'compact';
    tb.classList.remove('toolbar-detailed');
    tb.classList.add('toolbar-compact');
  }
}

// ——— Point tool ———
function handlePointTool(latlng) {
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

async function insertNodeOnSegment(segId, insertIndex, latlng) {
  const seg = roadStore.segments[segId];
  if (!seg) return;
  const x = latlng.lng * SCALE;
  const z = latlng.lat * SCALE;
  try {
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

window.addEventListener('load', () => { initToolbar(); initMap(); });
