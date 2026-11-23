/**
 * layout contract for the API-test workbench deep-link honesty.
 *
 * Posture = the ?raw source mirror used by apiTestArchT413a.test.ts: the
 * assertions read the shipped template/script text of ApiTestView.vue so a
 * regression that re-introduces the silent "create your first interface-set"
 * empty state on top of a dead ?projectId fails here before the browser
 * renders it.
 *
 * Clauses (bug-report ruling for the deep-link honesty case):
 * 1. the invalid-project verdict is derived from the auth visible-domain
 * snapshot only (no second selector, no core-side logic import);
 * 2. the rail renders a dedicated named absence state
 * (data-test=apitest-project-invalid) BEFORE the create-inducement
 * empty state can show;
 * 3. every project-scoped door (data-source menu, import/subscription
 * items, create-collection button) is gated by the same
 * projectMissing verdict — a dead link closes doors exactly like an
 * empty header pick;
 * 4. the page-side ownership echo (ProjectOwnerEcho, single source per
 * the ownership-echo iron rule) stays mounted in the rail top so a
 * valid deep link shows which project segment the lists belong to;
 * 5. both locale families carry the plain-word callout keys (i18n dual
 * track, no hard-coded Chinese in the view).
 */
import { describe, expect, it } from 'vitest'

import viewRaw from '@/views/apitest/ApiTestView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T415: invalid deep-linked project is derived from one visible-domain source', () => {
  it('computes projectInvalid from the auth store snapshot (no second ruling)', () => {
    expect(viewRaw).toContain('const projectInvalid = computed(')
    expect(viewRaw).toContain('auth.projects.some((p) => p.projectId === projectId.value)')
    // empty-snapshot guard: never accuses while /users/me is still cold
    expect(viewRaw).toContain('auth.projects.length > 0')
  })

  it('folds the invalid state into one projectMissing door gate', () => {
    expect(viewRaw).toContain(
      'const projectMissing = computed(() => projectId.value === null || projectInvalid.value)',
    )
  })
})

describe('T415: the named absence state wins over the create-inducement empty state', () => {
  it('renders the plain-word callout container in the rail', () => {
    expect(viewRaw).toContain('data-test="apitest-project-invalid"')
    expect(viewRaw).toContain("t('apitest.projectInvalid')")
  })

  it('checks the invalid branch before the empty-pick branch and before any empty guide', () => {
    const invalidIdx = viewRaw.indexOf('v-if="projectInvalid"')
    const pickIdx = viewRaw.indexOf('v-else-if="projectId === null"')
    // Assertion-lag fix : the empty-guide copy moved into the
    // railEmptyCopy computed in the script region, so the bare key literal
    // sits before the rail branches now. The rail branch element that
    // renders the empty guide is pinned instead.
    const emptyIdx = viewRaw.indexOf('v-else-if="filteredCollections.length === 0"')
    expect(invalidIdx).toBeGreaterThan(-1)
    expect(pickIdx).toBeGreaterThan(invalidIdx)
    expect(emptyIdx).toBeGreaterThan(pickIdx)
  })
})

describe('T415: project-scoped doors all obey the same gate', () => {
  it('gates the data-source menu button and its import/subscription items', () => {
    expect(viewRaw).toMatch(/apitest-datasource-menu[\s\S]{0,400}:disabled="projectMissing"/)
    expect(viewRaw).toContain('data-test="apitest-import-from-spec-btn" :disabled="projectMissing"')
    expect(viewRaw).toContain('data-test="apitest-subscription-btn" :disabled="projectMissing"')
  })

  it('gates the create-collection button and hides the mock bridge doors', () => {
    expect(viewRaw).toContain(':disabled="projectMissing"\n                  @click="addCollection"')
    expect(viewRaw).toContain("v-if=\"!projectMissing && auth.hasPerm('perm:mock:read')\"")
    expect(viewRaw).toContain("v-if=\"!projectMissing && auth.hasPerm('perm:mock:write')\"")
  })

  it('points the create-button tooltip at the honest reason per state', () => {
    expect(viewRaw).toContain(
      "t(projectInvalid ? 'apitest.projectInvalidHint' : 'apitest.pickProjectInHeader')",
    )
  })
})

describe('T415: ownership echo stays single-sourced (case 2 anchor)', () => {
  it('mounts ProjectOwnerEcho in the rail top (explicit deep links never write back to the header, so the page-side echo is the visibility door)', () => {
    expect(viewRaw).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
    const railTopIdx = viewRaw.indexOf('ap-rail-top')
    const echoIdx = viewRaw.indexOf('<ProjectOwnerEcho />')
    expect(railTopIdx).toBeGreaterThan(-1)
    expect(echoIdx).toBeGreaterThan(railTopIdx)
  })
})

describe('T415: i18n dual track for the plain-word callout', () => {
  it('carries both keys in the zh and en locale families', () => {
    expect(zhRaw).toContain('projectInvalid:')
    expect(zhRaw).toContain('projectInvalidHint:')
    expect(enRaw).toContain('projectInvalid:')
    expect(enRaw).toContain('projectInvalidHint:')
  })

  it('keeps the view free of hard-coded Chinese callout text', () => {
    expect(viewRaw).not.toMatch(/项目不存在或你无权限/)
  })
})
