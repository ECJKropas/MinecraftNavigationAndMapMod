#!/bin/bash

ego-browser nodejs <<'EOF' 2>&1
const t = await useOrCreateTaskSpace('my-task')
await openOrReuseTab('https://modrinth.com/mod/wayfarer-in-game-gis-system/settings/versions', { wait: true, timeout: 30 })

await wait(3)


var info = "Start"
cliLog(info)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = document.querySelector("#__nuxt > div.layout > main > div.normal-page.no-sidebar > div.normal-page__content > div > div.grid.gap-4.lg\\:grid-cols-\\[1fr_3fr\\] > div.min-w-0 > div > div.mb-3.flex.flex-col.gap-3 > div > div.btn-wrapper.text-base > button");
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await uploadFile('input[type="file"]',
  '/Users/cjkim/Documents/MinecraftNavigationAndMapMod/build/libs/wayfarer-v0.3.3-mc1.20.1-SNAPSHOT.jar')


await wait(3)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.relative.flex-1.min-h-0.flex.flex-col > div.flex-1.min-h-0.overflow-y-auto.p-6.\\!pb-1.sm\\:pb-6 > div > div:nth-child(3) > div.flex.items-center.justify-between > div > button");
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.relative.flex-1.min-h-0.flex.flex-col > div.flex-1.min-h-0.overflow-y-auto.p-6.\\!pb-1.sm\\:pb-6 > div > div.space-y-1 > div.flex.items-center.justify-between > div > button");
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

await js(String.raw`(() => {
  const inp = document.querySelector('input[placeholder="Search versions"]');  // 或 input[aria-label="xxx"]
  inp.value = '1.20.1';
  inp.dispatchEvent(new Event('input', {bubbles: true}));
})()`)

info = "正在输入"
cliLog(info)

await wait(0.5)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = [...document.querySelectorAll('button.w-16')].find(b => b.textContent.trim() === '1.20.1');
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.p-4 > div > div:nth-child(2) > button");
  if (btn) {
    btn.click();
    return '点击成功,进入detail页面';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)


info = await js(String.raw`(() => {
  const editor = document.querySelector('.cm-content');

  // 1. 聚焦编辑器
  editor.focus();

  // 2. 清空（如果已有占位符）
  editor.innerHTML = '';

  // 3. 逐行写入，每行包在 <div class="cm-line"> 里
  const lines = [
    '## v0.3.3',
    '',
    '### Fixes',
    '- malilib 兼容性修复',
    '- 软删除边界情况修复',
    '- UI 修正',
  ];
  editor.innerHTML = lines.map(l => '<div class="cm-line">' + (l || '<br>') + '</div>').join('');

  // 4. 触发 input 事件让框架感知
  editor.dispatchEvent(new Event('input', {bubbles: true}));

  return 'filled';
})()`)

await wait(3)


info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Metadata');
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

info = await js(String.raw`(() => {
  const btns = [...document.querySelectorAll('button[aria-label="Add dependency"]')];
  if (btns.length > 0) {
    for (const btn of btns) {
      btn.click();
    }
    return '点击成功 ' + btns.length + ' 个';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === 'Details');
  if (btn) {
    btn.click();
    return '点击成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

await wait(1)

info = await js(String.raw`(() => {
  // 直接粘贴你复制到的选择器，保准唯一！
  const btn = document.querySelector("body > div.modal-root > div.modal-container.shown > div > div.p-4 > div > div:nth-child(2) > button");
  if (btn) {
    btn.click();
    return '点击成功,发布成功';
  } else {
    return '没找到';
  }
})()`)
cliLog(info)

EOF
