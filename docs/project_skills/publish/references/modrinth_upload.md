---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_fa6fa84e8a2411f1a093525400287e28
    ReservedCode1: 0vPkmfSnay7+WEQBAIVipmZFdhTXdlEnUl540IYlwfjTKIJjwc6kyZry49WZan/PKSk76Dzl8NaW5pxgbWt/nKFoydOQ2tcbxlLkbty7AXpZP2nhLgpX9DTTw9DObY9+EeQfCMOxsFPhy7AhxV/M+kwLAUsoET/epsBzAZQZgK7EVJm923l9YybTMwc=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_fa6fa84e8a2411f1a093525400287e28
    ReservedCode2: 0vPkmfSnay7+WEQBAIVipmZFdhTXdlEnUl540IYlwfjTKIJjwc6kyZry49WZan/PKSk76Dzl8NaW5pxgbWt/nKFoydOQ2tcbxlLkbty7AXpZP2nhLgpX9DTTw9DObY9+EeQfCMOxsFPhy7AhxV/M+kwLAUsoET/epsBzAZQZgK7EVJm923l9YybTMwc=
---

# Modrinth 上传操作指南

通过 ego-browser 自动化完成 Modrinth 版本创建与上传。每轮对应一个 MC 版本。

## 初始化与页面打开

```javascript
const t = await useOrCreateTaskSpace('my-task')
await openOrReuseTab('https://modrinth.com/mod/wayfarer-in-game-gis-system/settings/versions', { wait: true, timeout: 30 })
await wait(3)
```

## 每轮操作流程

### 1. 点击 Create version 按钮

选择器（精确，从 DevTools 复制）：

```javascript
document.querySelector("#__nuxt > div.layout > main > div.normal-page.no-sidebar > div.normal-page__content > div > div.grid.gap-4.lg\\:grid-cols-\\[1fr_3fr\\] > div.min-w-0 > div > div.mb-3.flex.flex-col.gap-3 > div > div.btn-wrapper.text-base > button")
```

### 2. 上传 Jar 文件

```javascript
await uploadFile('input[type="file"]', '/Users/cjkim/Documents/MinecraftNavigationAndMapMod/build/libs/wayfarer-vX.Y.Z-mc<version>.jar')
```

上传后等待 6-8 秒让 Modrinth 解析 jar。

### 3. 修复 Detected Versions

> Very Important! 26.2版本不需要执行这一步,请不要多余工作,直接进行下一步前往details

#### 3.1 点击 Detected loaders 的编辑按钮

选择器：
```javascript
document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.relative.flex-1.min-h-0.flex.flex-col > div.flex-1.min-h-0.overflow-y-auto.p-6.\\!pb-1.sm\\:pb-6 > div > div:nth-child(3) > div.flex.items-center.justify-between > div > button")
```

#### 3.2 展开游戏版本列表

选择器：
```javascript
document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.relative.flex-1.min-h-0.flex.flex-col > div.flex-1.min-h-0.overflow-y-auto.p-6.\\!pb-1.sm\\:pb-6 > div > div.space-y-1 > div.flex.items-center.justify-between > div > button")
```

#### 3.3 搜索并选择目标 MC 版本

先取消多余版本（Modrinth 默认会勾选多个），然后搜索目标版本：

```javascript
(async () => {
  // 搜索目标版本
  const inp = document.querySelector('input[placeholder="Search versions"]');
  inp.value = '<mc-version>';  // 如 '1.20.1' 或 '26.1.1' 或 '26.2'
  inp.dispatchEvent(new Event('input', {bubbles: true}));
  await new Promise(r => setTimeout(r, 500));

  // 点击对应版本的按钮
  const btn = [...document.querySelectorAll('button.w-16')].find(b => b.textContent.trim() === '<mc-version>');
  if (btn) btn.click();
})();
```

注意：26.2 版本的选择器可能不同，如果 `button.w-16` 找不到，需要在 DevTools 中检查实际 class。

### 4. 进入 Details 并填写 Changelog

#### 4.1 进入 Details 标签页

点击 modal 底部的按钮（在 Detected versions 下方）：

```javascript
document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.p-4 > div > div:nth-child(2) > button")
```

#### 4.2 使用 CodeMirror 编辑器填充 Changelog

CodeMirror 是 `<div class="cm-content">` 容器，每行必须用 `<div class="cm-line">` 包裹：

```javascript
(() => {
  const editor = document.querySelector('.cm-content');
  editor.focus();
  editor.innerHTML = '';

  const lines = [
    '## vX.Y.Z',
    '',
    '### Fixes',
    '- item 1',
    '- item 2',
    '',
    '### Features',
    '- item 1',
  ];
  editor.innerHTML = lines.map(l => '<div class="cm-line">' + (l || '<br>') + '</div>').join('');

  // 触发 input 事件让框架感知变化
  editor.dispatchEvent(new Event('input', {bubbles: true}));

  return 'filled';
})();
```

⚠️ CodeMirror 找不到时的处理：26.2 版本的创建页面可能在 DOM 结构上有差异。如果 `document.querySelector('.cm-content')` 返回 null，先检查页面是否已经进入 Details 标签页，再尝试其他 CodeMirror 实例选择器。

填充后等待 3 秒。

### 5. 添加依赖

#### 5.1 切换到 Metadata 标签页

```javascript
[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Metadata').click()
```

#### 5.2 添加依赖（Fabric API + MaLiLib）

点击所有「Add dependency」按钮（每个依赖项目一个按钮）：

```javascript
(() => {
  const btns = [...document.querySelectorAll('button[aria-label="Add dependency"]')];
  for (const btn of btns) {
    btn.click();
  }
  return 'clicked ' + btns.length;
})();
```

等待 1 秒让依赖面板展开。

### 6. 提交发布

#### 6.1 切回 Details 标签页

```javascript
[...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Details').click()
```

#### 6.2 点击提交按钮

```javascript
document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.p-4 > div > div:nth-child(2) > button").click()
```

## 版本顺序

mc1.20.1 → mc26.1.1 → mc26.2

## 关键注意点

- 每轮上传之间不需要关闭 modal，Modrinth 会在提交后自动回到列表页
- 上传 jar 后 **必须等 6-8 秒** 让 Modrinth 完成解析
- 使用 `cliLog()` 输出日志，`console.log` 不生效
- 所有 `js()` 参数使用 `String.raw` 包裹，避免反斜杠转义问题
- 选择器含反斜杠时（如 `lg\\:grid-cols`），`String.raw` 可以避免重复转义
- 26.2 版本容易因选择器差异失败，必要时手动在 DevTools 获取正确的选择器
*（内容由AI生成，仅供参考）*
