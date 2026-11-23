<template>
  <el-container class="layout-root">
 
    <el-aside :width="collapsed ? `${SIDEBAR_COLLAPSED_WIDTH}px` : `${sidebarWidth}px`" class="layout-aside">
      <div class="brand" @click="router.push('/dashboard')">
        <el-icon :size="24" class="brand-icon"><Aim /></el-icon>
        <span v-show="!collapsed" class="brand-text">testPike</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="transparent"
        text-color="var(--tp-sidebar-text)"
        active-text-color="var(--tp-sidebar-text-active)"
        router
        class="layout-menu"
      >
 <!-- Two-level menu rendering (site-wide depth ruling): leaves show/hide by their own
             perm, a group shows when any leaf is visible; the three-level sub-group
             rendering branch was removed entirely so it cannot come back. -->
        <template v-for="grp in menuGroups" :key="grp.index">
          <el-sub-menu v-if="visibleLeafCount(grp) > 0" :index="grp.index"
            :data-test="`group-${grp.index}`">
            <template #title>
              <el-icon><component :is="grp.icon" /></el-icon>
              <span>{{ t(grp.titleKey) }}</span>
            </template>
            <el-menu-item v-for="item in visibleItems(grp.items)" :key="item.path" :index="item.path"
              :data-test="item.dataTest">
              <el-icon><component :is="item.icon" /></el-icon>
 
              <template #title>
                <span class="menu-leaf-label">{{ t(item.titleKey) }}</span>
                <el-icon v-show="!collapsed" class="menu-leaf-help"
                  :data-test="`${item.dataTest}-help`"
                  @click.stop="openHelp(item.helpDomain, item.helpAnchorKey ? t(item.helpAnchorKey) : '')">
                  <QuestionFilled />
                </el-icon>
              </template>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
 
      <div v-show="!collapsed" class="aside-resizer" data-test="sidebar-resizer"
        @mousedown="onResizeStart" @dblclick="onResizeReset"></div>
    </el-aside>

    <el-container class="layout-body">
      
      <el-header class="layout-header" height="56px">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="toggleCollapse">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
          <span class="header-title">{{ t(currentTitleKey) }}</span>
          <!-- R23 page-name single source: the "?" entry rides the topbar right of
               the page name (one point site-wide). Domain/anchor come from the menu
               leaf single source; detail pages fall back to their parent leaf. -->
          <el-tooltip v-if="currentHelpLeaf" :content="t(currentHelpLeaf.helpTipKey)" placement="bottom">
            <el-icon class="header-help-btn" :size="16" data-test="header-help-btn"
              @click="openHelp(currentHelpLeaf.helpDomain, currentHelpLeaf.helpAnchorKey ? t(currentHelpLeaf.helpAnchorKey) : '')">
              <QuestionFilled />
            </el-icon>
          </el-tooltip>
          <AppBreadcrumb :items="breadcrumbItems" class="header-breadcrumb" />
        </div>
        <div class="header-right">
 <!-- Header global project context (single-select contract): the visible-project
 dropdown sits directly on the bar, picking a project takes effect immediately;
 pick once and every page inherits, persisted per user, invisible-domain pruning
 raises a plain-word toast, empty state points to the project page -->
          <!-- Program lens switcher : zero-touch — the control is not
 rendered at all when the login snapshot carries no programs.
 Picking a program narrows the project switcher (and every scoped
 page) to that program's projects; clearing it restores everything. -->
          <el-select
            v-if="authStore.programs.length > 0"
            class="prog-ctx-select"
            data-test="program-ctx-select"
            style="width: 160px"
            :model-value="progCtx.activeProgramId"
            filterable
            clearable
            :placeholder="t('layout.programCtx.all')"
            :title="t('layout.programCtx.tip')"
            @update:model-value="onProgramCtxChange"
          >
            <el-option
              v-for="p in progCtx.programOptions"
              :key="p.programId"
              :label="p.name"
              :value="p.programId"
            />
          </el-select>
          <div class="proj-ctx-trigger" data-test="project-ctx-trigger" :title="t('layout.projectCtx.tip')">
            <el-icon :size="14"><FolderOpened /></el-icon>
            <ProjectSelect
              data-test="project-ctx-select"
              :model-value="projCtx.primaryId"
              :options="projCtxOptions"
              width="180px"
              :placeholder="lensEmptyHint ? t('layout.programCtx.empty') : t('layout.projectCtx.none')"
              @update:model-value="onProjCtxChange"
            />
          </div>
          <span v-if="authStore.projects.length === 0" class="proj-ctx-hint" data-test="project-ctx-hint">
            <span>{{ t('layout.projectCtx.noVisible') }}</span>
            <el-button link type="primary" size="small" data-test="project-ctx-goto" @click="gotoProjectsPage">
              {{ t('layout.projectCtx.gotoProjects') }}
            </el-button>
          </span>
 
          <div class="gs-trigger" data-test="global-search-trigger" @click="searchVisible = true">
            <el-icon :size="14"><Search /></el-icon>
            <span class="gs-trigger-text">{{ t('layout.search') }}</span>
            <span class="gs-trigger-kbd">Ctrl K</span>
          </div>
          <el-tooltip :content="t(themeTipKey)" placement="bottom">
            <el-icon class="header-icon" @click="appStore.toggleTheme()">
              <Sunny v-if="appStore.theme === 'dark'" />
              <Moon v-else />
            </el-icon>
          </el-tooltip>
 
          <LangSwitch />
 <!-- Help entry moved to the page name (R23 single point) — no second "?" in the bar -->
 
          <el-popover
            v-if="notifPermVisible"
            v-model:visible="notifPanelVisible"
            placement="bottom-end"
            :width="380"
            trigger="click"
            popper-class="notif-popover"
          >
            <template #reference>
              <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99" class="notif-badge" data-test="notif-bell">
                <el-icon class="header-icon" :size="18"><Bell /></el-icon>
              </el-badge>
            </template>
            <div class="notif-panel" data-test="notif-panel">
              <div class="notif-panel-header">
                <span class="notif-panel-title">{{ t('layout.notifPanelTitle') }}</span>
                <span class="notif-panel-ops">
                  <el-button v-if="unreadCount > 0" link type="primary" size="small" data-test="notif-read-all" @click="markAllRead">
                    {{ t('layout.markAllRead') }}
                  </el-button>
 
                  <el-button link size="small" data-test="notif-view-all" @click="gotoNotifCenter">{{ t('layout.viewAll') }}</el-button>
                </span>
              </div>
              <div class="notif-list">
                <div v-for="n in notifications" :key="n.id" class="notif-item" :class="{ unread: n.readFlag === 0 }" data-test="notif-item">
                  <div class="notif-item-top">
                    <el-tag :type="eventTagType(n.eventType)" size="small">
                      {{ eventLabel(n.eventType) }}
                    </el-tag>
                    <span v-if="n.readFlag === 0" class="notif-dot" />
                  </div>
                  <div class="notif-item-title">{{ n.title }}</div>
                  <div class="notif-item-content">{{ n.content }}</div>
                  <div class="notif-item-bottom">
                    <span class="notif-time">{{ formatNotifTime(n.createdAt) }}</span>
                    <span class="notif-actions">
                      <el-button link type="primary" size="small" data-test="notif-detail" @click="openNotif(n)">{{ t('layout.viewDetail') }}</el-button>
                      <el-button
                        v-if="n.eventType.includes('FAILED')"
                        link
                        type="danger"
                        size="small"
                        data-test="notif-retry"
                        @click="retryFromNotif(n)"
                      >
                        {{ t('layout.retry') }}
                      </el-button>
                    </span>
                  </div>
                </div>
                <el-empty v-if="notifications.length === 0" :description="t('layout.notifEmpty')" :image-size="60" />
              </div>
            </div>
          </el-popover>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="user-info">
              <el-avatar
                :size="28"
                :src="authStore.user?.avatar || undefined"
                class="user-avatar"
                data-test="header-avatar"
              >
                {{ authStore.displayName.charAt(0) }}
              </el-avatar>
              <span class="user-name">{{ authStore.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ authStore.user?.username }}</el-dropdown-item>
                <el-dropdown-item divided command="profile" data-test="dropdown-profile">
                  <el-icon><User /></el-icon>{{ t('layout.profileCenter') }}
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>{{ t('layout.logout') }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

 
      <el-main class="layout-main">
        <router-view v-slot="{ Component }">
          <keep-alive :include="CACHED_LIST_VIEWS">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
 
    <GlobalSearchDialog v-model:visible="searchVisible" />
    <!-- Global AI chat dock + bottom-right floating entry bubble :
 mounted exactly once here; the bubble hides while the dock is open; position
 and width are per-user localStorage preferences, never server state. -->
    <ChatDock />
    <ChatFab />
 
    <HelpDrawer />
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore } from '@/stores/projectContext'
import { useProgramContextStore } from '@/stores/programContext'
import { parseProgramIdQuery } from '@/composables/useProgramLens'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  eventLabel,
  eventTagType,
  type NotificationItem,
} from '@/api/notification'
import { retryAiTask } from '@/api/ai'
import GlobalSearchDialog from '@/components/GlobalSearchDialog.vue'
import HelpDrawer from '@/components/HelpDrawer.vue'
import ChatDock from '@/components/ChatDock.vue'
import ChatFab from '@/components/ChatFab.vue'
import ProjectSelect, { type ProjectOption } from '@/components/ProjectSelect.vue'
import { openHelp } from '@/composables/useHelpDrawer'
import AppBreadcrumb from '@/components/AppBreadcrumb.vue'
import LangSwitch from '@/components/LangSwitch.vue'
import { useBreadcrumbTitles, type BreadcrumbItem } from '@/composables/useBreadcrumb'
import { useFormHotkeys } from '@/composables/useFormHotkeys'
import {
  SIDEBAR_COLLAPSED_WIDTH,
  SIDEBAR_DEFAULT_WIDTH,
  clampSidebarWidth,
  readSidebarCollapsed,
  readSidebarWidth,
  writeSidebarCollapsed,
  writeSidebarWidth,
} from '@/composables/useSidebarPrefs'
import {
  journeyGroups,
  groupLeaves,
  filterVisibleLeaves,
  allMenuLeaves,
  type MenuLeaf,
  type JourneyGroup,
  resolveMenuTitleKey,
} from '@/config/menuStructure'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const appStore = useAppStore()
const authStore = useAuthStore()


const searchVisible = ref(false)

useFormHotkeys()


// AN-5: RequirementList / DocumentList / RegressionSuite no longer mount
// as standalone routes (merged into tab hosts) — only the live route names
// stay in the cache whitelist, otherwise a retired name silently dead-weights
// the include list.
const CACHED_LIST_VIEWS = ['CaseList', 'PlanList', 'BatchList', 'DefectLibrary', 'DataFactoryList', 'ExtensionsHost', 'DocumentList', 'RequirementList']

const collapsed = ref(false)

const sidebarWidth = ref(SIDEBAR_DEFAULT_WIDTH)


function prefBucket(): number | undefined {
  return authStore.user?.userId
}

function applySidebarPrefs(): void {
  sidebarWidth.value = readSidebarWidth(prefBucket())
  collapsed.value = readSidebarCollapsed(prefBucket())
}

watch(() => authStore.user?.userId, applySidebarPrefs, { immediate: true })


function onResizeStart(e: MouseEvent): void {
  if (e.button !== 0) return
  const startX = e.clientX
  const startW = sidebarWidth.value
  document.body.style.userSelect = 'none'
  document.body.classList.add('is-resizing-sidebar')
  const onMove = (ev: MouseEvent) => {
    sidebarWidth.value = clampSidebarWidth(startW + (ev.clientX - startX))
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    document.body.style.userSelect = ''
    document.body.classList.remove('is-resizing-sidebar')
    writeSidebarWidth(prefBucket(), sidebarWidth.value)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}


function onResizeReset(): void {
  sidebarWidth.value = SIDEBAR_DEFAULT_WIDTH
  writeSidebarWidth(prefBucket(), SIDEBAR_DEFAULT_WIDTH)
}


function toggleCollapse(): void {
  collapsed.value = !collapsed.value
  writeSidebarCollapsed(prefBucket(), collapsed.value)
}


const menuGroups = journeyGroups


function visibleItems(items: MenuLeaf[] | undefined): MenuLeaf[] {
  return filterVisibleLeaves(items ?? [], (code) => authStore.hasPerm(code))
}

/** Group visibility rule = at least one visible leaf inside the group. */
function visibleLeafCount(grp: JourneyGroup): number {
  return visibleItems(groupLeaves(grp)).length
}

const notifPanelVisible = ref(false)
const notifications = ref<NotificationItem[]>([])
const unreadCount = ref(0)
let notifTimer: number | null = null


const notifPermVisible = computed(() => authStore.hasPerm('perm:ux:notify-center'))

// ---- Header global project context (store=projectContext stays framework-free;
// this layer only wires the single-select dropdown and consumes the prune toast) ----
// Single-select contract: the header dropdown picks ONE project and takes effect
// immediately; the "+N" summary popover of the multi-select era is retired.
const projCtx = useProjectContextStore()
// Program lens : the header picks the program once; project options
// are pruned through the store's inScope() single source (lens off = untouched).
const progCtx = useProgramContextStore()

const projCtxOptions = computed<ProjectOption[]>(() =>
  authStore.projects
    .map((p) => ({ projectId: p.projectId, projectName: p.projectName, projectCode: p.projectCode }))
    .filter((p) => progCtx.inScope(p.projectId)),
)
/** Honest empty echo: a picked program currently carries no visible projects. */
const lensEmptyHint = computed(() => progCtx.activeProgramId != null && projCtxOptions.value.length === 0)
function onProgramCtxChange(v: unknown): void {
  progCtx.select(typeof v === 'number' ? v : null)
}
function onProjCtxChange(v: number | number[] | null): void {
  projCtx.select(typeof v === 'number' ? [v] : [])
}
// Lens switch: when the picked project falls out of the new scope, move to the
// first in-scope visible project (or clear) — the header never shows a project
// the current program does not contain.
watch([() => projCtx.primaryId, () => progCtx.activeProgramId], () => {
  const pid = projCtx.primaryId
  if (pid != null && !progCtx.inScope(pid)) {
    const first = authStore.projects.find((p) => progCtx.inScope(p.projectId))
    projCtx.select(first ? [first.projectId] : [])
  }
})
// Deep-linked lens landing: a URL ?programId pins the header lens
// (the store stays the single owner — pages never keep a second copy) and
// clears the stale single-project default, so the list pages query the
// program-wide branch (server-side lens ∩ caller visibility, projectId=0
// anchor). A URL WITHOUT the param never detaches the lens: the query value
// is a landing instruction, not the lens authority (context is filter state,
// not an authorization credential — every request stays server-guarded).
watch(
  () => route.query.programId,
  (raw) => {
    const landed = parseProgramIdQuery(raw)
    if (landed == null) return
    if (landed !== progCtx.activeProgramId) progCtx.select(landed)
    if (route.query.projectId == null && projCtx.primaryId != null) projCtx.select([])
    // The login snapshot may lag a program created after login (same posture
    // as the project-side deep-link refresh): re-read the read-only visibility
    // snapshot once; if the id stays invisible the store's relax watcher
    // honestly drops the lens back to off.
    if (!authStore.programs.some((p) => p.programId === landed)) {
      void authStore.loadMe().catch(() => {})
    }
  },
  { immediate: true },
)
function gotoProjectsPage(): void {
  void router.push('/projects')
}
watch(() => projCtx.lastPrunedCount, (n) => {
  if (n > 0) {
    ElMessage.warning(t('layout.projectCtx.pruned', { count: n }))
    projCtx.acknowledgePruning()
  }
})

async function loadNotifications(): Promise<void> {
  if (!notifPermVisible.value) {
    return
  }
  try {
    const [all, unread] = await Promise.all([
      listNotifications({ page: 1, size: 20 }),
      listNotifications({ readFlag: 0, page: 1, size: 1 }),
    ])
    notifications.value = all.list || []
    unreadCount.value = unread.total || 0
  } catch {
  }
}

watch(notifPanelVisible, (open) => {
  if (open) void loadNotifications()
})

function formatNotifTime(raw?: string): string {
  if (!raw) return ''
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}


async function openNotif(n: NotificationItem): Promise<void> {
  await ElMessageBox.alert(n.content || t('layout.notifNoContent'), n.title, {
    confirmButtonText: t('layout.gotIt'),
    customStyle: { maxWidth: '520px' },
  })
  if (n.readFlag === 0) {
    await markNotificationRead(n.id)
    n.readFlag = 1
    if (unreadCount.value > 0) unreadCount.value -= 1
  }
}


async function retryFromNotif(n: NotificationItem): Promise<void> {
  const taskId = n.dedupKey ? Number(n.dedupKey.split(':').pop()) : NaN
  if (!Number.isFinite(taskId) || taskId <= 0) {
    ElMessage.warning(t('layout.retryNoTask'))
    return
  }
  try {
    await retryAiTask(taskId)
    if (n.readFlag === 0) {
      await markNotificationRead(n.id)
      n.readFlag = 1
      if (unreadCount.value > 0) unreadCount.value -= 1
    }
    ElMessage.success(t('layout.retrySubmitted'))
    notifPanelVisible.value = false
    router.push({ path: '/ai/generate', query: { task: String(taskId) } })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('layout.retryFailed'))
  }
}


async function markAllRead(): Promise<void> {
  try {
    const { affected } = await markAllNotificationsRead()
    notifications.value.forEach((n) => { n.readFlag = 1 })
    unreadCount.value = 0
    ElMessage.success(affected > 0 ? t('layout.markAllReadDone', { affected }) : t('layout.markAllReadNone'))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('layout.markAllReadFailed'))
  }
}


function gotoNotifCenter(): void {
  notifPanelVisible.value = false
  void router.push('/notifications')
}


function onNotifChanged(): void {
  void loadNotifications()
}

onMounted(() => {
  void loadNotifications()
  projCtx.loadForUser()
  // The program lens hydrates the same way: its store watch keys on the
  // userId TRANSITION, so a refresh with an already-loaded session wakes
  // nothing and the persisted pick would never return (11-07 E2E: reloaded
  // list requests silently dropped programId). Guarded on a loaded user so
  // an early mount can never overwrite memory with the anonymous bucket.
  if (authStore.user?.userId != null) progCtx.loadForUser()
  notifTimer = window.setInterval(() => void loadNotifications(), 60000)
  window.addEventListener('tp-notification-changed', onNotifChanged)
})

onUnmounted(() => {
  if (notifTimer !== null) {
    window.clearInterval(notifTimer)
    notifTimer = null
  }
  window.removeEventListener('tp-notification-changed', onNotifChanged)
})

const activeMenu = computed(() => {
  if (route.path.startsWith('/projects/')) return '/projects'
  if (route.path.startsWith('/cases/')) return '/cases'
  return route.path
})

const currentTitleKey = computed(() =>
  resolveMenuTitleKey(activeMenu.value, route.meta.titleKey),
)

/**
 * R23 stage 1: the topbar "?" resolves through the menu-leaf mapping as its
 * single source (helpDomain/helpTipKey/helpAnchorKey in menuStructure). Detail
 * routes (meta.parent) resolve to their parent leaf; routes with no leaf hit
 * render no "?" (login/about pages).
 */
const currentHelpLeaf = computed<MenuLeaf | null>(() => {
  const direct = allMenuLeaves.find((m) => m.path === activeMenu.value)
  if (direct) return direct
  const parent = route.meta.parent as string | undefined
  if (parent) return allMenuLeaves.find((m) => m.path === parent) ?? null
  return null
})

const breadcrumbTitles = useBreadcrumbTitles()


const breadcrumbItems = computed<BreadcrumbItem[]>(() => {
  const parent = route.meta.parent as string | undefined
  if (!parent) return []
  const parentItem = allMenuLeaves.find((m) => m.path === parent)
  const parentTitle = parentItem ? t(parentItem.titleKey) : String(route.name ?? '')
  const dynamic = route.name ? breadcrumbTitles.value[String(route.name)] : undefined
  return [
    { key: parent.replace(/^\//, ''), title: parentTitle, path: parent },
    {
      key: 'current',
      title: dynamic ?? (route.meta.titleKey ? t(String(route.meta.titleKey)) : t('common.appName')),
      path: null,
    },
  ]
})

const themeTipKey = computed(() =>
  appStore.theme === 'dark' ? 'layout.themeLight' : 'layout.themeDark',
)

async function onCommand(command: string): Promise<void> {
  if (command === 'profile') {
    router.push('/profile')
    return
  }
  if (command === 'logout') {
    await ElMessageBox.confirm(t('layout.logoutConfirm'), t('common.warning'), {
      confirmButtonText: t('layout.logoutConfirmBtn'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
    await authStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped lang="scss">
.layout-root {
  height: 100vh;
  overflow: hidden;
}

.layout-aside {
  background: var(--tp-bg-sidebar);
  border-right: 1px solid var(--tp-sidebar-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  transition: background-color 0.3s ease, border-color 0.3s ease;
}


.aside-resizer {
  position: absolute;
  top: 0;
  right: 0;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 10;
}
.aside-resizer::after {
  content: '';
  position: absolute;
  top: 0;
  left: 4px;
  width: 2px;
  height: 100%;
  background: transparent;
  transition: background-color 0.15s ease;
}
.aside-resizer:hover::after {
  background: var(--tp-primary);
}
body.is-resizing-sidebar .layout-aside,
body.is-resizing-sidebar .el-aside {
  transition: none !important;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  cursor: pointer;
  border-bottom: 1px solid var(--tp-sidebar-border);
  flex-shrink: 0;
}


.brand-icon {
  margin-left: 12px;
  color: var(--tp-sidebar-text-active);
}

.brand-text {
  color: var(--tp-sidebar-brand);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.layout-menu {
  border-right: none;
  flex: 1;
  overflow-y: auto;
  
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  --el-menu-item-height: 40px;
  --el-menu-base-level-padding: 12px;
  --el-menu-text-color: var(--tp-sidebar-text);
  --el-menu-hover-bg-color: var(--tp-sidebar-hover-bg);
  --el-menu-active-color: var(--tp-sidebar-text-active);
  padding: 6px 0 16px;
}




.layout-aside:has(.layout-menu.el-menu--collapse) .brand-icon {
  margin-left: 0;
}

.layout-aside:has(.layout-menu.el-menu--collapse) .brand {
  justify-content: center;
  padding: 0;
}

.layout-menu :deep(.el-menu-item) {
  margin: 2px 8px;
  border-radius: 8px;
  color: var(--tp-sidebar-text);
  transition: background-color 0.15s ease, color 0.15s ease;
}


.layout-menu.el-menu--collapse :deep(.el-menu-item),
.layout-menu.el-menu--collapse :deep(.el-sub-menu > .el-sub-menu__title) {
  margin-left: 0;
  margin-right: 0;
}

.layout-menu :deep(.el-menu-item .el-icon) {
  color: var(--tp-sidebar-text-dim);
  transition: color 0.15s ease;
}


.layout-menu :deep(.el-menu-item .menu-leaf-label) {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.layout-menu :deep(.el-menu-item .menu-leaf-help) {
  flex: none;
  margin-left: 6px;
  font-size: 13px;
  opacity: 0;
  transition: opacity 0.15s ease, color 0.15s ease;
}
.layout-menu :deep(.el-menu-item:hover .menu-leaf-help),
.layout-menu :deep(.el-menu-item.is-active .menu-leaf-help) {
  opacity: 0.75;
}
.layout-menu :deep(.el-menu-item .menu-leaf-help:hover) {
  opacity: 1;
  color: var(--tp-sidebar-text-active);
}

.layout-menu :deep(.el-menu-item:hover) {
  color: var(--tp-sidebar-brand);
}

.layout-menu :deep(.el-menu-item:hover .el-icon) {
  color: var(--tp-sidebar-text);
}

.layout-menu :deep(.el-menu-item.is-active) {
  background: var(--tp-sidebar-active-bg);
  color: var(--tp-sidebar-text-active);
  font-weight: 600;
}

.layout-menu :deep(.el-menu-item.is-active .el-icon) {
  color: var(--tp-sidebar-text-active);
}


.layout-menu :deep(.el-sub-menu__title) {
  margin: 2px 8px;
  border-radius: 8px;
  color: var(--tp-sidebar-text);
}

.layout-menu :deep(.el-sub-menu__title .el-icon) {
  color: var(--tp-sidebar-text-dim);
}

.layout-menu :deep(.el-sub-menu__title:hover) {
  background: var(--tp-sidebar-hover-bg);
  color: var(--tp-sidebar-brand);
}


.layout-menu :deep(.el-sub-menu__title > span) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.layout-menu :deep(.el-sub-menu__title .el-sub-menu__icon-arrow) {
  flex: none;
}

.layout-menu :deep(.el-sub-menu .el-menu) {
  background: var(--tp-sidebar-submenu-bg);
  border-radius: 10px;
  margin: 2px 8px 6px;
  padding: 2px 0;
}


.layout-menu :deep(.el-sub-menu .el-menu > .el-sub-menu > .el-sub-menu__title .el-sub-menu__icon-arrow) {
  right: calc(var(--el-menu-base-level-padding) - 8px);
}

.layout-menu :deep(.el-sub-menu .el-menu .el-menu-item) {
  margin: 2px 6px;
  min-width: 0;
}

.layout-body {
  flex: 1;
  min-width: 0;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--tp-bg-header);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--tp-border);
  padding: 0 16px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: var(--tp-text-secondary);
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}
/* R23: topbar "?" sits right of the page name; secondary tone keeps the page
   name as the visual lead (weight diff noted in the action-band close-out). */
.header-help-btn {
  cursor: pointer;
  color: var(--tp-text-secondary);
}
.header-help-btn:hover {
  color: var(--tp-primary);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
/* Header project context (single-select): icon + dropdown on the bar; long
   project names ellipsize inside el-select itself */
.proj-ctx-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--tp-text-secondary);
  font-size: 13px;
}
.proj-ctx-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.gs-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.gs-trigger:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.gs-trigger-kbd {
  font-size: 11px;
  padding: 0 4px;
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
}

.header-icon {
  font-size: 20px;
  cursor: pointer;
  color: var(--tp-text-secondary);
}

.header-icon:hover {
  color: var(--tp-primary);
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--tp-text-primary);
  outline: none;
}

.notif-badge {
  cursor: pointer;
  display: flex;
  align-items: center;
}

.notif-panel {
  .notif-panel-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--tp-border);

    .notif-panel-ops {
      display: flex;
      align-items: center;
      gap: 4px;
    }

    .notif-panel-title {
      font-size: 14px;
      font-weight: 600;
    }
  }

  .notif-list {
    max-height: 420px;
    overflow-y: auto;
    margin-top: 4px;
  }

  .notif-item {
    padding: 10px 4px;
    border-bottom: 1px dashed var(--tp-border);
    position: relative;

    &:last-child {
      border-bottom: none;
    }

    &.unread {
      background: var(--tp-primary-weak);
      border-radius: 8px;
      padding-left: 10px;
      padding-right: 10px;
    }
  }

  .notif-item-top {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;
  }

  .notif-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--tp-danger);
    display: inline-block;
  }

  .notif-item-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--tp-text-primary);
  }

  .notif-item-content {
    font-size: 12px;
    color: var(--tp-text-secondary);
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin: 4px 0;
  }

  .notif-item-bottom {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .notif-time {
    font-size: 11px;
    color: var(--tp-text-tertiary, var(--tp-text-secondary));
  }

  .notif-actions {
    display: flex;
    gap: 4px;
  }
}

.user-avatar {
  
  background: linear-gradient(135deg, #0369a1, #4338ca);
  color: #fff;
  font-weight: 600;
}

.user-name {
  font-size: 14px;
}

.layout-main {
  /* Right gutter = live chat-dock width (single token, owned by ChatDock):
     the non-modal drawer must never sit on top of the page's own controls —
     see theme.scss --tp-chat-dock-reserve (ux gate X-2 / WCAG 2.4.11). */
  padding: 0 var(--tp-chat-dock-reserve, 0px) 0 0;
  overflow-y: auto;
  background: var(--tp-bg);
}
</style>
