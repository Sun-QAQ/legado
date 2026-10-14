import { createWebHashHistory, createRouter } from 'vue-router'

export const bookRoutes = [
  {
    path: '/explore',
    name: 'explore',
    component: () => import('../views/BookExplore.vue'),
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('../views/AiChat.vue'),
  },
  {
    path: '/upload',
    name: 'upload',
    component: () => import('../views/BookUpload.vue'),
  },
  {
    path: '/',
    name: 'shelf',
    component: () => import('../views/BookShelf.vue'),
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('../views/BookShelf.vue'),
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/WebSettings.vue'),
  },
  {
    path: '/book',
    name: 'book-detail',
    component: () => import('../views/BookDetail.vue'),
  },
  {
    path: '/chapter',
    name: 'chapter',
    component: () => import('../views/BookChapter.vue'),
  },
]

const router = createRouter({
  // mode: "history",
  history: createWebHashHistory(),
  routes: bookRoutes,
})

export default router
