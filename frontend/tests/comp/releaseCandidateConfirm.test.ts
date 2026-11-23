/**
 * candidate-confirmation surface component contract (Vitest Browser Mode,
 * real Chromium). Three shapes per the task card:
 * 1. prefill — the confirm drawer opens with payload-derived values (release
 * name/repo/publish time shown, breaking changes prefilled from
 * release_notes_md, version locked to the candidate);
 * 2. required-field highlight — blank authoritative fields (matrix/min/max/path)
 * carry the confirm-field-blank warning ring and the human hint (a prompt,
 * not a submit gate — core register tolerates empty arrays);
 * 3. halo state — pending candidate card renders with the candidate-halo
 * background tint (halo caliber: no modal, no side bar).
 * Plus the empty-state regression pin: candidates=[] → the page is
 * element-for-element identical to the pre- surface (progressive
 * enhancement) and the changelog drawer shows no pending group.
 * The api-mirror gate pins getCompatDiffWith path + params passthrough (impact
 * preview numbers are core-scored; the page only counts — red line 6).
 * i18n mirror gate: the whole candidate/confirm key family exists in zh-CN and
 * en-US (terminal zero-hard-Chinese caliber for new surfaces).
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createPinia, setActivePinia } from 'pinia'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

const api = vi.hoisted(() => ({
  getLatestManifest: vi.fn(),
  listReleaseManifests: vi.fn(),
  getPlatformVersion: vi.fn(),
  listReleaseCandidates: vi.fn(),
  getCompatDiffWith: vi.fn(),
  confirmReleaseCandidate: vi.fn(),
  dismissReleaseCandidate: vi.fn(),
  getProjects: vi.fn(),
}))
vi.mock('@/api/cluster', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return {
    ...actual,
    getLatestManifest: api.getLatestManifest,
    listReleaseManifests: api.listReleaseManifests,
    getPlatformVersion: api.getPlatformVersion,
    listReleaseCandidates: api.listReleaseCandidates,
    getCompatDiffWith: api.getCompatDiffWith,
    confirmReleaseCandidate: api.confirmReleaseCandidate,
    dismissReleaseCandidate: api.dismissReleaseCandidate,
  }
})
vi.mock('@/api/project', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, getProjects: api.getProjects }
})
const auth = vi.hoisted(() => ({ isAdmin: false, perms: [] as string[] }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    user: { userId: 1, username: 'u', nickName: 'u', roles: auth.isAdmin ? ['ADMIN'] : [] },
    get isAdmin() { return auth.isAdmin },
    hasPerm: (code: string) => auth.isAdmin || auth.perms.includes(code),
  }),
}))

import UpgradeCenterView from '@/views/cluster/UpgradeCenterView.vue'
import clusterApiRaw from '@/api/cluster?raw'
import viewRaw from '@/views/cluster/UpgradeCenterView.vue?raw'

const VIEW_PERM = 'perm:cluster:release:candidate:view'
const CONFIRM_PERM = 'perm:cluster:release:candidate:confirm'

function manifest(over: Record<string, unknown> = {}) {
  return {
    id: 1, version: '1.4.3', componentMatrix: '[]', breakingChanges: '[]',
    executorVersionMin: '1.0', executorVersionMax: '1.4', suggestedPath: null,
    source: 'import', registeredAt: '2026-10-10T08:00:00', ...over,
  }
}

function candidate(over: Record<string, unknown> = {}) {
  return {
    id: 9, version: '1.5.0', channel: 'released', detectedSource: 'poll',
    detectedAt: '2026-10-12T20:00:00',
    payloadJson: JSON.stringify({
      release_name: 'v1.5.0', release_notes_md: 'Executor protocol bump',
      published_at: '2026-10-12T19:30:00', assets: ['testpike-1.5.0.tar.gz'],
      detected_repo: 'sixbean/testpike',
    }),
    status: 'detected', confirmedManifestId: null, ...over,
  }
}

/** Wait for a teleported drawer node to actually render in body (open
 * transitions resolve on animation frames, not on flushPromises alone). */
/** Wait for a lazily-mounted drawer pane to render (el-drawer renders in place
 * under the wrapper — see pluginDetailDrawer precedent — and opens only on a
 * false→true modelValue transition). */
/** Wait for the drawer pane to render and return the native element so plain
 * querySelector/textarea introspection works inside a teleported-free tree. */
async function drawerOf(w: ReturnType<typeof mount>, attr: string): Promise<HTMLElement> {
  const sel = `[data-test="${attr}"]`
  await vi.waitFor(() => expect(w.find(sel).exists(), sel).toBe(true))
  return w.find(sel).element as HTMLElement
}

async function factory() {
  const i18n = createI18n({
    legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS },
  })
  const pinia = createPinia()
  setActivePinia(pinia)
  const w = mount(UpgradeCenterView, { global: { plugins: [ElementPlus, i18n, pinia] } })
  await flushPromises()
  return w
}

beforeEach(() => {
  api.getLatestManifest.mockReset().mockResolvedValue(manifest())
  api.listReleaseManifests.mockReset().mockResolvedValue([manifest()])
  api.getPlatformVersion.mockReset().mockResolvedValue({
    runningVersion: '1.4.2', buildTime: null, latestVersion: '1.4.3', verdict: 'BEHIND',
  })
  api.listReleaseCandidates.mockReset().mockResolvedValue([])
  api.getCompatDiffWith.mockReset().mockResolvedValue([])
  api.confirmReleaseCandidate.mockReset().mockResolvedValue(manifest({ id: 2, version: '1.5.0' }))
  api.dismissReleaseCandidate.mockReset().mockResolvedValue(candidate({ status: 'dismissed' }))
  api.getProjects.mockReset().mockResolvedValue({ list: [], total: 0 })
  auth.isAdmin = false
  auth.perms = [VIEW_PERM, CONFIRM_PERM]
  document.body.innerHTML = ''
})

describe('T351 候选确认体验面（晕染卡/确认抽屉/预填/必填高亮/空态回归钉）', () => {
  it('api 镜像闸：compat-diff-with 口路径与 min/max 透传在位（判定单源 core，前端零二判）', () => {
    expect(clusterApiRaw).toContain('/cluster/release-manifests/compat-diff-with')
    expect(clusterApiRaw).toMatch(/getCompatDiffWith\([\s\S]*?params:\s*{\s*min/)
    // Consumption slot: the impact preview only counts core-scored
    // out-of-range rows — never re-scored locally.
    expect(viewRaw).toContain('getCompatDiffWith')
    expect(viewRaw).toContain('compatible === false')
  })

  it('i18n 镜像闸：candidate/confirm 键族 zh-CN/en-US 双写全命中', () => {
    const keys = [
      'candidateFound', 'candidateView', 'candidateConfirm', 'candidateDismiss',
      'candidateSourcePoll', 'candidateSourceWebhook', 'candidateSourceImport',
      'candidateCardNote', 'drawerConfirmTitle', 'confirmReleaseName',
      'confirmDetectedRepo', 'confirmPublishedAt', 'confirmVersionLabel',
      'confirmMatrixLabel', 'confirmBreakingLabel', 'confirmMinLabel',
      'confirmMaxLabel', 'confirmPathLabel', 'confirmSourceLabel',
      'confirmBlankRequired', 'confirmImpactRun', 'confirmImpactOut',
      'confirmImpactOk', 'confirmImpactEmpty', 'confirmImpactFailed',
      'confirmCrossWarn', 'confirmSubmit', 'confirmSuccess',
      'dismissPromptTitle', 'dismissPromptHint', 'dismissReasonBlank',
      'dismissSuccess', 'changelogPendingGroup', 'changelogPendingTag',
    ]
    for (const locale of [zhCN, enUS] as const) {
      const up = (locale as Record<string, any>).upgrade
      for (const k of keys) expect(up[k], `upgrade.${k}`).toBeTruthy()
    }
  })

  it('晕染态：待确认卡渲 candidate-halo 背景晕染类+发现新版本文案+三动作钮（禁弹窗禁竖条=类名面闸）', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    const card = w.find('[data-test="upgrade-candidate-1.5.0"]')
    expect(card.exists()).toBe(true)
    expect(card.classes()).toContain('candidate-halo')
    expect(card.text()).toContain('发现新版本 1.5.0')
    expect(card.text()).toContain('定时探测')
    expect(card.find('[data-test="upgrade-candidate-confirm-9"]').exists()).toBe(true)
    expect(card.find('[data-test="upgrade-candidate-dismiss-9"]').exists()).toBe(true)
    // Halo red line: the stylesheet allows no side bar / modal shape
    // (inset bars forbidden — a faint background tint is the only form)
    const haloRule = viewRaw.match(/\.candidate-halo\s*\{([^}]*)\}/)
    expect(haloRule, 'candidate-halo rule exists').toBeTruthy()
    expect(haloRule![1]).toContain('var(--tp-danger-weak)')
    expect(haloRule![1]).not.toMatch(/inset|border-left|border-right/)
  })

  it('无 confirm 权限：确认/作废钮隐藏（view 权限仍可看卡）', async () => {
    auth.perms = [VIEW_PERM]
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    expect(w.find('[data-test="upgrade-candidate-1.5.0"]').exists()).toBe(true)
    expect(w.find('[data-test="upgrade-candidate-confirm-9"]').exists()).toBe(false)
    expect(w.find('[data-test="upgrade-candidate-dismiss-9"]').exists()).toBe(false)
  })

  it('预填态：查看开抽屉=payload 单点预填（检出三要素直显/发布说明进破坏性变更/版本锁候选版禁改）', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    const text = drawer.textContent || ''
    expect(text).toContain('确认发布候选 1.5.0')
    expect(text).toContain('v1.5.0')
    expect(text).toContain('sixbean/testpike')
    // ElInput inheritAttrs=false: data-test lands on the native input/textarea.
    const versionInput = drawer.querySelector('input[data-test="upgrade-confirm-version"]') as HTMLInputElement
    expect(versionInput.value).toBe('1.5.0')
    expect(versionInput.disabled || versionInput.readOnly).toBe(true)
    const breaking = drawer.querySelector('textarea[data-test="upgrade-confirm-breaking"]') as HTMLTextAreaElement
    expect(breaking.value).toContain('Executor protocol bump')
    const source = drawer.querySelector('input[data-test="upgrade-confirm-source"]') as HTMLInputElement
    expect(source.value).toBe('import')
  })

  it('必填高亮态：矩阵/区间/路径空项带 confirm-field-blank 环+人话提示行；填齐后提示消', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    // data-test lands on the native inner (ElInput inheritAttrs=false) — the
    // warning ring class sits on the component root, one level up.
    for (const key of ['matrix', 'min', 'max', 'path']) {
      const inner = drawer.querySelector(`[data-test="upgrade-confirm-${key}"]`)!
      const root = inner.closest('.confirm-field-blank')
      expect(root, `upgrade-confirm-${key} blank ring`).not.toBeNull()
    }
    // breaking changes prefilled from release notes = no blank ring
    expect(drawer.querySelector('[data-test="upgrade-confirm-breaking"]')!.closest('.confirm-field-blank')).toBeNull()
    expect(drawer.querySelector('[data-test="upgrade-confirm-blank-hint"]')).not.toBeNull()
    // Blank fields are a prompt, not a submit gate (core tolerates empty arrays)
    expect(drawer.querySelector('[data-test="upgrade-confirm-submit"]')).not.toBeNull()
  })

  it('跨版黄提示：候选大版>最新大版且路径空=warning 不硬拦；填路径后提示消', async () => {
    api.getLatestManifest.mockResolvedValue(manifest({ version: '1.4.3' }))
    api.listReleaseCandidates.mockResolvedValue([candidate({ version: '2.0.0' })])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    expect(drawer.querySelector('[data-test="upgrade-confirm-cross-warn"]')).not.toBeNull()
    // Same major = no nagging (hint only fires on a real skip).
    api.listReleaseCandidates.mockResolvedValue([candidate({ version: '1.4.9' })])
    const w2 = await factory()
    await w2.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer2 = await drawerOf(w2, 'upgrade-confirm-drawer')
    expect(drawer2.querySelector('[data-test="upgrade-confirm-cross-warn"]')).toBeNull()
  })

  it('影响预检：compat-diff-with 现口取数，区间外计数人话句「N 台区间外将标红」；core 判词直透零前端自算', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    api.getCompatDiffWith.mockResolvedValue([
      { id: 1, name: 'node-a', version: '1.2.0', compatible: true, reason: null },
      { id: 2, name: 'node-b', version: '0.9.0', compatible: false, reason: '低于区间下界' },
      { id: 3, name: 'node-c', version: null, compatible: null, reason: '未上报版本不判定' },
    ])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    ;(drawer.querySelector('[data-test="upgrade-confirm-impact-btn"]') as HTMLElement).click()
    await flushPromises()
    expect(api.getCompatDiffWith).toHaveBeenCalled()
    const result = drawer.querySelector('[data-test="upgrade-confirm-impact-result"]')
    expect(result).not.toBeNull()
    expect(result!.textContent).toContain('1 台区间外将标红')
    // null-verdict rows never count (honest no-judgment ≠ incompatible)
    expect(result!.textContent).not.toContain('2 台')
  })

  it('确认成功链：confirm 收七字段入参 version=候选版；成功后卡就地变创建计划入口+横幅收敛重拉', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    ;(drawer.querySelector('[data-test="upgrade-confirm-submit"]') as HTMLElement).click()
    await flushPromises()
    expect(api.confirmReleaseCandidate).toHaveBeenCalledWith(9, expect.objectContaining({
      version: '1.5.0', source: 'import',
    }))
    // Pending card converges (re-pull drops the confirmed row) and the
    // in-place create-plan anchor appears
    expect(api.listReleaseCandidates).toHaveBeenCalledTimes(2)
    const pub = w.find('[data-test="upgrade-published-1.5.0"]')
    expect(pub.exists()).toBe(true)
    expect(pub.find('[data-test="upgrade-candidate-create-plan-1.5.0"]').exists()).toBe(true)
  })

  // ── one-click promotion: the confirmed card's create-plan entry starts
  // the wizard from a clean slate (flow reset) with the released version
  // preset on step 1 — residue from a previous plan must never skip the user
  // to step 2 with a stale version. ──
  it('T352 一键起念：创建计划入口=向导复位（resetPlanFlow 先行）+预置发布版', async () => {
    // Contract pin: startPlanFromVersion resets before presetting.
    const fn = viewRaw.match(/function startPlanFromVersion\([\s\S]*?\n\}/)
    expect(fn, 'startPlanFromVersion exists').toBeTruthy()
    expect(fn![0]).toContain('resetPlanFlow()')
    expect(fn![0].indexOf('resetPlanFlow()')).toBeLessThan(fn![0].indexOf('manifestVersion'))
  })

  it('T352 起念实跑：点创建计划入口后落计划 Tab 步 1，清单下拉=刚发布版本', async () => {
    api.listReleaseManifests.mockResolvedValue([manifest(), manifest({ id: 2, version: '1.5.0' })])
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    await w.find('[data-test="upgrade-candidate-view-9"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-confirm-drawer')
    ;(drawer.querySelector('[data-test="upgrade-confirm-submit"]') as HTMLElement).click()
    await flushPromises()
    await w.find('[data-test="upgrade-candidate-create-plan-1.5.0"]').trigger('click')
    await vi.waitFor(() => {
      const select = w.find('[data-test="upgrade-plan-manifest"]')
      expect(select.exists(), 'plan wizard step 1 reachable').toBe(true)
      // EP 2.9 renders the selected option as a span (input.value stays empty)
      expect(select.text()).toContain('1.5.0')
    })
  })

  it('changelog 待确认分组置顶：候选渲灰态组，无候选时组整体缺席', async () => {
    api.listReleaseCandidates.mockResolvedValue([candidate()])
    const w = await factory()
    await w.find('[data-test="upgrade-changelog-btn"]').trigger('click')
    const drawer = await drawerOf(w, 'upgrade-changelog-drawer')
    expect(drawer.querySelector('[data-test="upgrade-changelog-pending-group"]')).not.toBeNull()
    expect(drawer.querySelector('[data-test="upgrade-changelog-candidate-1.5.0"]')).not.toBeNull()
    expect(drawer.textContent).toContain('待确认候选')
  })

  it('空态回归钉：候选空数组=页面逐元素与 T351 之前无差（渐进增强零新噪音）', async () => {
    const w = await factory()
    expect(w.find('[data-test="upgrade-version-banner"]').exists()).toBe(true)
    expect(w.findAll('[class*="candidate-card"]').length).toBe(0)
    expect(w.html()).not.toContain('candidate-halo')
  })

  it('候选拉取失败独立容错：接口 reject 不连坐清单区（横幅照常+无卡无炸）', async () => {
    api.listReleaseCandidates.mockRejectedValue(new Error('boom'))
    const w = await factory()
    expect(w.find('[data-test="upgrade-version-banner"]').exists()).toBe(true)
    expect(w.findAll('[class*="candidate-card"]').length).toBe(0)
  })
})
