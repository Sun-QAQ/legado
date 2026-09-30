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
})

export default router
