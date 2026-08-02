// Wayfarer i18n Module
// Supports zh-CN and en languages with localStorage persistence

const I18N = {
  currentLang: 'zh-CN',
  translations: {
    'zh-CN': {
      // Page
      'page.title': 'Wayfarer — 道路编辑器',
      
      // Toolbar
      'toolbar.move.title': '移动节点',
      'toolbar.move.label': '移动',
      'toolbar.constrain.extend': '延长',
      'toolbar.constrain.free': '自由拖动',
      'toolbar.point.title': '描点',
      'toolbar.point.label': '描点',
      'toolbar.merge.title': '合并节点',
      'toolbar.merge.label': '合并',
      'toolbar.fenhe.title': '分合 (路段拆分/合并)',
      'toolbar.fenhe.label': '分合',
      'toolbar.softdelete.title': '软删除 (删除节点保留路段连续性)',
      'toolbar.softdelete.label': '软删',
      'toolbar.undo.title': '撤销 (Ctrl+Z)',
      'toolbar.undo.label': '撤销',
      'toolbar.redo.title': '重做 (Ctrl+Shift+Z)',
      'toolbar.redo.label': '重做',
      'toolbar.expand.title': '展开工具栏',
      'toolbar.expand.label': '展开',
      'toolbar.contract.label': '收起',
      'toolbar.language.title': '切换语言',
      'toolbar.language.label': 'EN',
      
      // Editor - No selection
      'editor.noSelection': '点击节点或路段查看属性',
      
      // Editor - Node
      'editor.node.sectionLabel': '节点属性',
      'editor.node.id': 'ID',
      'editor.node.x': 'X',
      'editor.node.z': 'Z',
      'editor.node.source': '来源',
      'editor.node.save': '保存',
      'editor.node.cancel': '取消',
      'editor.node.delete': '删除节点',
      
      // Editor - Segment
      'editor.segment.sectionLabel': '路段属性',
      'editor.segment.id': 'ID',
      'editor.segment.classification': '等级',
      'editor.segment.classification.none': '—',
      'editor.segment.classification.G': '国道/高速',
      'editor.segment.classification.S': '省道/高架',
      'editor.segment.classification.X': '县道',
      'editor.segment.classification.Y': '乡道',
      'editor.segment.classification.C': '村道',
      'editor.segment.number': '编号',
      'editor.segment.number.placeholder': '如 107',
      'editor.segment.roadName': '道路名',
      'editor.segment.roadName.placeholder': '未命名道路',
      'editor.segment.source': '来源',
      'editor.segment.status': '状态',
      'editor.segment.save': '保存道路',
      'editor.segment.cancel': '取消',
      'editor.segment.mergeSelected': '合并选中',
      'editor.segment.delete': '删除路段',
      
      // Toasts - General
      'toast.undoFailed': '撤销失败',
      'toast.undoSuccess': '已撤销',
      'toast.redoFailed': '重做失败',
      'toast.redoSuccess': '已重做',
      'toast.networkError': '网络错误',
      'toast.loadFailed': '加载数据失败',
      'toast.saveFailed': '保存失败',
      'toast.deleteFailed': '删除失败',
      'toast.unknownError': '未知错误',
      
      // Toasts - Node
      'toast.nodeSaved': '节点已保存',
      'toast.nodeDeleted': '节点已删除',
      'toast.nodeInserted': '节点已插入',
      'toast.nodeInsertedIntersection': '已在交点插入节点',
      'toast.nodeMerged': '节点已合并',
      'toast.nodeSoftDeleted': '节点已软删除',
      'toast.endpointSoftDeleted': '端点已软删除',
      
      // Toasts - Segment/Road
      'toast.segmentDeleted': '路段已删除',
      'toast.segmentMerged': '路段已合并',
      'toast.segmentNotLinked': '该路段未关联道路',
      'toast.roadSaved': '道路已保存',
      
      // Toasts - Conflict
      'toast.versionConflict': '版本冲突',
      'toast.gameModified': '游戏内已修改',
      'toast.refreshing': '正在刷新...',
      'toast.syncedFromGame': '已从游戏同步最新数据',
      'toast.acceptedGameVersion': '已接受游戏版本',
      'toast.retrying': '重试中...',
      
      // Toasts - Tools
      'toast.noSegmentNearby': '附近没有路段，无法插入孤立节点',
      'toast.selectedNode': '已选中节点 {id}，再点击目标节点完成合并',
      'toast.cancelledSelection': '已取消选择',
      'toast.cannotMerge': '这可不能合并啊！',
      'toast.cannotUseTool': '无法使用该工具',
      'toast.differentRoads': '路段属于不同道路，无法合并',
      'toast.splitSuccess': '拆分成功',
      'toast.splitFailed': '拆分失败',
      'toast.mergeSuccess': '合并成功',
      'toast.mergeFailed': '合并失败',
      'toast.insertFailed': '插入失败',
      'toast.intersectionInsertFailed': '交点插入失败',
      
      // Sheets - Node delete
      'sheet.deleteNode.title': '删除节点',
      'sheet.deleteNode.message': '此操作将级联删除关联路段，确定要删除吗？',
      'sheet.deleteNode.cancel': '取消',
      'sheet.deleteNode.confirm': '删除',
      
      // Sheets - Segment delete
      'sheet.deleteSegment.title': '删除路段',
      'sheet.deleteSegment.message': '确定要删除此路段吗？',
      
      // Sheets - Merge
      'sheet.mergeNode.title': '合并节点',
      'sheet.mergeNode.message': '合并该节点会删除中间所有节点，是否继续？',
      'sheet.mergeNode.cancel': '取消',
      'sheet.mergeNode.confirm': '合并',
      
      // Sheets - Conflict
      'sheet.conflict.message': '此{entityType}在游戏中已被修改 (版本 {serverVersion})。\n你的编辑版本为 {clientVersion}。\n\n选择如何处理：',
      'sheet.conflict.optionA': '输入 [A] 接受游戏版本 (按 A)',
      'sheet.conflict.optionR': '输入 [R] 重试你的编辑 (按 R)',
      
      // Entity types
      'entity.node': '节点',
      'entity.segment': '路段',
      'entity.road': '道路'
    },
    
    'en': {
      // Page
      'page.title': 'Wayfarer — Road Editor',
      
      // Toolbar
      'toolbar.move.title': 'Move Node',
      'toolbar.move.label': 'Move',
      'toolbar.constrain.extend': 'Constrain',
      'toolbar.constrain.free': 'Free Drag',
      'toolbar.point.title': 'Add Point',
      'toolbar.point.label': 'Point',
      'toolbar.merge.title': 'Merge Nodes',
      'toolbar.merge.label': 'Merge',
      'toolbar.fenhe.title': 'Split/Merge (Segment Split/Merge)',
      'toolbar.fenhe.label': 'Split/Merge',
      'toolbar.softdelete.title': 'Soft Delete (Delete Node, Preserve Continuity)',
      'toolbar.softdelete.label': 'Soft Del',
      'toolbar.undo.title': 'Undo (Ctrl+Z)',
      'toolbar.undo.label': 'Undo',
      'toolbar.redo.title': 'Redo (Ctrl+Shift+Z)',
      'toolbar.redo.label': 'Redo',
      'toolbar.expand.title': 'Expand Toolbar',
      'toolbar.expand.label': 'Expand',
      'toolbar.contract.label': 'Collapse',
      'toolbar.language.title': 'Change Language',
      'toolbar.language.label': '中',
      
      // Editor - No selection
      'editor.noSelection': 'Click a node or segment to view properties',
      
      // Editor - Node
      'editor.node.sectionLabel': 'Node Properties',
      'editor.node.id': 'ID',
      'editor.node.x': 'X',
      'editor.node.z': 'Z',
      'editor.node.source': 'Source',
      'editor.node.save': 'Save',
      'editor.node.cancel': 'Cancel',
      'editor.node.delete': 'Delete Node',
      
      // Editor - Segment
      'editor.segment.sectionLabel': 'Segment Properties',
      'editor.segment.id': 'ID',
      'editor.segment.classification': 'Class',
      'editor.segment.classification.none': '—',
      'editor.segment.classification.G': 'National Highway',
      'editor.segment.classification.S': 'Provincial Highway',
      'editor.segment.classification.X': 'County Road',
      'editor.segment.classification.Y': 'Township Road',
      'editor.segment.classification.C': 'Village Road',
      'editor.segment.number': 'Number',
      'editor.segment.number.placeholder': 'e.g. 107',
      'editor.segment.roadName': 'Road Name',
      'editor.segment.roadName.placeholder': 'Unnamed Road',
      'editor.segment.source': 'Source',
      'editor.segment.status': 'Status',
      'editor.segment.save': 'Save Road',
      'editor.segment.cancel': 'Cancel',
      'editor.segment.mergeSelected': 'Merge Selected',
      'editor.segment.delete': 'Delete Segment',
      
      // Toasts - General
      'toast.undoFailed': 'Undo failed',
      'toast.undoSuccess': 'Undone',
      'toast.redoFailed': 'Redo failed',
      'toast.redoSuccess': 'Redone',
      'toast.networkError': 'Network Error',
      'toast.loadFailed': 'Failed to load data',
      'toast.saveFailed': 'Save failed',
      'toast.deleteFailed': 'Delete failed',
      'toast.unknownError': 'Unknown error',
      
      // Toasts - Node
      'toast.nodeSaved': 'Node saved',
      'toast.nodeDeleted': 'Node deleted',
      'toast.nodeInserted': 'Node inserted',
      'toast.nodeInsertedIntersection': 'Node inserted at intersection',
      'toast.nodeMerged': 'Nodes merged',
      'toast.nodeSoftDeleted': 'Node soft-deleted',
      'toast.endpointSoftDeleted': 'Endpoint soft-deleted',
      
      // Toasts - Segment/Road
      'toast.segmentDeleted': 'Segment deleted',
      'toast.segmentMerged': 'Segments merged',
      'toast.segmentNotLinked': 'Segment not linked to a road',
      'toast.roadSaved': 'Road saved',
      
      // Toasts - Conflict
      'toast.versionConflict': 'Version conflict',
      'toast.gameModified': 'Modified in game',
      'toast.refreshing': 'Refreshing...',
      'toast.syncedFromGame': 'Synced latest data from game',
      'toast.acceptedGameVersion': 'Accepted game version',
      'toast.retrying': 'Retrying...',
      
      // Toasts - Tools
      'toast.noSegmentNearby': 'No segment nearby, cannot insert orphan node',
      'toast.selectedNode': 'Selected node {id}, click target node to merge',
      'toast.cancelledSelection': 'Selection cancelled',
      'toast.cannotMerge': 'Cannot merge these!',
      'toast.cannotUseTool': 'Cannot use this tool',
      'toast.differentRoads': 'Segments belong to different roads, cannot merge',
      'toast.splitSuccess': 'Split successful',
      'toast.splitFailed': 'Split failed',
      'toast.mergeSuccess': 'Merge successful',
      'toast.mergeFailed': 'Merge failed',
      'toast.insertFailed': 'Insert failed',
      'toast.intersectionInsertFailed': 'Intersection insert failed',
      
      // Sheets - Node delete
      'sheet.deleteNode.title': 'Delete Node',
      'sheet.deleteNode.message': 'This will cascade and delete connected segments. Are you sure?',
      'sheet.deleteNode.cancel': 'Cancel',
      'sheet.deleteNode.confirm': 'Delete',
      
      // Sheets - Segment delete
      'sheet.deleteSegment.title': 'Delete Segment',
      'sheet.deleteSegment.message': 'Are you sure you want to delete this segment?',
      
      // Sheets - Merge
      'sheet.mergeNode.title': 'Merge Nodes',
      'sheet.mergeNode.message': 'Merging this node will delete all intermediate nodes. Continue?',
      'sheet.mergeNode.cancel': 'Cancel',
      'sheet.mergeNode.confirm': 'Merge',
      
      // Sheets - Conflict
      'sheet.conflict.message': 'This {entityType} has been modified in game (version {serverVersion}).\nYour edit version is {clientVersion}.\n\nChoose how to handle:',
      'sheet.conflict.optionA': 'Enter [A] to accept game version (press A)',
      'sheet.conflict.optionR': 'Enter [R] to retry your edit (press R)',
      
      // Entity types
      'entity.node': 'Node',
      'entity.segment': 'Segment',
      'entity.road': 'Road'
    }
  },
  
  // Initialize i18n
  init() {
    const saved = localStorage.getItem('wayfarer_lang');
    const browserLang = navigator.language.toLowerCase().startsWith('zh') ? 'zh-CN' : 'en';
    this.currentLang = saved || browserLang;
    
    if (!this.translations[this.currentLang]) {
      this.currentLang = 'en';
    }
    
    document.documentElement.lang = this.currentLang;
    document.title = this.t('page.title');
    
    this.applyToDOM();
    
    document.dispatchEvent(new CustomEvent('languagechange', {
      detail: { lang: this.currentLang }
    }));
  },
  
  // Get translated string with optional interpolation
  t(key, params) {
    const dict = this.translations[this.currentLang] || this.translations['en'];
    let str = dict[key] || this.translations['en'][key] || key;
    
    if (params && typeof params === 'object') {
      for (const [k, v] of Object.entries(params)) {
        str = str.replace(new RegExp('\\{' + k + '\\}', 'g'), String(v));
      }
    }
    
    return str;
  },
  
  // Apply i18n to all DOM elements with data-i18n attributes
  applyToDOM() {
    // Text content
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      el.textContent = this.t(key);
    });
    
    // Placeholder
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
      const key = el.getAttribute('data-i18n-placeholder');
      el.setAttribute('placeholder', this.t(key));
    });
    
    // Title attribute
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
      const key = el.getAttribute('data-i18n-title');
      el.setAttribute('title', this.t(key));
    });
    
    // HTML content (preserves HTML structure)
    document.querySelectorAll('[data-i18n-html]').forEach(el => {
      const key = el.getAttribute('data-i18n-html');
      el.innerHTML = this.t(key);
    });
    
    // Update page title
    document.title = this.t('page.title');
  },
  
  // Set language
  setLanguage(lang) {
    if (!this.translations[lang]) return;
    this.currentLang = lang;
    localStorage.setItem('wayfarer_lang', lang);
    document.documentElement.lang = lang;
    this.applyToDOM();
    
    document.dispatchEvent(new CustomEvent('languagechange', {
      detail: { lang }
    }));
  },
  
  // Toggle between languages
  toggleLanguage() {
    const nextLang = this.currentLang === 'zh-CN' ? 'en' : 'zh-CN';
    this.setLanguage(nextLang);
  }
};
