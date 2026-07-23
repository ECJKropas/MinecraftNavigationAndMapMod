// Wayfarer Road Editor — Leaflet frontend
// MC coords (X, Z) map to Leaflet [lat=Z/128, lng=X/128]

const SCALE = 128.0; // MC blocks → Leaflet units
let map, selectedSegments = new Set(), selectedNodeId = null, selectedSegmentId = null;

// ---- Data cache ----
let roadStore = { nodes:{}, segments:{}, roads:{} };

function initMap() {
  map = L.map('map', { crs:L.CRS.Simple, minZoom:-4, maxZoom:8, zoomControl:true }).setView([0,0], -2);
  map.on('click', onMapClick);
  loadData();
  setInterval(loadDelta, 2000);
}

function mc2latlng(x, z) { return [z/SCALE, x/SCALE]; }

// ---- Data loading ----
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
  } catch(e) { setStatus('加载数据失败: ' + e.message); }
}

async function loadDelta() {
  try {
    const since = Math.floor((Date.now() - 5000));
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
      for (const [k,v] of Object.entries(data.roads)) { roadStore.roads[k] = v; }
      changed = true;
    }
    if (changed) renderAll();
  } catch(e) { /* polling may fail silently */ }
}

// ---- Rendering ----
let nodeMarkers = new Map();
let segmentLines = new Map();

function renderAll() {
  // Clear old
  nodeMarkers.forEach(m => map.removeLayer(m));
  segmentLines.forEach(l => map.removeLayer(l));
  nodeMarkers.clear();
  segmentLines.clear();

  // Draw segments first
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    const pts = [];
    if (!seg.nodeIds) continue;
    for (const nid of seg.nodeIds) {
      const node = roadStore.nodes[nid];
      if (node) pts.push(mc2latlng(node.x, node.z));
    }
    if (pts.length < 2) continue;

    const road = seg.roadId ? roadStore.roads[seg.roadId] : null;
    const color = road ? road.color : '#FFFFFF';
    const isSelected = selectedSegments.has(sid);

    const opts = {
      color: isSelected ? '#f44' : color,
      weight: isSelected ? 5 : 2.5,
      opacity: isSelected ? 1 : 0.8,
    };

    if (seg.source === 'AUTO') {
      opts.dashArray = '6,4';
      if (!isSelected) opts.color = '#777';
    }

    const line = L.polyline(pts, opts).addTo(map);
    line.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      onSegmentClick(sid, e.originalEvent);
    });
    segmentLines.set(sid, line);
  }

  // Draw nodes
  for (const [nid, node] of Object.entries(roadStore.nodes)) {
    const color = node.source === 'AUTO' ? '#888'
      : node.cornerType === 'SHARP' ? '#f44' : '#48f';
    const marker = L.circleMarker(mc2latlng(node.x, node.z), {
      radius: 4, fillColor:color, color:'#fff', weight:1, fillOpacity:0.9
    }).addTo(map);
    marker.on('click', (e) => {
      L.DomEvent.stopPropagation(e);
      onNodeClick(nid, e.originalEvent);
    });
    // Drag
    marker.dragging ? marker.dragging.enable() : null;
    marker.on('dragend', () => onNodeDragEnd(nid, marker));
    nodeMarkers.set(nid, marker);
  }
}

// ---- Interactions ----
function onMapClick() {
  clearSelection();
}

function onNodeClick(nid, event) {
  if (event.ctrlKey || event.metaKey) return;
  clearSelection();
  selectNode(nid);
}

function onSegmentClick(sid, event) {
  if (event.ctrlKey || event.metaKey) {
    if (selectedSegments.has(sid)) {
      selectedSegments.delete(sid);
    } else {
      selectedSegments.add(sid);
    }
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
    method:'PUT',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({x, z, expectedVersion: node.version})
  }).then(r => {
    if (r.status === 409) { setStatus('版本冲突，正在刷新...'); loadData(); }
    else if (r.ok) return r.json().then(updated => {
      roadStore.nodes[nid] = updated;
      renderAll();
    });
    else setStatus('保存失败');
  }).catch(e => setStatus('网络错误: ' + e.message));
}

function selectNode(nid) {
  selectedNodeId = nid;
  const node = roadStore.nodes[nid];
  if (!node) return;
  document.getElementById('no-selection').style.display = 'none';
  document.getElementById('segment-editor').style.display = 'none';
  const ed = document.getElementById('node-editor');
  ed.style.display = 'block';
  document.getElementById('node-id').textContent = nid.substring(0,8)+'...';
  document.getElementById('node-x').value = node.x;
  document.getElementById('node-z').value = node.z;
  document.getElementById('node-source').textContent = node.source;

  // Show split button if this node belongs to a segment
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
  document.getElementById('no-selection').style.display = 'none';
  document.getElementById('node-editor').style.display = 'none';
  const ed = document.getElementById('segment-editor');
  ed.style.display = 'block';
  document.getElementById('seg-id').textContent = sid.substring(0,8)+'...';
  document.getElementById('seg-source').textContent = seg.source;
  document.getElementById('seg-status').textContent = seg.status;

  const road = seg.roadId ? roadStore.roads[seg.roadId] : null;
  document.getElementById('seg-road-name').value = road ? (road.name||'') : '';
  document.getElementById('seg-color').value = road ? (road.color||'#FFFFFF') : '#FFFFFF';
}

function clearSelection() {
  selectedSegments.clear();
  selectedNodeId = null;
  selectedSegmentId = null;
  document.getElementById('no-selection').style.display = '';
  document.getElementById('node-editor').style.display = 'none';
  document.getElementById('segment-editor').style.display = 'none';
  document.getElementById('merge-btn').style.display = 'none';
  renderAll();
}

function updateMergeButton() {
  const btn = document.getElementById('merge-btn');
  btn.style.display = selectedSegments.size >= 2 ? '' : 'none';
}

// ---- Save / Delete actions ----
async function saveNode() {
  const nid = selectedNodeId;
  if (!nid) return;
  const node = roadStore.nodes[nid];
  const x = parseFloat(document.getElementById('node-x').value);
  const z = parseFloat(document.getElementById('node-z').value);
  try {
    const res = await fetch('/api/nodes/'+nid, {
      method:'PUT',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({x, z, expectedVersion: node.version})
    });
    if (res.status === 409) { setStatus('版本冲突，正在刷新...'); loadData(); return; }
    if (!res.ok) { setStatus('保存失败'); return; }
    const updated = await res.json();
    roadStore.nodes[nid] = updated;
    renderAll();
    setStatus('节点已保存');
  } catch(e) { setStatus('网络错误'); }
}

async function deleteNode() {
  const nid = selectedNodeId;
  if (!nid || !confirm('删除此节点将级联删除关联路段，确定？')) return;
  const res = await fetch('/api/nodes/'+nid, { method:'DELETE' });
  if (res.ok) { clearSelection(); loadData(); setStatus('节点已删除'); }
  else setStatus('删除失败');
}

async function deleteSegment() {
  const sid = selectedSegmentId;
  if (!sid || !confirm('确定删除此路段？')) return;
  const res = await fetch('/api/segments/'+sid, { method:'DELETE' });
  if (res.ok) { clearSelection(); loadData(); setStatus('路段已删除'); }
  else setStatus('删除失败');
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
    const res = await fetch('/api/roads/'+roadId, {
      method:'PATCH',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({name, color, expectedVersion: road ? road.version : 0})
    });
    if (res.status === 409) { setStatus('版本冲突，正在刷新...'); loadData(); return; }
    if (res.ok) {
      const updated = await res.json();
      roadStore.roads[roadId] = updated;
      renderAll();
      setStatus('道路已保存');
    }
  } else {
    setStatus('该路段未关联道路');
  }
}

async function mergeSegments() {
  if (selectedSegments.size < 2) return;
  const ids = [...selectedSegments];
  const res = await fetch('/api/merge', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({segmentIds: ids})
  });
  if (res.ok) { clearSelection(); loadData(); setStatus('路段已合并'); }
  else setStatus('合并失败');
}

async function splitSegment() {
  const nid = selectedNodeId;
  if (!nid) return;
  // Find which segment contains this node
  let targetSeg = null, nodeIdx = -1;
  for (const [sid, seg] of Object.entries(roadStore.segments)) {
    if (!seg.nodeIds) continue;
    const idx = seg.nodeIds.indexOf(nid);
    if (idx >= 1 && idx < seg.nodeIds.length-1) {
      targetSeg = seg; nodeIdx = idx; break;
    }
  }
  if (!targetSeg) { setStatus('未找到可拆分的路段（节点需位于路段中间）'); return; }

  const res = await fetch('/api/split/'+targetSeg.id, {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({nodeIndex: nodeIdx, expectedVersion: targetSeg.version})
  });
  if (res.ok) { clearSelection(); loadData(); setStatus('路段已拆分'); }
  else setStatus('拆分失败');
}

function setStatus(msg) {
  document.getElementById('status').textContent = msg;
  setTimeout(() => { const el = document.getElementById('status'); if(el) el.textContent=''; }, 3000);
}

// ---- Init ----
window.addEventListener('load', initMap);
