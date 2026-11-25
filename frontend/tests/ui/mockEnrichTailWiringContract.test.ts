/**
 * (R62/R67) mock-enrich tail wiring contract: the simulation-space docs page
 * files the bulk data-enrichment job through the shared AI job door and
 * replays its process tail through the SHARED tail base and the SHARED
 * drawer — one cursor channel opened dispatch-first, terminal collapse,
 * the retention pointer naming its hot-reload parameter, a revisit deep
 * link, and NO manual-refresh affordance left on the process surface.
 * Guard family mirrors exploreTailWiringContract.test.ts (same judgement
 * shape, no second gate, R40).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { isMockEnrichTerminalRow } from '@/api/mock'

const here = dirname(fileURLToPath(import.meta.url))

function read(rel: string): string {
  return readFileSync(resolve(here, rel), 'utf8')
}

const viewSrc = read('../../src/views/mock/MockDocsView.vue')
const zhLocales = read('../../src/utils/locales/zh-CN.ts')
const enLocales = read('../../src/utils/locales/en-US.ts')

function event(cursor: number, summary: string, level = 'step') {
  return { cursor, level, summary, detail: null, landedAt: `2026-11-25T09:0${cursor}:00` }
}

describe('isMockEnrichTerminalRow：批量完善尾窗终态帧两族前缀单源命中', () => {
  it('草稿就绪封存与失败封存都算终态帧', () => {
    expect(isMockEnrichTerminalRow(event(1, '草稿就绪：共 42 条完善草稿待人工勾选确认'))).toBe(true)
    expect(isMockEnrichTerminalRow(event(2, '执行失败：模型服务暂不可达，可稍后重新发起'))).toBe(true)
  })

  it('步骤帧与心跳帧不是终态（尾窗不得中途收档）', () => {
    expect(isMockEnrichTerminalRow(event(3, '端点分 3 批，第 2/3 批 AI 数据增强完成'))).toBe(false)
    expect(isMockEnrichTerminalRow(event(4, '预裁回填完成'))).toBe(false)
  })

  it('前缀值面与生成侧写家源码对账（写侧句子漂移即红）', () => {
    const runner = read(
      '../../../ai-service/src/main/java/com/sixbean/testpike/aiservice/mock/domain/service/impl/MockDraftGenServiceImpl.java',
    )
    expect(runner).toContain('"草稿就绪：')
    expect(runner).toContain('"执行失败：')
  })
})

describe('MockDocsView 批量完善与进程尾窗接线契约', () => {
  it('共享件单源挂载：抽屉与游标窗走平台件，禁第二套窗口实现', () => {
    expect(viewSrc).toContain("import TaskTailDrawer from '@/components/TaskTailDrawer.vue'")
    expect(viewSrc).toContain("import { useTestRunTail } from '@/composables/useTestRunTail'")
    expect(viewSrc).toContain('isMockEnrichTerminalRow')
    expect(viewSrc).toContain('listAiJobProcessEvents')
  })

  it('入口三锚在位：批量动作钮、卡片进程详情钮、派发先开窗', () => {
    expect(viewSrc).toContain('data-test="mock-docs-ai-bulk"')
    expect(viewSrc).toContain('data-test="mock-ai-bulk-dialog"')
    expect(viewSrc).toContain('data-test="mock-ai-job-tail"')
    expect(viewSrc).toContain('data-test="mock-ai-job-tail-drawer"')
    expect(viewSrc).toMatch(/beginPending\(\)[\s\S]*?await createAiJob/)
  })

  it('批量任务经桥面携带契约版本行（specId 直达 core 门）', () => {
    expect(viewSrc).toMatch(/jobType: 'spec-enrich'[\s\S]*?specId: bulkSpecId\.value/)
  })

  it('R67 回看三件：深链入口 + 过期指路参数键字面 + 无手动刷新指令句', () => {
    expect(viewSrc).toContain('route.query.job')
    expect(viewSrc).toContain('retention-param="mock.spec-import.history-frame-cap"')
    expect(viewSrc).not.toMatch(/点击刷新|手动刷新/)
  })

  it('R67 过期判定单源消费：封存作业空窗回看调平台件公式，禁页面另写判定', () => {
    expect(viewSrc).toMatch(/openTailRun\([\s\S]*?\.then\(\(\) => \{[\s\S]*?noteReplayExpiry/)
    expect(viewSrc).toMatch(/job\.status !== 'queued' && job\.status !== 'running'/)
    expect(viewSrc).not.toMatch(/expired\.value\s*=/)
  })

  it('双语键对称：批量族与进程详情键 zh/en 两面齐', () => {
    for (const key of [
      'bulkEntry:', 'bulkDialogTitle:', 'bulkFSpec:', 'bulkSpecPh:',
      'bulkHint:', 'bulkSubmit:', 'bulkNeedsSpec:', 'bulkFiled:', 'actProcessDetail:', 'bulkPrompt:',
    ]) {
      expect(zhLocales).toContain(key)
      expect(enLocales).toContain(key)
    }
    expect(zhLocales).toContain("'spec-enrich': 'AI 数据完善'")
  })

  it('尾窗抬头随观察态分支：封存回放不得挂实时词（R62/R67 文案反馈判据）', () => {
    const drawerSrc = read('../../src/components/TaskTailDrawer.vue')
    expect(drawerSrc).toContain("{{ live ? t('document.tailTitle') : t('document.tailTitleReplay') }}")
    expect(drawerSrc).toContain("{{ live ? t('document.tailJumpLatest') : t('document.tailJumpEnd') }}")
    // The head used to render one static key, which labelled a finished
    // job's replay as live streaming. That unconditioned form must not return.
    expect(drawerSrc).not.toMatch(/data-test="task-tail-title">\{\{ t\('document\.tailTitle'\) \}\}/)
  })

  it('双语键对称：tailTitleReplay / tailJumpEnd zh/en 两面齐', () => {
    for (const key of ['tailTitleReplay:', 'tailJumpEnd:']) {
      expect(zhLocales).toContain(key)
      expect(enLocales).toContain(key)
    }
    expect(zhLocales).toContain("tailTitleReplay: '过程详情（历史回放）'")
    expect(zhLocales).toContain("tailJumpEnd: '跳到末尾'")
  })
})
