
import { describe, expect, it } from 'vitest'

import suiteRaw from '@/views/suite/SuiteListView.vue?raw'
import planRaw from '@/views/plan/PlanListView.vue?raw'
import reportRaw from '@/views/report/ReportView.vue?raw'

const pages: [string, string][] = [
  ['SuiteListView', suiteRaw],
  ['PlanListView', planRaw],
  ['ReportView', reportRaw],
]

describe('小批② 三页接线消费面（单源接线件，禁第二份兜底逻辑）', () => {
  // Correction anchor (triage): this contract predates the ruling
  // that removed page-level ProjectSelect pickers — a page without one legally
  // destructures { projectId, followTick } only, since syncToContext would be
  // dead code with no in-page selector to write back from. The consumption face
  // now has two tiers: the destructure must carry projectId+followTick, and
  // syncToContext must coexist with <ProjectSelect> (scope-equals-container rule).
  it.each(pages)('%s 引入 useProjectPageContext 并消费 projectId/followTick', (_name, src) => {
    expect(src).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(src).toMatch(/const \{ projectId[^}]*followTick[^}]*\} = useProjectPageContext\(\)/)
  })

  it.each(pages)('%s 页内选择器与回写同消同长（有选择器必 syncToContext，无则零回写）', (_name, src) => {
    if (/<ProjectSelect/.test(src)) {
      expect(src).toContain('syncToContext()')
    } else {
      expect(src).not.toMatch(/<ProjectSelect/)
      expect(src).not.toContain('syncToContext()')
    }
  })

  it.each(pages)('%s 头部上下文驱动跟随（followTick watch 在位）', (_name, src) => {
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(pages)('%s 旧「首项兜底 autoSelectFirst/取[0]」退役', (_name, src) => {
    expect(src).not.toMatch(/projects\.value\[0\]\.projectId/)
    expect(src).not.toMatch(/projects\.value\[0\]\.id/)
    expect(src).not.toMatch(/auth\.projects\[0\]/)
  })
})
