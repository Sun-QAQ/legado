// 源接口类型由路由切换，不能在模块导入时固定。
export const sourceMode = ref<'book' | 'rss'>(
  /bookSource/i.test(location.href) ? 'book' : 'rss',
)
export const selectSourceMode = (path: string) => {
  if (/bookSource|rssSource/i.test(path))
    sourceMode.value = /bookSource/i.test(path) ? 'book' : 'rss'
}
