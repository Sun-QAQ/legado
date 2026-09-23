# NG JS 书源

在「书源管理 → 本地导入」选择 `.js` 文件，也可粘贴脚本或通过网络地址导入。
导入后的脚本保存在书源 `jsLib` 字段（以 `// legado:ng-js:v1` 标记），可以正常导出 JSON、备份和恢复。

## 脚本接口

```javascript
var config = {
    bookSourceUrl: "https://example.org/#ng",
    bookSourceName: "示例"
};
function search(key, page) { return [{name: "书名", author: "作者", bookUrl: "https://example.org/book/1"}]; }
function getBookInfo(book) { return {name: book.name, tocUrl: book.bookUrl}; }
function getChapters(book) { return [{title: "第一章", url: book.bookUrl + "/1"}]; }
function getContent(chapter, book, nextChapterUrl) { return "正文"; }
```

`config` 必须是顶层声明的对象字面量，支持字符串（含转义）、数字、布尔值、null、数组及嵌套对象。
导入阶段只解析语法与配置，不执行脚本。上述四个函数必须为顶层函数声明。
运行阶段使用 Rhino，同步调用函数；不支持 ES module、Node.js 模块或 Promise/async 返回值。
每次调用使用独立作用域，持久数据请使用 `source.get/put` 或 `source.getLoginInfo/putLoginInfo`。
网络请求使用 `java.ajax` 等现有阅读 JS 接口，自动沿用书源的请求头、Cookie 和限流设置。
内置 CryptoJS 4.2.0；单次调用最长 60 秒。

搜索条目支持 `name`、`author`、`bookUrl`、`coverUrl`、`intro`、`kind`、`wordCount`、`latestChapterTitle`。
详情额外支持 `tocUrl`；目录支持 `title`、`url`、`isVolume`、`isVip`、`isPay`、`tag`。
可选 `explore(url, page)` 返回与搜索相同格式，配合 `config.exploreUrl` 使用。

## 登录和段评

可选 `loginUi(state)` 返回 `{rows: [...]}`（也兼容直接返回数组），支持 text/password/button 和按钮布局 style。
按钮调用 `loginAction(action, formData, state)`；返回 `{state: ...}` 时自动刷新界面。
关闭登录页不会清除脚本保存的设备或账号信息。

支持正文图片 URL 选项中的 `style: "TEXT"` 和 `click: "JavaScript"`。
点击脚本可调用 `java.showBrowser(url, html, injectedJs, options)` 打开书源 HTML。
页面提供 Promise 形式的 `run(code)` 和 `imageToPngDataUrlAwait(url)`，用于读取评论和转换图片。
当前浏览窗口使用 App 的完整页面，`options` 中的底部抽屉高度、圆角、拖动等外观选项暂不应用。
窗口禁止跳转、子框架、外部脚本和本地文件访问；远端图片可正常显示。

CryptoJS 来自 npm `crypto-js@4.2.0`，MIT 许可证见 `assets/js/crypto-js.LICENSE`。
