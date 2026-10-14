import { createWebHashHistory, createRouter } from 'vue-router'
import { bookRoutes } from './bookRouter'
import { sourceRoutes } from './sourceRouter'
import { selectSourceMode } from '@/config/sourceContext'

const router = createRouter({
  //   history: createWebHistory(process.env.BASE_URL),
  history: createWebHashHistory(),
  routes: [bookRoutes, sourceRoutes].flat(),
})

router.beforeResolve(to => selectSourceMode(to.path))
router.afterEach(to => {
  if (to.name == 'shelf') document.title = '书架'
  if (to.name == 'search') document.title = '在线搜索'
  if (to.name == 'upload') document.title = '传书'
  if (to.name == 'chat') document.title = 'AI 对话'
  if (to.name == 'book-detail') document.title = '书籍详情'
})

export default router
