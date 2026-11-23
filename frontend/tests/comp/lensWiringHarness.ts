/**
 * Shared mount harness for the header program-lens page contracts (MP4-b,
 * R40 single source).
 *
 * Every list page consumes the lens identically: seed the login snapshot on
 * the auth store, mount the page in browser mode, drive the header lens
 * flip/detach and capture the outgoing list params. Writing that assembly
 * once per page would put the same ≥8-line block into three files — the
 * duplication-gate lesson from the MP3b L2 fixtures — so the store seeding,
 * the global component registration mirror and the four wiring contracts all
 * live HERE once, and each page test contributes only its own API mocks plus
 * the component under mount.
 *
 * Contract pinned by describeProgramLensWiring (per list page):
 *  1. lens ON  → the list request carries programId (server conjuncts it with
 *     the explicit projectId via the core three-branch lens path);
 *  2. lens OFF → the parameter is ABSENT entirely (legacy zero drift: users
 *     without a program pick get the exact pre-feature request);
 *  3. lens flip while mounted → reload resets to page 1 with the NEW id
 *     (same posture as the project-header followTick reload);
 *  4. lens detach → the follow-up request drops programId again.
 *
 * Wiring law (R30 program-dimension projection): the page never owns a
 * second program picker — it reads useProgramContextStore().activeProgramId
 * as the single source and forwards it verbatim.
 */
import 'element-plus/dist/index.css'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import type { Component } from 'vue'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import zhCN from '@/utils/locales/zh-CN'
import DataTablePage from '@/components/DataTablePage.vue'
import EmptyState from '@/components/EmptyState.vue'
import RowActions from '@/components/RowActions.vue'
import TableSkeleton from '@/components/TableSkeleton.vue'
import TimeCell from '@/components/TimeCell.vue'
import { useAuthStore } from '@/stores/auth'
import { useProgramContextStore } from '@/stores/programContext'
import { PROJECT_CTX_KEY_PREFIX, useProjectContextStore } from '@/stores/projectContext'
import type { LoginUser, ProjectBrief, UserProgramView } from '@/api/auth'

export type LensStore = ReturnType<typeof useProgramContextStore>

/** Project visible under PROGRAM_A only. */
export const LENS_PROJ_ID = 11
/** Second project in the snapshot (never selected — keeps the segment single). */
export const LENS_PROJ_OTHER = 12
/** Header lens ids: pick A at mount, flip to B, then detach. */
export const LENS_PROGRAM_A = 7
export const LENS_PROGRAM_B = 8

// Mirror the global registrations from main.ts (TimeCell/EmptyState/TableSkeleton/
// RowActions/DataTablePage) — without them the pages' scoped-slot tables resolve
// as native elements and the mount dies before any request is issued.
const GLOBAL_COMPONENTS: Record<string, unknown> = {
  TimeCell,
  EmptyState,
  TableSkeleton,
  RowActions,
  DataTablePage,
}

function projectBrief(pid: number): ProjectBrief {
  return { projectId: pid, projectName: `Proj-${pid}`, projectCode: `p${pid}` }
}

function programView(programId: number, projectIds: number[]): UserProgramView {
  return { programId, name: `Prog-${programId}`, projectIds }
}

export interface LensMountOptions {
  /** Page component under contract. */
  component: Component
  /** Route the page resolves its path from (catch-all route registered). */
  routePath: string
  /** Child views kept out of the mount (heavy sibling pages). */
  stubs?: Record<string, boolean>
  /** Initial header-lens state before the page setup runs. */
  activeProgramId: number | null
  /** Seed the login snapshot with ADMIN roles (report-center gates the
   *  generate entry and the scope filter on authStore.isAdmin). Default
   *  keeps today's member posture so existing pages stay byte-identical. */
  admin?: boolean
  /** Deep-link posture probe: seed NO project segment (the header project is
 * cleared while the lens resolves) so a page's load gate is exercised with
 * the lens alone. Default false keeps every existing suite byte-identical. */
  noProject?: boolean
  /** Attach the mounted tree to document.body (EP dialogs/popovers behave
   *  like real UI only inside the live document — detached mounts can hand
   *  back stale subtrees whose listeners are gone; precedent
   *  programHeaderMount.test.ts). Default false keeps sibling suites
   *  byte-identical. */
  attachToBody?: boolean
}

/**
 * Mount a list page with the login snapshot seeded directly on the auth
 * store (fixture rule from the MP4 recon: seed store state, never depend on
 * a remote loadMe). Page context lands on LENS_PROJ_ID through the normal
 * useProjectPageContext chain; the header lens starts as requested.
 */
export async function mountPageWithLens(o: LensMountOptions) {
  localStorage.removeItem('tp_program_ctx_1')
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  const userId = 1
  auth.user = { userId, username: 'u1', roles: o.admin ? ['ADMIN'] : [] } as LoginUser
  auth.projects = [projectBrief(LENS_PROJ_ID), projectBrief(LENS_PROJ_OTHER)]
  auth.programs = [programView(LENS_PROGRAM_A, [LENS_PROJ_ID]), programView(LENS_PROGRAM_B, [LENS_PROJ_OTHER])]
  const ctx = useProjectContextStore(pinia)
  if (o.noProject) {
    // Cleared segment must survive the store's own hydration: the persisted
    // per-user key would otherwise restore the project selected above.
    localStorage.removeItem(`${PROJECT_CTX_KEY_PREFIX}${userId}`)
  } else {
    ctx.select([LENS_PROJ_ID])
  }
  const lens = useProgramContextStore(pinia)
  if (o.activeProgramId != null) lens.select(o.activeProgramId)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div/>' } },
      { path: '/:rest+', component: { template: '<div/>' } },
    ],
  })
  await router.push(o.routePath)
  await router.isReady()

  const i18n = createI18n({ legacy: false, locale: 'zh-CN', fallbackLocale: 'zh-CN', messages: { 'zh-CN': zhCN } })
  const wrapper = mount(o.component as never, {
    global: {
      plugins: [pinia, ElementPlus, i18n, router],
      components: GLOBAL_COMPONENTS as never,
      stubs: o.stubs,
    },
    ...(o.attachToBody ? { attachTo: document.body } : {}),
  })
  await flushPromises()
  return { wrapper, pinia, lens }
}

/**
 * Shared API doubles (R40 single source): the report-center suites mocked
 * the same project-rail/caliber surfaces byte-identically and shared an
 * 8-line reportCenter prefix — the duplication gate flagged both windows,
 * so every factory lives once here. Consume LAZILY: vitest hoists `vi.mock`
 * above the imports, so a passed-in imported identifier hits a TDZ error —
 * wrap it: `vi.mock('@/api/project', (io) => projectApiDouble(io))`.
 */
export async function projectApiDouble(importOriginal: () => Promise<unknown>): Promise<Record<string, unknown>> {
  const actual = (await importOriginal()) as Record<string, unknown>
  return { ...actual, getProjects: async () => ({ list: [], total: 0 }) }
}

export async function reportApiDouble(importOriginal: () => Promise<unknown>): Promise<Record<string, unknown>> {
  const actual = (await importOriginal()) as Record<string, unknown>
  return { ...actual, getCalibers: async () => ({}) }
}

/**
 * Report-center list/generate doubles recording call payloads into `sink`.
 * `generate` is optional — the wiring suite never submits, so its array
 * stays absent and the branch is inert there.
 */
export async function makeReportCenterDouble(
  importOriginal: () => Promise<unknown>,
  sink: { list: unknown[]; generate?: unknown[] },
): Promise<Record<string, unknown>> {
  const actual = (await importOriginal()) as Record<string, unknown>
  return {
    ...actual,
    listReportCenter: async (params: Record<string, unknown>) => {
      sink.list.push(params)
      return { list: [], total: 0 }
    },
    generateReportCenter: async (data: Record<string, unknown>) => {
      sink.generate?.push(data)
      return { id: 555 } as never
    },
  }
}

export interface LensCallContract {
  /** Page label for the test output. */
  page: string
  /** mountPageWithLens bound to this page's component/route; returns the lens handle. */
  mountWith: (activeProgramId: number | null) => Promise<{ lens: LensStore }>
  /** Params recorded by the page's own API mock, in call order. */
  calls: () => Record<string, unknown>[]
  /** false for pages whose lens door is a non-paged endpoint (the API-test
   *  collection rail — display-scale ledger page, no page param exists). The
   *  reload contracts still hold; only the page-1 reset assertion is skipped. */
  paged?: boolean
  /** Mount in the deep-link posture: header lens resolved, project segment
 * cleared. When present the three lens-only load-gate postures are
 * registered alongside the wiring ones — a page supplies only this mount
 * binding, never a second copy of the assertions (R40). */
  mountLensOnly?: (activeProgramId: number | null) => Promise<{ lens: LensStore }>
  /** Param key carrying the header project segment on this page's request.
 * Defaults to 'projectId'; the report ledger narrows by scopeId instead
 * (tp_report rows key on scope_id), so the same lens posture travels
 * under a different key — pinned here, not special-cased per page. */
  sliceKey?: 'projectId' | 'scopeId'
}

/** Register the four wiring contracts against a page (single source of the assertions). */
export function describeProgramLensWiring(c: LensCallContract): void {
  describe(`${c.page} program-lens wiring (MP4)`, () => {
    it('lens ON: the initial list request carries programId', async () => {
      await c.mountWith(LENS_PROGRAM_A)
      const first = c.calls()[0]
      expect(c.calls().length).toBeGreaterThan(0)
      expect(first[c.sliceKey ?? 'projectId']).toBe(LENS_PROJ_ID)
      expect(first.programId).toBe(LENS_PROGRAM_A)
    })

    it('lens OFF: the request carries no programId at all (legacy zero drift)', async () => {
      await c.mountWith(null)
      expect(c.calls().length).toBeGreaterThan(0)
      // Wire-level reading: undefined params are dropped by the request layer,
      // so "no effective programId travels" is the whole legacy contract.
      expect(c.calls()[0].programId ?? null).toBe(null)
    })

    it('lens flip while mounted: reload resets to page 1 with the new programId', async () => {
      const { lens } = await c.mountWith(LENS_PROGRAM_A)
      const base = c.calls().length
      if (c.paged !== false) expect(c.calls()[base - 1].page).toBe(1)
      // Only the public lens switch is driven — the watch must come back to page 1.
      lens.select(LENS_PROGRAM_B)
      await flushPromises()
      expect(c.calls().length).toBeGreaterThan(base)
      const last = c.calls()[c.calls().length - 1]
      expect(last.programId).toBe(LENS_PROGRAM_B)
      if (c.paged !== false) expect(last.page).toBe(1)
    })

    it('lens detach (pick → off): the follow-up request drops programId', async () => {
      const { lens } = await c.mountWith(LENS_PROGRAM_A)
      const base = c.calls().length
      lens.select(null)
      await flushPromises()
      expect(c.calls().length).toBeGreaterThan(base)
      const last = c.calls()[c.calls().length - 1]
      expect(last.programId === undefined || last.programId === null).toBe(true)
    })
  })

  if (c.mountLensOnly) {
    const mountLensOnly = c.mountLensOnly
    // Deep-link posture (drilldown / shared link): the program lens resolves the
    // segment while the single-project anchor is cleared. A page that gates its
    // load on a bare project id fires nothing here and strands on the lens
    // empty state — the regression these three postures lock (R40 single source).
    describe(`${c.page} lens-only deep-link load gate`, () => {
      it('lens ON with the project slice cleared: the list request still fires', async () => {
        await mountLensOnly(LENS_PROGRAM_A)
        expect(c.calls().length).toBeGreaterThan(0)
        expect(c.calls()[0].programId).toBe(LENS_PROGRAM_A)
        if (c.paged !== false) expect(c.calls()[0].page).toBe(1)
      })

      it('lens flip with no project slice: the reload carries the new program id', async () => {
        const { lens } = await mountLensOnly(LENS_PROGRAM_A)
        const base = c.calls().length
        lens.select(LENS_PROGRAM_B)
        await flushPromises()
        expect(c.calls().length).toBeGreaterThan(base)
        expect(c.calls()[c.calls().length - 1].programId).toBe(LENS_PROGRAM_B)
      })

      it('no project and no lens: nothing scopes the query, no request fires', async () => {
        await mountLensOnly(null)
        expect(c.calls().length).toBe(0)
      })
    })
  }
}

/**
 * Empty-state copy contract (MP4-d). The pages above mock their list API to
 * an empty result, so every mount lands on the empty branch — the same
 * fixture doubles as the copy probe:
 * 1. lens ON → the empty area names the program segment (the page's own
 * 2. lens OFF → that phrase NEVER appears (legacy copy, zero drift).
 * Assertions live once here; each page only supplies its mount binding.
 */
export function describeProgramLensEmptyCopy(c: {
  page: string
  mountWith: (activeProgramId: number | null) => Promise<{ wrapper: { text(): string } }>
}): void {
  describe(`${c.page} program-lens empty copy (MP4-d)`, () => {
    it('lens ON + empty list: copy names the program slice and how to leave it', async () => {
      const { wrapper } = await c.mountWith(LENS_PROGRAM_A)
      const text = wrapper.text()
      expect(text).toContain('当前项目集内暂无')
      // The exit-guide wording moved once already (main chain copy pass);
      // pin the durable part — the copy points at the header switcher —
      expect(text).toContain('顶栏项目集切换器')
    })

    it('lens OFF + empty list: legacy copy untouched (no program wording)', async () => {
      const { wrapper } = await c.mountWith(null)
      expect(wrapper.text()).not.toContain('当前项目集内暂无')
    })
  })
}
