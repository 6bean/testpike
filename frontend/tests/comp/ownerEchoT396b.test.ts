/**
 * T396b ownership-echo layout contract (RED first, then the shared component).
 * Authority = the 2026-10-14 ownership-echo ruling doc (docs/change-eval, whole-site
 * iron rule, three clauses):
 * 1. every create/write entry fed by the global project context must show a
 * read-only ownership echo, value single-sourced from the context store -
 * no second selector in the dialog;
 * 2. with no context the create entry must be disabled with a plain-language hint;
 * 3. the page must make "list = current project segment" visible.
 * This file locks rule 1/2 mechanics for the reusable ProjectOwnerEcho component
 * plus the store computed (primaryName) it reads. Per-page wiring is validated by
 * the ?raw source scans appended batch by batch (packages 2/3/4).
 * Posture follows mockHeaderLayoutT396.test.ts (?raw mirror) and
 * empty-state.test.ts (real mount in browser mode).
 */
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import zhCN from '@/utils/locales/zh-CN'
import type { LoginUser, ProjectBrief } from '@/api/auth'
import echoRaw from '@/components/ProjectOwnerEcho.vue?raw'
import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectContextStore } from '@/stores/projectContext'

function project(pid: number): ProjectBrief {
  return { projectId: pid, projectName: `Proj-${pid}`, projectCode: `c${pid}` }
}

function factory(selected: number[], visible: number[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.user = { userId: 1, username: 'u1' } as LoginUser
  auth.projects = visible.map(project)
  const ctx = useProjectContextStore(pinia)
  ctx.select(selected)
  const host = document.createElement('div')
  document.body.appendChild(host)
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  return mount(ProjectOwnerEcho, { attachTo: host, global: { plugins: [pinia, i18n] } })
}

describe('T396b ProjectOwnerEcho: read-only echo, single source, no second selector', () => {
  it('renders the context project name as the ownership value (rule 1)', () => {
    const w = factory([7, 3], [7, 3, 9])
    expect(w.find('[data-test="owner-echo"]').exists()).toBe(true)
    // primaryId = first selected id = 7 → the echo must show that project's name
    expect(w.find('[data-test="owner-echo-value"]').text()).toBe('Proj-7')
    expect(w.text()).toContain('所属项目')
    // plain-language switch pointer so users know where ownership is decided
    expect(w.text()).toContain('顶栏')
  })

  it('shows the missing-context hint instead of a value when nothing is selected (rule 2 wording)', () => {
    const w = factory([], [7])
    expect(w.find('[data-test="owner-echo-none"]').exists()).toBe(true)
    expect(w.find('[data-test="owner-echo-value"]').exists()).toBe(false)
    expect(w.text()).toContain('顶栏')
  })

  it('stays honest when the selected id left the visible domain (stale context = no fake name)', () => {
    const w = factory([42], [7, 3])
    expect(w.find('[data-test="owner-echo-none"]').exists()).toBe(true)
  })

  it('follows the store single source: header switch re-renders the echo with no page plumbing', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.user = { userId: 1, username: 'u1' } as LoginUser
    auth.projects = [7, 3].map(project)
    const ctx = useProjectContextStore(pinia)
    ctx.select([7])
    const host = document.createElement('div')
    document.body.appendChild(host)
    const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
    const w = mount(ProjectOwnerEcho, { attachTo: host, global: { plugins: [pinia, i18n] } })
    expect(w.find('[data-test="owner-echo-value"]').text()).toBe('Proj-7')
    ctx.select([3, 7])
    await w.vm.$nextTick()
    expect(w.find('[data-test="owner-echo-value"]').text()).toBe('Proj-3')
  })

  it('the echo component itself never hosts a selector (rule 1: no second channel)', () => {
    expect(echoRaw).not.toMatch(/<el-select|ProjectSelect/)
  })
})

describe('T396b projectContext store: primaryName is the single-sourced echo value', () => {
  it('resolves the first selected id against the visible projects list', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.user = { userId: 1, username: 'u1' } as LoginUser
    auth.projects = [7, 3].map(project)
    const ctx = useProjectContextStore(pinia)
    expect(ctx.primaryName).toBe(null)
    ctx.select([7, 3])
    expect(ctx.primaryName).toBe('Proj-7')
    ctx.select([3])
    expect(ctx.primaryName).toBe('Proj-3')
    // id outside the visible domain never yields a name (no stale echo)
    ctx.selectedIds = [42]
    expect(ctx.primaryName).toBe(null)
  })
})
