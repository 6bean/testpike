import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import { i18n } from '@/utils/i18n'
import { routes } from './routes'
import { createAuthGuard } from './sessionGuard'


const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// Guard decision logic lives in ./sessionGuard.ts (single source, node-layer L2 testable);
// this file only wires real deps. A network blip is NOT an auth failure: session kept +
// human toast + navigation held; only real auth failures (401/403) clear state to /login.
const authGuard = createAuthGuard({
  hasToken: () => !!getToken(),
  getAuth: () => useAuthStore(),
  error: (m) => ElMessage.error(m),
  warning: (m) => ElMessage.warning(m),
  t: (key, named) => i18n.global.t(key, named ?? {}),
})

router.beforeEach((to) =>
  authGuard({
    name: to.name,
    path: to.path,
    fullPath: to.fullPath,
    meta: {
      public: to.meta.public === true,
      perm: to.meta.perm == null ? undefined : String(to.meta.perm),
    },
  }),
)

router.afterEach((to) => {
  const key = to.meta.titleKey as string | undefined
  const title = key ? i18n.global.t(key) : ''
  document.title = title && title !== key ? `${title} · testPike` : 'testPike'
})

export default router
