/**
 * C-face (R62) spec-import tail wiring contract: the channel-B AI
 * extraction wizard rides the SHARED tail base (useTestRunTail +
 * TaskTailDrawer + shared hot keys), seals on the server's terminal frames
 * and ships no refresh/reopen instruction copy. Guard family mirrors
 * batchTailWiringContract.test.ts / exploreTailWiringContract.test.ts
 * (same judgement shape, no second gate, R40).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  SPEC_EXTRACT_TERMINAL_PREFIXES,
  isSpecExtractTerminalRow,
  specExtractFailureReason,
} from '@/api/apiSpec'

const here = dirname(fileURLToPath(import.meta.url))

function read(rel: string): string {
  return readFileSync(resolve(here, rel), 'utf8')
}

const viewSrc = read('../../src/views/apitest/SpecImportWizard.vue')
const eventsSrc = read('../../src/api/taskEvents.ts')
const writerSrc = read(
  '../../../ai-service/src/main/java/com/sixbean/testpike/aiservice/spec/domain/service/impl/SpecDraftExtractServiceImpl.java')
const zhLocales = read('../../src/utils/locales/zh-CN.ts')
const enLocales = read('../../src/utils/locales/en-US.ts')

/** segment the nested `specImport` locale block (4-space indent inside apitest). */
function specImportBlock(src: string): string {
  const start = src.indexOf('\n    specImport: {')
  expect(start, 'locale block specImport missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n    },', start)
  expect(end, 'locale block specImport unterminated').toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('isSpecExtractTerminalRow：抽取尾窗两型终态帧命中', () => {
  it('完成帧与失败帧都算终态（尾窗在此收档）', () => {
    expect(isSpecExtractTerminalRow({ summary: '接口文档抽取完成|接口 12 个' })).toBe(true)
    expect(isSpecExtractTerminalRow({ summary: '接口文档抽取失败|文档中未识别到接口定义，请确认上传的是接口设计文档' })).toBe(true)
  })

  it('发起帧与分组进度帧不封窗（尾窗不得中途收档）', () => {
    expect(isSpecExtractTerminalRow({ summary: '接口文档抽取已发起|分块 8 组 3' })).toBe(false)
    expect(isSpecExtractTerminalRow({ summary: '接口文档抽取进行中|第 2/3 组完成' })).toBe(false)
  })

  it('失败原因取竖线后原文，完成行返回 null', () => {
    expect(specExtractFailureReason({ summary: '接口文档抽取失败|模型调用超时' })).toBe('模型调用超时')
    expect(specExtractFailureReason({ summary: '接口文档抽取完成|接口 3 个' })).toBeNull()
  })

  it('前缀值面与后端写侧源码对账（写侧句子漂移即红）', () => {
    expect(writerSrc).toContain(`"${SPEC_EXTRACT_TERMINAL_PREFIXES[0]}接口 "`)
    expect(writerSrc).toContain(`"${SPEC_EXTRACT_TERMINAL_PREFIXES[1]}"`)
  })
})

describe('SpecImportWizard 尾窗接线形态（消费共享底座单源）', () => {
  it('只 import 底座两文件与共享热更键，旧轮询机组已摘除', () => {
    expect(viewSrc).toContain("import TaskTailDrawer from '@/components/TaskTailDrawer.vue'")
    expect(viewSrc).toContain("import { useTestRunTail } from '@/composables/useTestRunTail'")
    expect(viewSrc).toContain('surveyTailCap')
    expect(viewSrc).toContain('tailTerminalDwellMs')
    expect(viewSrc).toContain('terminal: isSpecExtractTerminalRow')
    expect(viewSrc).not.toContain('setInterval')
    expect(viewSrc).not.toContain('POLL_INTERVAL_MS')
    expect(viewSrc).not.toContain('startPolling')
    // the cursor door rides the shared fetchEventWindow, no second decoder (R40)
    expect(eventsSrc).toContain('/spec-extraction-events')
    expect(eventsSrc).toContain('fetchEventWindow')
  })

  it('发起即开窗、确认卡取消不撤观测、终态自动进入预览（R62①②③）', () => {
    expect(viewSrc).toContain('specTail.beginPending()')
    expect(viewSrc).toContain('specTail.cancelPending()')
    expect(viewSrc).toMatch(/openRun\(\{ runToken: String\(documentId\) \}\)/)
    expect(viewSrc).toContain('watchSpecExtraction(documentId)')
    expect(viewSrc).toContain('data-test="spec-import-tail"')
    // reset and scope dispose both close the window (no orphan poll loop, R57)
    expect(viewSrc).toContain('closeSpecTail()')
    expect(viewSrc).toContain('onScopeDispose(closeSpecTail)')
  })
})

describe('值面守卫：specImport 回执文案（zh/en 对称，零刷新/重开指令句式）', () => {
  it('zh/en 回执键对称且零指令句式', () => {
    const zh = specImportBlock(zhLocales)
    const en = specImportBlock(enLocales)
    for (const key of ['extractQueuedNote', 'extractStarted', 'extractFailed', 'extractInterrupted']) {
      expect(zh, `zh specImport.${key}`).toContain(`${key}:`)
      expect(en, `en specImport.${key}`).toContain(`${key}:`)
    }
    for (const line of zh.split('\n')) {
      expect(line, `zh instruction copy: ${line.trim()}`).not.toMatch(/(手动刷新|点击刷新|刷新页面|刷新后重试|稍后重开|重新打开本向导)/)
    }
    for (const line of en.split('\n')) {
      expect(line, `en instruction copy: ${line.trim()}`).not.toMatch(/reopen this wizard|refresh the (page|wizard)/i)
    }
  })

  it('轮询退役键两语言删净（无孤儿键，R31）', () => {
    const zh = specImportBlock(zhLocales)
    const en = specImportBlock(enLocales)
    for (const key of ['extractPolling', 'extractTimeout', 'pollFail']) {
      expect(zh, `zh still carries ${key}`).not.toContain(`${key}:`)
      expect(en, `en still carries ${key}`).not.toContain(`${key}:`)
    }
  })
})
