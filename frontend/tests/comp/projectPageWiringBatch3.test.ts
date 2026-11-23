
import { describe, expect, it } from 'vitest'

import batchRaw from '@/views/batch/BatchListView.vue?raw'
import dataFactoryRaw from '@/views/datafactory/DataFactoryListView.vue?raw'
import defectRaw from '@/views/defect/DefectListView.vue?raw'
import documentRaw from '@/views/ai/DocumentListView.vue?raw'
import scheduleRaw from '@/views/schedule/ScheduleFireListView.vue?raw'
import boardRaw from '@/views/plan/BoardView.vue?raw'
import exploreRaw from '@/views/explore/ExploreListView.vue?raw'
import testReportRaw from '@/views/report/TestReportListView.vue?raw'
import deleteRequestRaw from '@/views/case/DeleteRequestView.vue?raw'
import coverageRaw from '@/views/coverage/CoverageMatrixView.vue?raw'
import aiGenRaw from '@/views/ai/AiGenerateView.vue?raw'
import reportCenterRaw from '@/views/report-center/ReportCenterView.vue?raw'

const pages: [string, string][] = [
  ['BatchListView', batchRaw],
  ['DataFactoryListView', dataFactoryRaw],
  ['DefectListView', defectRaw],
]

const round2Pages: [string, string][] = [
  ['DocumentListView', documentRaw],
  ['ScheduleFireListView', scheduleRaw],
  ['BoardView', boardRaw],
]

const round3Pages: [string, string][] = [
  ['ExploreListView', exploreRaw],
  ['TestReportListView', testReportRaw],
  ['DeleteRequestView', deleteRequestRaw],
]

// Correction anchor (triage): these contracts predate the ruling
// that removed page-level ProjectSelect pickers — a page without one legally
// destructures { projectId, followTick } only, because syncToContext would be
// dead code with no in-page selector to write back from. The consumption face
// now has two tiers: the destructure must carry projectId+followTick, and
// syncToContext must coexist with <ProjectSelect> (scope-equals-container rule,
// same posture as projectPageWiringBatch2 corrected in the same stroke).
function expectContextConsumption(name: string, src: string) {
  expect(src, name).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
  expect(src, name).toMatch(/const \{ projectId[^}]*followTick[^}]*\} = useProjectPageContext\(\)/)
}

function expectSelectorWritebackPair(name: string, src: string) {
  if (/<ProjectSelect/.test(src)) {
    expect(src, `${name}: in-page selector present, write-back required`).toContain('syncToContext()')
  } else {
    expect(src, `${name}: no in-page selector, syncToContext would be dead code`).not.toContain('syncToContext()')
  }
}

describe('小批③a 轮1 三页接线消费面（单源接线件，禁第二份兜底逻辑）', () => {
  it.each(pages)('%s 引入 useProjectPageContext 并消费 projectId/followTick', expectContextConsumption)

  it.each(pages)('%s 页内选择器与回写同消同长', expectSelectorWritebackPair)

  it.each(pages)('%s 头部上下文驱动跟随（followTick watch 在位）', (_name, src) => {
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(pages)('%s 旧「首项兜底/页内自解 URL 参」退役（兜底与优先链逻辑只活在接线件单源）', (_name, src) => {
    expect(src).not.toMatch(/projects\.value\[0\]\.projectId/)
    expect(src).not.toMatch(/projects\.value\[0\]\.id/)
    expect(src).not.toMatch(/auth\.projects\[0\]/)
    expect(src).not.toMatch(/resolveProjectFromQuery\(/)
  })
})

describe('小批③a 轮2 三页接线消费面（文档/定时触发/计划看板，同轮1 四断言谱）', () => {
  it.each(round2Pages)('%s 引入 useProjectPageContext 并消费 projectId/followTick', expectContextConsumption)

  it.each(round2Pages)('%s 页内选择器与回写同消同长', expectSelectorWritebackPair)

  it.each(round2Pages)('%s 头部上下文驱动跟随（followTick watch 在位）', (_name, src) => {
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(round2Pages)('%s 旧「首项兜底/页内自解 URL 参/登录快照硬编码」退役（兜底与优先链逻辑只活在接线件单源）', (_name, src) => {
    expect(src).not.toMatch(/projects\.value\[0\]\.projectId/)
    expect(src).not.toMatch(/projects\.value\[0\]\.id/)
    expect(src).not.toMatch(/auth\.projects\[0\]/)
    expect(src).not.toMatch(/resolveProjectFromQuery\(/)
  })
})

describe('特殊3页：覆盖率矩阵（单选页全接线谱）+ AI 生成/报表中心（预填不回写谱）', () => {
  // R30 (10-25): the coverage matrix retired its in-page picker — the header is
  // the only project switch, so the consumption face is { projectId, followTick }
  // (the pairing rule of the correction applies: no selector, no write-back).
  it('覆盖率矩阵 引入 useProjectPageContext 并消费 projectId/followTick（R30 纯消费面）', () => {
    expect(coverageRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(coverageRaw).toMatch(/const \{ projectId[^}]*followTick[^}]*\} = useProjectPageContext\(\)/)
  })

  it('覆盖率矩阵 无页内选择器零回写 + 头部跟随 watch(followTick) 重载', () => {
    expect(coverageRaw).not.toMatch(/<ProjectSelect/)
    expect(coverageRaw).not.toContain('syncToContext()')
    expect(coverageRaw).toMatch(/watch\(followTick/)
  })

  it('覆盖率矩阵 旧「远程首页首项兜底/页内自解 URL 参」退役（兜底与优先链逻辑只活在接线件单源）', () => {
    expect(coverageRaw).not.toMatch(/projects\.value\[0\]\.id/)
    expect(coverageRaw).not.toMatch(/resolveProjectFromQuery\(/)
  })

  // AI generate wizard (T396b): the in-page project selector retired and the
  // ownership echo took its place — the page now rides the shared context wiring
  // piece exactly like the other context pages (deep link ?projectId still wins
  // as the explicit parameter via the composable's priority chain).
  it('AI 生成 经接线件消费头部上下文且旧 store 预填/快照首项兜底退役', () => {
    expect(aiGenRaw).toContain("import { useProjectPageContext } from '@/composables/useProjectPageContext'")
    expect(aiGenRaw).not.toContain("import { useProjectContextStore } from '@/stores/projectContext'")
    expect(aiGenRaw).not.toMatch(/projects\.value\[0\]\.projectId/)
  })

  it('AI 生成 上下文消费位单源（无页内第二选择器，回写由接线件 sync 律承载）', () => {
    expect(aiGenRaw).not.toMatch(/<ProjectSelect/)
    expect(aiGenRaw).not.toMatch(/ctx\.select\(|ctxStore\.select\(/)
  })

  it('报表中心 非 ADMIN 锁项目兜底改头部 primaryId 且登录快照首项退役', () => {
    expect(reportCenterRaw).toContain("import { useProjectContextStore } from '@/stores/projectContext'")
    expect(reportCenterRaw).toMatch(/primaryId/)
    expect(reportCenterRaw).not.toMatch(/authStore\.projects\[0\]/)
  })

  it('报表中心 页私有筛选不反写全局上下文（聚合视图清空=诚实全量非全局状态变更）', () => {
    expect(reportCenterRaw).not.toMatch(/syncToContext/)
    expect(reportCenterRaw).not.toMatch(/ctx\.select\(|ctxStore\.select\(/)
  })
})

describe('小批③a 轮3 三页接线消费面（探索会话/测试报告/删除审批，同轮1 四断言谱）', () => {
  it.each(round3Pages)('%s 引入 useProjectPageContext 并消费 projectId/followTick', expectContextConsumption)

  it.each(round3Pages)('%s 页内选择器与回写同消同长', expectSelectorWritebackPair)

  it.each(round3Pages)('%s 头部上下文驱动跟随（followTick watch 在位）', (_name, src) => {
    expect(src).toMatch(/watch\(followTick/)
  })

  it.each(round3Pages)('%s 旧「首项兜底/页内自解 URL 参/登录快照硬编码」退役（兜底与优先链逻辑只活在接线件单源）', (_name, src) => {
    expect(src).not.toMatch(/projects\.value\[0\]\.projectId/)
    expect(src).not.toMatch(/projects\.value\[0\]\.id/)
    expect(src).not.toMatch(/auth\.projects\[0\]/)
    expect(src).not.toMatch(/resolveProjectFromQuery\(/)
  })
})
