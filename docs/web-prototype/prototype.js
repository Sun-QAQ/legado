'use strict';
// 独立原型：所有数据与状态仅在此页面内存中，不调用 App 接口。
const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const books = [
  { id: 0, name: '山海之间', author: '林间客', type: '文学', progress: 42, chapter: '第十二章 · 风从海上来', color: '#586d6b', subtitle: 'MOUNTAINS & SEAS', updated: true },
  { id: 1, name: '长夜微光', author: '陆远', type: '悬疑', progress: 18, chapter: '第五章 · 旧信', color: '#555268', subtitle: 'A LIGHT IN THE NIGHT', updated: true },
  { id: 2, name: '人间草木', author: '白露', type: '散文', progress: 67, chapter: '第二十章 · 春日', color: '#99846a', subtitle: 'LIFE IN THE ORDINARY' },
  { id: 3, name: '星河旅人', author: '徐行', type: '科幻', progress: 9, chapter: '第三章 · 远航', color: '#52647c', subtitle: 'BEYOND THE STARS', updated: true },
  { id: 4, name: '听风记', author: '南枝', type: '文学', progress: 0, chapter: '尚未开始阅读', color: '#9a6c62', subtitle: 'THE SOUND OF WIND' },
  { id: 5, name: '时间的侧面', author: '周序', type: '随笔', progress: 100, chapter: '全书读完', color: '#72715b', subtitle: 'ON THE OTHER SIDE' },
];
const sourceData = {
  sources: [
    { name: '山海书屋', url: 'https://books.example.com', group: '文学 / 综合', enabled: true },
    { name: '星河文库', url: 'https://stars.example.com', group: '科幻', enabled: true },
    { name: '旧时书馆', url: 'https://archive.example.com', group: '经典', enabled: false },
  ],
  rss: [
    { name: '每日读书', url: 'https://reading.example.com', group: '阅读', enabled: true },
    { name: '设计札记', url: 'https://design.example.com', group: '设计', enabled: true },
  ],
};
const state = { filter: '全部书籍', query: '', list: false, searchKey: '', searched: false, book: 0, chapter: 12, fontSize: 18, line: 2, letter: 0, paragraph: 1.2, width: 820, jump: 300, readerFont: '宋体', paper: '纸张', infinite: false, focus: false, sourceIndex: 0, sourceTab: '基础', editorMode: 'form', sourceDirty: false, drafts: {}, undo: [], debugStatus: 'idle', debugTimer: null, connected: true, address: 'http://127.0.0.1:9527' };
const titles = { shelf: '我的书架', search: '在线搜索', read: '正在阅读', sources: '书源管理', rss: '订阅源管理', settings: '连接与设置' };
let page = 'shelf';
let toastTimer;
function toast(message) { $('#toast').textContent = message; $('#toast').classList.add('show'); clearTimeout(toastTimer); toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 3200); }
function go(target) { if (location.hash === '#' + target) render(); else location.hash = target; }
function heading(title, subtitle, actions = '') { return `<div class="page-heading"><div><div class="eyebrow">LEGADO READING SPACE</div><h1>${title}</h1><p>${subtitle}</p></div><div class="actions">${actions}</div></div>`; }
function cover(book) { return `<div class="cover" style="--cover:${book.color}"><span class="cover-title">${book.name}</span><small>${book.subtitle}</small></div>`; }
function cards(items, search = false) {
  if (!items.length) return '<div class="empty"><h2>这里暂时没有书籍</h2><p>试试其他关键词，或前往在线搜索发现一本新书。</p></div>';
  return items.map(book => `<article class="book"><button class="book-open" data-book="${book.id}" aria-label="${search ? '加入书架并阅读' : '阅读'}《${book.name}》">${cover(book)}<div class="book-details"><h3>${book.name}</h3><p>${book.author} · ${book.type}</p></div></button><div class="book-meta">${search ? '<span class="pill">演示书源</span><span>加入并阅读 →</span>' : `<span>${book.progress ? `已读 ${book.progress}%` : '未读'}</span>${book.updated ? '<span class="pill">有更新</span>' : ''}`}</div>${!search ? `<div class="progress" role="progressbar" aria-label="${book.name}阅读进度" aria-valuenow="${book.progress}" aria-valuemin="0" aria-valuemax="100"><span style="width:${book.progress}%"></span></div>` : ''}</article>`).join('');
}
function shelf() {
  const recent = books[state.book];
  return heading('我的书架', '把时间留给喜欢的故事。', '<button data-action="refresh">↻ 刷新书架</button><button class="primary" data-go="search">⌕ 找一本书</button>') +
    `<section class="hero" aria-label="继续阅读">${cover(recent)}<div><div class="eyebrow">CONTINUE READING / 继续上次阅读</div><h2>${recent.name}</h2><p class="muted">${recent.author} · ${recent.chapter}</p><div class="progress"><span style="width:${recent.progress}%"></span></div><small>已读 ${recent.progress}% · 演示进度</small></div><button class="primary" data-book="${recent.id}">继续阅读 →</button></section>` +
    `<div class="toolbar"><div class="tabs" aria-label="书架筛选">${['全部书籍', '阅读中', '未读', '已读完'].map(label => `<button data-filter="${label}" class="${state.filter === label ? 'active' : ''}" aria-pressed="${state.filter === label}">${label}</button>`).join('')}</div><div class="tools"><input id="shelf-query" aria-label="搜索书架" placeholder="搜索书名或作者" value="${escapeHtml(state.query)}"><select id="sort" aria-label="书架排序"><option value="recent">最近阅读</option><option value="name">书名排序</option><option value="progress">阅读进度</option></select><button data-action="view" aria-label="切换书架网格或列表" aria-pressed="${state.list}">${state.list ? '▦' : '☷'}</button></div></div><div id="books" class="books ${state.list ? 'list-mode' : ''}" aria-live="polite"></div>`;
}
function updateShelf() {
  let items = books.filter(b => (b.name + b.author).includes(state.query));
  if (state.filter === '阅读中') items = items.filter(b => b.progress > 0 && b.progress < 100);
  if (state.filter === '未读') items = items.filter(b => b.progress === 0);
  if (state.filter === '已读完') items = items.filter(b => b.progress === 100);
  if ($('#sort')?.value === 'name') items.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
  if ($('#sort')?.value === 'progress') items.sort((a, b) => b.progress - a.progress);
  $('#books').innerHTML = cards(items);
}
function searchPage() { return heading('在线搜索', '从 App 已启用的书源中，找到下一段好故事。') + `<form id="search-form" class="search-box"><input id="search-key" aria-label="在线搜索关键词" placeholder="输入书名或作者，例如：山海" value="${escapeHtml(state.searchKey)}" required><button class="primary">搜索书籍</button></form><p class="search-hint">选择搜索结果后将加入书架并打开阅读。当前结果为演示数据。</p>${state.searched ? `<div class="toolbar"><h2>搜索结果</h2><span class="muted">关键词：${escapeHtml(state.searchKey)}</span></div><div class="books">${cards(books.filter(b => (b.name + b.author).includes(state.searchKey)), true)}</div>` : `<div class="empty"><h2>下一本想读什么？</h2><p>输入关键词，探索你的阅读世界。</p><div class="actions" style="justify-content:center;margin-top:20px"><button data-key="山海">山海</button><button data-key="星河">星河</button><button data-key="林间客">林间客</button></div></div>`}`; }
const chapterNames = ['潮声', '渡口', '旧地图', '春山', '来信', '雨停以后', '灯塔', '归途', '远山', '夜航', '故人', '风从海上来', '新的岸', '山间清晨', '又见星光', '出发'];
function readPage() {
  const book = books[state.book];
  const chapter = `第${state.chapter}章 · ${chapterNames[state.chapter - 1]}`;
  return `<div class="reader-top"><button data-go="shelf">← 返回书架</button><span>${book.name}<small> / ${book.author}</small></span><div class="actions"><button class="mobile-only" data-action="catalog">目录</button><button data-action="focus" aria-pressed="${state.focus}">${state.focus ? '退出专注' : '专注阅读'}</button></div></div><div class="reader-layout ${state.focus ? 'focus' : ''}"><aside class="catalog"><h3>章节目录 <small>16 章</small></h3>${chapterNames.map((name, i) => `<button data-chapter="${i + 1}" class="${state.chapter === i + 1 ? 'active' : ''}" ${state.chapter === i + 1 ? 'aria-current="true"' : ''}>第${i + 1}章 · ${name}</button>`).join('')}</aside><article class="reader-paper" id="reader-paper"><h1>${chapter}</h1><div class="chapter-meta">${book.name} · 原型原创示例正文</div><div class="prose"><p>天刚亮的时候，海边的小镇还很安静。窗台上的一本旧书被风翻开，停在画着山脊的那一页。远处的雾慢慢散去，露出一条通向渡口的石板路。</p><p>他把地图折好，放进外套的口袋。昨夜的雨在屋檐下留了一点声音，如今也渐渐停了。街角的店铺升起炊烟，有人推开木门，向刚醒来的世界道了一声早安。</p><p>“你要去哪里？”守着渡口的老人问。他想了想，没有立刻回答。地图上有许多名字，有些已经去过，有些却一直只是纸上的墨迹。</p><p>风从海上来，带着盐和远方的气息。他忽然觉得，旅途的意义或许并不是抵达，而是在每一段路上，重新学会看见。</p><p>于是他向老人告别，沿着石板路走下去。阳光落在书页上，也落在那些尚未写出的故事里。</p></div><div class="chapter-nav"><button data-action="prev" ${state.chapter === 1 ? 'disabled' : ''}>← 上一章</button><small>${state.chapter} / 16</small><button data-action="next" ${state.chapter === 16 ? 'disabled' : ''}>下一章 →</button></div></article><aside class="reader-settings"><h3>阅读设置</h3><label>字体大小 <output id="font-value">${state.fontSize}</output> px<input id="font-size" type="range" min="14" max="30" value="${state.fontSize}"></label><label>行距 <output id="line-value">${state.line}</output><input id="line-height" type="range" min="1.5" max="2.8" step="0.1" value="${state.line}"></label><label>正文字体<select id="reader-font">${['宋体', '雅黑', '楷体'].map(f => `<option ${f === state.readerFont ? 'selected' : ''}>${f}</option>`).join('')}</select></label><label>阅读背景</label><div class="actions">${['纸张', '纯净', '夜间'].map(name => `<button data-paper="${name}" class="${state.paper === name ? 'selected' : ''}" aria-pressed="${state.paper === name}">${name}</button>`).join('')}</div><details class="advanced"><summary>更多排版设置</summary><label>字距<input id="letter-spacing" type="range" min="0" max="4" step="0.5" value="${state.letter}"></label><label>段距<input id="paragraph-spacing" type="range" min="0.5" max="2.5" step="0.1" value="${state.paragraph}"></label><label>阅读宽度<input id="read-width" type="range" min="480" max="1000" step="40" value="${state.width}"></label><label>翻页速度（毫秒）<input id="jump-duration" type="number" min="0" max="1000" step="100" value="${state.jump}"></label><label class="checkbox"><input id="infinite" type="checkbox" ${state.infinite ? 'checked' : ''}>无限加载</label><small>翻页速度与无限加载仅记录原型选择。</small><label>自定字体名称<input id="custom-font" placeholder="输入本机已安装的字体名称"></label></details><div class="notice">演示阅读进度已更新<br>正式版将同步至阅读 App</div></aside></div>`;
}
function styleReader() {
  const paper = $('#reader-paper');
  paper.style.setProperty('--read-size', state.fontSize + 'px'); paper.style.setProperty('--read-line', state.line);
  paper.style.maxWidth = state.width + 'px';
  paper.style.background = state.paper === '夜间' ? '#25232a' : state.paper === '纯净' ? 'var(--surface)' : 'var(--paper)';
  paper.style.color = state.paper === '夜间' ? '#e5dfd3' : 'var(--text)';
  paper.querySelectorAll('.prose p').forEach(p => { p.style.letterSpacing = state.letter + 'px'; p.style.marginBottom = state.paragraph + 'em'; p.style.fontFamily = ({ 宋体: 'SimSun, serif', 雅黑: 'Microsoft YaHei, sans-serif', 楷体: 'KaiTi, serif' })[state.readerFont] || state.readerFont; });
}
function currentDraft() {
  const key = page + ':' + state.sourceIndex;
  if (!state.drafts[key]) {
    const source = sourceData[page][state.sourceIndex];
    state.drafts[key] = page === 'rss' ? { sourceName: source.name, sourceUrl: source.url, sourceGroup: source.group, enabled: source.enabled, ruleArticles: '.article', ruleTitle: 'h2@text', ruleLink: 'a@href', ruleContent: '.content@html' } : { bookSourceName: source.name, bookSourceUrl: source.url, bookSourceGroup: source.group, enabled: source.enabled, searchUrl: '/search?q={{key}}', ruleSearch: { bookList: '.book-list .item', name: 'h3@text', author: '.author@text', bookUrl: 'a@href' } };
  }
  return state.drafts[key];
}
function field(label, key, value = '', full = false, help = '') { return `<label class="${full ? 'full' : ''}">${label}<input data-field="${key}" value="${escapeHtml(value)}">${help ? `<small class="field-help">${help}</small>` : ''}</label>`; }
function sourceFields() {
  const d = currentDraft(), rss = page === 'rss';
  if (state.sourceTab === '基础') return `<div class="form-grid">${field('源名称 *', rss ? 'sourceName' : 'bookSourceName', rss ? d.sourceName : d.bookSourceName)}${field('源分组', rss ? 'sourceGroup' : 'bookSourceGroup', rss ? d.sourceGroup : d.bookSourceGroup)}${field('源域名 *', rss ? 'sourceUrl' : 'bookSourceUrl', rss ? d.sourceUrl : d.bookSourceUrl, true, '示例地址仅供原型展示，不访问该站点。')}<label class="checkbox full"><input type="checkbox" data-field="enabled" ${d.enabled ? 'checked' : ''}>启用${rss ? '订阅源' : '书源'}</label>${!rss ? `<label>源类型<select data-field="bookSourceType"><option value="0">文本</option><option value="1">音频</option><option value="2">图片</option><option value="3">文件</option></select></label>` : field('分类地址', 'sortUrl', d.sortUrl)}${field('源注释', rss ? 'sourceComment' : 'bookSourceComment', rss ? d.sourceComment : d.bookSourceComment, true)}</div><details class="advanced"><summary>高级选项 · 登录、请求头、JS 库</summary><div class="form-grid">${field('登录地址', 'loginUrl', d.loginUrl, true)}${field('请求头', 'header', d.header, true)}${field('JS 库', 'jsLib', d.jsLib, true)}</div></details>`;
  const config = rss ? { 列表: [['列表规则', 'ruleArticles'], ['标题规则', 'ruleTitle'], ['链接规则', 'ruleLink']], 内容: [['正文规则', 'ruleContent'], ['下一页规则', 'ruleNextPage']] } : { 搜索: [['搜索地址', 'searchUrl'], ['列表规则', 'ruleSearch.bookList'], ['书名规则', 'ruleSearch.name'], ['作者规则', 'ruleSearch.author'], ['书籍链接', 'ruleSearch.bookUrl']], 发现: [['发现地址', 'exploreUrl'], ['列表规则', 'ruleExplore.bookList']], 详情: [['书名规则', 'ruleBookInfo.name'], ['简介规则', 'ruleBookInfo.intro'], ['目录链接', 'ruleBookInfo.tocUrl']], 目录: [['章节列表', 'ruleToc.chapterList'], ['章节名称', 'ruleToc.chapterName'], ['章节链接', 'ruleToc.chapterUrl']], 正文: [['正文规则', 'ruleContent.content'], ['下一页规则', 'ruleContent.nextContentUrl']] };
  return `<div class="form-grid">${(config[state.sourceTab] || []).map(([name, key]) => { const [a, b] = key.split('.'); return field(name, key, b ? d[a]?.[b] : d[a], true); }).join('')}</div><small class="field-help">支持 CSS、XPath、JSONPath、正则与 JS。原型展示代表字段；正式版沿用完整配置。</small>`;
}
function sourcesPage() {
  const rss = page === 'rss', sources = sourceData[page], d = currentDraft(), name = rss ? d.sourceName : d.bookSourceName;
  const tabs = rss ? ['基础', '列表', '内容'] : ['基础', '搜索', '发现', '详情', '目录', '正文'];
  return heading(titles[page], rss ? '整理订阅规则，在一个工作区完成编辑与调试。' : '让规则编辑更清晰，让每次调试都有迹可循。', '<button data-action="pull">↓ 从 App 拉取</button><button data-action="push">↑ 批量推送</button>') + `<div class="source-layout"><aside class="source-list"><h3>源列表 <small>${sources.length}</small></h3><input id="source-query" aria-label="筛选源列表" placeholder="搜索源名称"><div id="source-rows" style="width:100%">${sources.map((s, i) => `<button data-source="${i}" class="source-row ${state.sourceIndex === i ? 'active' : ''}">${escapeHtml(s.name)}<small>${escapeHtml(s.group)} · ${s.enabled ? '已启用' : '已停用'}</small></button>`).join('')}</div><button data-action="new-source">＋ 新建源</button><button data-action="delete-source">删除当前源</button></aside><section class="editor"><div class="editor-header"><div><h2>${escapeHtml(name)}</h2><small id="source-status">${state.sourceDirty ? '有未保存修改' : '所有修改已保存 · 演示状态'}</small></div><div class="actions"><button data-mode="form" class="${state.editorMode === 'form' ? 'selected' : ''}">表单</button><button data-mode="json" class="${state.editorMode === 'json' ? 'selected' : ''}">JSON</button></div></div><div class="editor-body">${state.editorMode === 'form' ? `<div class="tabs" aria-label="规则分区">${tabs.map(t => `<button data-source-tab="${t}" class="${state.sourceTab === t ? 'active' : ''}" aria-pressed="${state.sourceTab === t}">${t}</button>`).join('')}</div>${sourceFields()}` : `<label>源 JSON<textarea id="source-json" class="code" spellcheck="false">${escapeHtml(JSON.stringify(d, null, 2))}</textarea></label><small>切回表单或保存时校验并同步 JSON。</small>`}<details class="debug" ${state.debugStatus !== 'idle' ? 'open' : ''}><summary>调试输出 ${state.debugStatus === 'running' ? '· 运行中' : state.debugStatus === 'done' ? '· 演示完成' : ''}</summary><div class="actions"><input id="debug-key" aria-label="调试关键词或地址" placeholder="${rss ? '输入文章地址' : '输入书名或作者'}" style="flex:1" value="${rss ? 'https://reading.example.com/article' : '山海'}"><button data-action="debug" ${state.debugStatus === 'running' ? 'disabled' : ''}>运行调试</button></div><pre id="debug-output" aria-live="polite">${state.debugStatus === 'done' ? '[演示] 开始解析规则\n[演示] 获取列表 → 6 条结果\n[演示] 解析标题、链接 → 完成\n[演示] 本次调试结束；未请求真实书源。' : state.debugStatus === 'running' ? '[演示] 正在解析规则…' : '运行调试后在这里查看结果。仅演示输出，不执行规则。'}</pre></details></div><div class="editor-bottom"><div class="actions"><button data-action="undo">↶ 撤销</button><button data-action="clear-form">清空表单</button><button data-action="help">规则帮助</button><button data-action="shortcuts">快捷键</button></div><button class="primary" data-action="save-source">保存到 App</button></div></section></div>`;
}
function settingsPage() { return heading('连接与设置', '管理阅读 App 的连接，以及这个阅读空间的外观。') + `<div class="settings-grid"><section class="panel"><h2>连接阅读 App</h2><p>在 App 中开启 Web 服务后，填写页面显示的地址。</p><form id="connection-form"><label>服务地址<input id="address" type="url" value="${escapeHtml(state.address)}" required placeholder="http://127.0.0.1:9527"></label><div class="notice">${state.connected ? '● 演示连接已建立' : '○ 演示连接已断开'}<br>原型仅校验地址格式；正式版需验证服务与配置。</div><div class="actions"><button class="primary">测试并连接</button><button type="button" data-action="disconnect">断开连接</button></div></form></section><section class="panel"><h2>外观与阅读</h2><p>跟随你的习惯，保持简单。</p><div class="setting-row"><span>界面主题</span><button data-action="theme">切换深浅主题</button></div><div class="setting-row"><span>阅读排版</span><button data-go="read">调整阅读设置 →</button></div><div class="setting-row"><span>数据状态</span><span class="pill">演示数据</span></div><small style="display:block;margin-top:20px">正式版由 App 提供书架、规则及阅读配置；浏览器侧保留连接与界面偏好。</small></section></div>`; }
function render() {
  const nextPage = location.hash.slice(1) || 'shelf';
  page = titles[nextPage] ? nextPage : 'shelf';
  if (page === 'sources' || page === 'rss') { state.sourceIndex = Math.min(state.sourceIndex, sourceData[page].length - 1); if (!(page === 'rss' ? ['基础', '列表', '内容'] : ['基础', '搜索', '发现', '详情', '目录', '正文']).includes(state.sourceTab)) state.sourceTab = '基础'; }
  $('#crumb').textContent = titles[page];
  document.querySelectorAll('nav a').forEach(a => { const active = a.dataset.page === page || (page === 'read' && a.dataset.page === 'shelf'); a.classList.toggle('active', active); if (active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  $('#main').innerHTML = ({ shelf, search: searchPage, read: readPage, sources: sourcesPage, rss: sourcesPage, settings: settingsPage })[page]();
  if (page === 'shelf') updateShelf(); if (page === 'read') styleReader();
}
function showModal(title, content, actionLabel = '知道了', action = null) {
  $('#modal-content').innerHTML = `<h2>${title}</h2>${content}<div class="actions">${action ? '<button id="modal-cancel">取消</button>' : ''}<button class="primary" id="modal-confirm">${actionLabel}</button></div>`;
  $('#modal-cancel')?.addEventListener('click', () => $('#modal').close());
  $('#modal-confirm').addEventListener('click', () => { $('#modal').close(); action?.(); }); $('#modal').showModal();
}
function commitJson() {
  if (!$('#source-json')) return true;
  try { const parsed = JSON.parse($('#source-json').value); if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(); state.undo.push({ key: page + ':' + state.sourceIndex, draft: structuredClone(currentDraft()) }); state.drafts[page + ':' + state.sourceIndex] = parsed; state.sourceDirty = true; return true; }
  catch { toast('JSON 格式错误，请检查后再继续'); return false; }
}
function updateConnection() { $('#connection-label').innerHTML = `${state.connected ? '演示连接' : '未连接'}<small>阅读 App · 原型数据</small>`; }
const actions = {
  theme: () => { document.documentElement.dataset.theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'; },
  refresh: () => toast('演示书架已刷新，共 6 本书'),
  view: () => { state.list = !state.list; render(); },
  focus: () => { state.focus = !state.focus; render(); },
  catalog: () => $('.catalog').classList.toggle('open'),
  prev: () => { state.chapter = Math.max(1, state.chapter - 1); render(); },
  next: () => { state.chapter = Math.min(16, state.chapter + 1); render(); },
  pull: () => toast('演示：源列表已从 App 拉取'),
  push: () => showModal('批量推送', `<p>将向 App 推送当前 ${sourceData[page].length} 个${page === 'rss' ? '订阅源' : '书源'}。同地址源会覆盖原配置。</p><small>本次仅演示确认流程，不发送数据。</small>`, '确认推送', () => toast('演示：批量推送完成')),
  'save-source': () => { if (!commitJson()) return; const d = currentDraft(); const name = page === 'rss' ? d.sourceName : d.bookSourceName, url = page === 'rss' ? d.sourceUrl : d.bookSourceUrl; try { if (!String(name || '').trim() || !/^https?:$/.test(new URL(url).protocol)) throw new Error(); } catch { toast('请填写源名称与有效的 HTTP / HTTPS 域名'); return; } Object.assign(sourceData[page][state.sourceIndex], { name, url, group: (page === 'rss' ? d.sourceGroup : d.bookSourceGroup) || '', enabled: d.enabled }); state.sourceDirty = false; render(); toast('演示：源已保存，未写入 App'); },
  undo: () => { const index = state.undo.findLastIndex(item => item.key === page + ':' + state.sourceIndex); if (index < 0) return toast('当前源没有可撤销的操作'); const item = state.undo.splice(index, 1)[0]; state.drafts[item.key] = item.draft; state.sourceDirty = true; render(); },
  'clear-form': () => showModal('清空当前表单', '<p>清空当前源的演示字段，可通过撤销恢复。</p>', '清空', () => { const key = page + ':' + state.sourceIndex; state.undo.push({ key, draft: structuredClone(currentDraft()) }); state.drafts[key] = { enabled: true }; state.sourceDirty = true; render(); }),
  'new-source': () => { sourceData[page].push({ name: '未命名源', url: 'https://new.example.com', group: '未分组', enabled: true }); state.sourceIndex = sourceData[page].length - 1; state.sourceDirty = true; state.sourceTab = '基础'; render(); },
  'delete-source': () => { if (sourceData[page].length === 1) return toast('原型至少保留一个演示源'); showModal('删除当前源', '<p>从演示列表移除此源。此操作只影响当前原型。</p>', '删除', () => { const kind = page, removed = state.sourceIndex; sourceData[kind].splice(removed, 1); const remaining = {}; Object.entries(state.drafts).forEach(([key, value]) => { const [p, idx] = key.split(':'); if (p !== kind) remaining[key] = value; else if (+idx < removed) remaining[key] = value; else if (+idx > removed) remaining[p + ':' + (+idx - 1)] = value; }); state.drafts = remaining; state.undo = state.undo.filter(item => !item.key.startsWith(kind + ':')); state.sourceIndex = 0; state.sourceDirty = false; render(); toast('演示源已删除'); }); },
  help: () => showModal('规则帮助', '<p>CSS：<code>.book-list .item</code><br>文本：<code>h3@text</code><br>链接：<code>a@href</code><br>规则也支持 XPath、JSONPath、正则与 JS。</p><small>正式版保留现有帮助内容及全部规则字段。</small>'),
  shortcuts: () => showModal('快捷键', '<p>Ctrl / ⌘ + S：保存当前源<br>Esc：关闭弹窗<br>← / →：阅读时切换章节</p><small>正式版保留现有可自定义快捷键配置。</small>'),
  debug: () => { if (!commitJson()) return; const debugPage = page; state.debugStatus = 'running'; $('#debug-output').textContent = '[演示] 开始解析规则…'; const button = $('[data-action="debug"]'); button.disabled = true; button.textContent = '调试中…'; clearTimeout(state.debugTimer); state.debugTimer = setTimeout(() => { state.debugStatus = 'done'; if (page === debugPage) { render(); toast('演示调试完成，未访问真实书源'); } }, 900); },
  disconnect: () => { state.connected = false; updateConnection(); render(); toast('演示连接已断开'); },
};
document.addEventListener('click', event => {
  const el = event.target.closest('button'); if (!el) return;
  if (el.dataset.go) go(el.dataset.go);
  if (el.dataset.action) actions[el.dataset.action]?.();
  if (el.dataset.book !== undefined) { state.book = +el.dataset.book; state.chapter = state.book === 0 ? 12 : 1; go('read'); }
  if (el.dataset.filter) { state.filter = el.dataset.filter; render(); }
  if (el.dataset.key) { state.searchKey = el.dataset.key; state.searched = true; render(); }
  if (el.dataset.chapter) { state.chapter = +el.dataset.chapter; render(); }
  if (el.dataset.paper) { state.paper = el.dataset.paper; render(); }
  if (el.dataset.source !== undefined) { if (!commitJson()) return; state.sourceIndex = +el.dataset.source; state.sourceDirty = false; state.debugStatus = 'idle'; render(); }
  if (el.dataset.sourceTab) { state.sourceTab = el.dataset.sourceTab; render(); }
  if (el.dataset.mode) { if (!commitJson()) return; state.editorMode = el.dataset.mode; render(); }
});
document.addEventListener('input', event => {
  const el = event.target;
  if (el.id === 'shelf-query') { state.query = el.value; updateShelf(); }
  if (el.id === 'source-query') document.querySelectorAll('[data-source]').forEach(row => row.hidden = !row.textContent.includes(el.value));
  if (el.dataset.field) {
    state.undo.push({ key: page + ':' + state.sourceIndex, draft: structuredClone(currentDraft()) }); if (state.undo.length > 60) state.undo.shift();
    const [parent, child] = el.dataset.field.split('.'), d = currentDraft();
    const value = el.type === 'checkbox' ? el.checked : el.tagName === 'SELECT' ? +el.value : el.value;
    if (child) { d[parent] ||= {}; d[parent][child] = value; } else d[parent] = value;
    state.sourceDirty = true; $('#source-status').textContent = '有未保存修改';
  }
  if (el.id === 'source-json') { state.sourceDirty = true; $('#source-status').textContent = '有未保存修改'; }
  if (el.id === 'font-size') { state.fontSize = +el.value; $('#font-value').textContent = el.value; styleReader(); }
  if (el.id === 'line-height') { state.line = +el.value; $('#line-value').textContent = el.value; styleReader(); }
  const readerFields = { 'letter-spacing': 'letter', 'paragraph-spacing': 'paragraph', 'read-width': 'width', 'jump-duration': 'jump' };
  if (readerFields[el.id]) { state[readerFields[el.id]] = +el.value; styleReader(); }
  if (el.id === 'infinite') state.infinite = el.checked;
  if (el.id === 'custom-font' && el.value.trim()) { state.readerFont = el.value.trim(); styleReader(); }
});
document.addEventListener('change', event => { if (event.target.id === 'sort') updateShelf(); if (event.target.id === 'reader-font') { state.readerFont = event.target.value; styleReader(); } });
document.addEventListener('submit', event => { event.preventDefault(); if (event.target.id === 'search-form') { state.searchKey = $('#search-key').value.trim(); if (!state.searchKey) return toast('请输入书名或作者'); state.searched = true; render(); } if (event.target.id === 'connection-form') { const value = $('#address').value.trim(); try { if (!/^https?:$/.test(new URL(value).protocol)) throw new Error(); state.address = value; state.connected = true; updateConnection(); render(); toast('地址格式有效，演示连接成功'); } catch { toast('请输入有效的 HTTP / HTTPS 地址'); } } });
document.addEventListener('keydown', event => { if ($('#modal').open) return; const editing = /INPUT|TEXTAREA|SELECT/.test(event.target.tagName); if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && ['sources', 'rss'].includes(page)) { event.preventDefault(); actions['save-source'](); } if (!editing && page === 'read' && ['ArrowLeft', 'ArrowRight'].includes(event.key)) { event.preventDefault(); actions[event.key === 'ArrowLeft' ? 'prev' : 'next'](); } });
$('#theme-toggle').addEventListener('click', actions.theme);
window.addEventListener('hashchange', () => { render(); window.scrollTo(0, 0); });
render();
