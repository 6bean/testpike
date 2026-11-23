/**
 * Header program lens mounting contract ( MP2c, RED-first, Vitest Browser
 * Mode = real Chromium). Mirrors the projectContextHeader recipe (three mocks
 * + router/i18n/ElementPlus) so MainLayout mounts identically to production.
 * Tri-state pinned here:
 * 1. zero-touch: login snapshot without programs -> the program switcher is
 * NOT rendered and the project switcher behaves exactly as before.
 * 2. lens on: options list the program names; picking a program narrows the
 * project switcher to the program's projects, and a current project that
 * falls out of scope auto-moves to the first in-scope one.
 * 3. clearing the lens restores the full visible project set.
 * Division of labour: pure store semantics = node L2
 * (tests/ui/programHeaderLens.test.ts); full-chain behaviour = E2E (MP4).
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElOption, ElSelect } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

vi.mock('@/api/notification', () => ({
  listNotifications: vi.fn(async () => ({ list: [], total: 0 })),
  markAllNotificationsRead: vi.fn(async () => ({ affected: 0 })),
  markNotificationRead: vi.fn(async () => ({ affected: 0 })),
  eventLabel: (x: string) => x,
  eventTagType: () => 'info',
}))
vi.mock('@/api/help', () => ({
  listHelpDocs: vi.fn(async () => []),
  getHelpDomainDocs: vi.fn(async () => []),
}))
vi.mock('@/api/project', () => ({
  getProjects: vi.fn(async () => ({ list: [], total: 0 })),
}))

import MainLayout from '@/layouts/MainLayout.vue'
import ProjectSelect from '@/components/ProjectSelect.vue'
import { useAuthStore } from '@/stores/auth'
import { useProgramContextStore } from '@/stores/programContext'
import { useProjectContextStore } from '@/stores/projectContext'

const mounted: { unmount: () => void }[] = []

function makeProjects() {
  return [
    { projectId: 11, projectName: '回归演示项目', projectCode: 'DEMO' },
    { projectId: 22, projectName: '支付项目', projectCode: 'PAY' },
    { projectId: 33, projectName: '订单项目', projectCode: 'ORD' },
  ]
}

function makePrograms() {
  return [
    { programId: 5, name: '支付项目集', projectIds: [11, 22] },
    { programId: 6, name: '订单项目集', projectIds: [33] },
  ]
}

async function mountLayout(userId = 7, programs: ReturnType<typeof makePrograms> = []) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { userId, username: 'admin', nickName: '管理员', roles: ['ADMIN'] }
  auth.projects = makeProjects()
  auth.programs = programs
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/projects', name: 'ProjectList', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'blank', component: { template: '<div />' } },
    ],
  })
  router.push('/dashboard')
  await router.isReady()
  const w = mount(MainLayout, {
    attachTo: document.body,
    global: { plugins: [ElementPlus, i18n, pinia, router] },
  })
  mounted.push(w)
  await flushPromises()
  return w
}

function programSelect(w: ReturnType<typeof mount>) {
  return w.findAllComponents(ElSelect).find((s) => s.attributes('data-test') === 'program-ctx-select')
}

function projectOptions(w: ReturnType<typeof mount>) {
  return w.findComponent(ProjectSelect).props('options').map((p: { projectId: number }) => p.projectId)
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
  document.body.innerHTML = ''
})

describe('header program lens mounting', () => {
  it('state 1 zero-touch: no programs in the snapshot -> switcher absent, project bar untouched', async () => {
    const w = await mountLayout(7, [])
    expect(programSelect(w)).toBeUndefined()
    expect(w.find('[data-test="project-ctx-trigger"]').exists()).toBe(true)
    expect(projectOptions(w)).toEqual([11, 22, 33])
  })

  it('state 2 lens on: options = program names; picking narrows the project switcher; out-of-scope current project auto-moves', async () => {
    const w = await mountLayout(7, makePrograms())
    const sel = programSelect(w)
    expect(sel).toBeDefined()
    const labels = w.findAllComponents(ElOption).map((o) => String(o.props('label')))
    expect(labels).toContain('支付项目集')
    expect(labels).toContain('订单项目集')
    // current picked project = 33 (outside program 5's scope)
    useProjectContextStore().select([33])
    await flushPromises()
    // user picks program 5 through the rendered select event
    sel!.vm.$emit('update:model-value', 5)
    await flushPromises()
    expect(useProgramContextStore().activeProgramId).toBe(5)
    expect(projectOptions(w)).toEqual([11, 22])
    expect(useProjectContextStore().primaryId).toBe(11)
  })

  it('state 3 clearing the lens restores the full visible project set', async () => {
    const w = await mountLayout(7, makePrograms())
    const sel = programSelect(w)!
    useProjectContextStore().select([33])
    await flushPromises()
    sel.vm.$emit('update:model-value', 5)
    await flushPromises()
    expect(projectOptions(w)).toEqual([11, 22])
    sel.vm.$emit('update:model-value', null)
    await flushPromises()
    expect(useProgramContextStore().activeProgramId).toBeNull()
    expect(projectOptions(w)).toEqual([11, 22, 33])
  })

  it('empty-scope honest echo: a program with no visible projects empties the switcher and the guidance hint no longer misfires', async () => {
    const w = await mountLayout(7, [{ programId: 9, name: '空项目集', projectIds: [] }])
    const sel = programSelect(w)!
    useProjectContextStore().select([22])
    await flushPromises()
    sel.vm.$emit('update:model-value', 9)
    await flushPromises()
    expect(projectOptions(w)).toEqual([])
    expect(useProjectContextStore().primaryId).toBeNull()
    // lens-empty is NOT "no visible projects" — the projects page hint must stay hidden
    expect(w.find('[data-test="project-ctx-hint"]').exists()).toBe(false)
  })
})
