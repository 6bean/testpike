/**
 * D-face (R62) AI drafting-job drawer auto-follow contract: the job
 * panel of the mock docs page keeps its own list fresh with the SHARED
 * useListAutoFollow beat (one loop implementation for every list panel,
 * R40), the beat interval rides the fourth hot-updated knob
 * `ui.list.auto-follow-ms`, no second polling channel is born here, and the
 * drawer ships no refresh-instruction copy. Guard family mirrors
 * specImportTailWiringContract.test.ts (same judgement shape, R40).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { useListAutoFollow } from '@/composables/useListAutoFollow'
import { MOCK_AI_JOB_PENDING_STATUSES, isMockAiJobPending } from '@/api/mock'

const here = dirname(fileURLToPath(import.meta.url))

function read(rel: string): string {
  return readFileSync(resolve(here, rel), 'utf8')
}

const viewSrc = read('../../src/views/mock/MockDocsView.vue')
const mockSrc = read('../../src/api/mock.ts')
const knobsSrc = read('../../src/composables/tailWindowConfig.ts')
const jobSvcSrc = read(
  '../../../core/src/main/java/com/sixbean/testpike/core/mock/domain/service/impl/MockAiJobServiceImpl.java')
const migrationSrc = read('../../../db/migration/V578__mock_jobs_auto_follow.sql')
const zhLocales = read('../../src/utils/locales/zh-CN.ts')
const enLocales = read('../../src/utils/locales/en-US.ts')

/** segment the nested `mockAi` locale block (2-space indent at top level). */
function mockAiBlock(src: string): string {
  const start = src.indexOf('\n  mockAi: {')
  expect(start, 'locale block mockAi missing').toBeGreaterThan(-1)
  const end = src.indexOf('\n  },', start)
  expect(end, 'locale block mockAi unterminated').toBeGreaterThan(start)
  return src.slice(start, end)
}

interface BeatRow { id: number; status: string }

function makeRows(...statuses: string[]): BeatRow[] {
  return statuses.map((status, i) => ({ id: i + 1, status }))
}

describe('useListAutoFollow：自动跟随节拍单元形态', () => {
  it('有进行中行时按拍重读；节拍为递归 setTimeout 单请求在途', async () => {
    vi.useFakeTimers()
    try {
      let rows = makeRows('queued')
      let reloads = 0
      let release: (() => void) | null = null
      const scope = effectScope()
      const handle = scope.run(() =>
        useListAutoFollow<BeatRow>({
          active: () => true,
          rows: () => rows,
          isPending: (r) => r.status === 'queued' || r.status === 'running',
          reload: () => {
            reloads += 1
            return new Promise<void>((res) => {
              release = () => {
                rows = makeRows('preview')
                res()
              }
            })
          },
          intervalMs: () => 2000,
        }),
      )!
      handle.start()
      expect(handle.following.value).toBe(true)
      await vi.advanceTimersByTimeAsync(2000)
      // one request in flight: a second beat cannot stack while it pends
      await vi.advanceTimersByTimeAsync(4000)
      expect(reloads).toBe(1)
      release!()
      await nextTick()
      await vi.advanceTimersByTimeAsync(0)
      // row settled on the re-read: the loop self-stops (terminal sealing)
      expect(handle.following.value).toBe(false)
      scope.stop()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('重读失败不断拍：上一快照保留，下一拍继续重试', async () => {
    vi.useFakeTimers()
    try {
      let reloads = 0
      const scope = effectScope()
      const handle = scope.run(() =>
        useListAutoFollow<BeatRow>({
          active: () => true,
          rows: () => makeRows('running'),
          isPending: (r) => r.status === 'running',
          reload: async () => {
            reloads += 1
            throw new Error('network blip')
          },
          intervalMs: () => 1000,
        }),
      )!
      handle.start()
      await vi.advanceTimersByTimeAsync(1000)
      await vi.advanceTimersByTimeAsync(1000)
      expect(reloads).toBe(2)
      handle.stop()
      expect(handle.following.value).toBe(false)
      scope.stop()
    } finally {
      vi.useRealTimers()
    }
  })

  it('面板关闭即停拍；无进行中行不起拍', async () => {
    vi.useFakeTimers()
    try {
      let active = true
      let reloads = 0
      const scope = effectScope()
      const handle = scope.run(() =>
        useListAutoFollow<BeatRow>({
          active: () => active,
          rows: () => makeRows('queued'),
          isPending: (r) => r.status === 'queued',
          reload: async () => {
            reloads += 1
          },
          intervalMs: () => 2000,
        }),
      )!
      const idleScope = effectScope()
      const idleHandle = idleScope.run(() =>
        useListAutoFollow<BeatRow>({
          active: () => true,
          rows: () => makeRows('consumed', 'failed', 'cancelled'),
          isPending: (r) => r.status === 'queued' || r.status === 'running',
          reload: async () => {
            reloads += 1
          },
          intervalMs: () => 2000,
        }),
      )!
      idleHandle.start()
      expect(idleHandle.following.value).toBe(false)
      handle.start()
      active = false
      await nextTick()
      await vi.advanceTimersByTimeAsync(5000)
      expect(handle.following.value).toBe(false)
      expect(reloads).toBe(0)
      scope.stop()
      idleScope.stop()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('值面与接线形态：D 面自动跟随（单源底座+热更键+零第二通道）', () => {
  it('pending 值域与 core 状态字面对账（写侧漂移即红）', () => {
    expect(MOCK_AI_JOB_PENDING_STATUSES).toEqual(['queued', 'running'])
    for (const literal of MOCK_AI_JOB_PENDING_STATUSES) {
      expect(jobSvcSrc, `core STATUS literal ${literal}`).toContain(`"${literal}"`)
    }
    expect(isMockAiJobPending({ status: 'queued' })).toBe(true)
    expect(isMockAiJobPending({ status: 'running' })).toBe(true)
    expect(isMockAiJobPending({ status: 'preview' })).toBe(false)
    expect(isMockAiJobPending({ status: 'consumed' })).toBe(false)
    expect(isMockAiJobPending({ status: 'failed' })).toBe(false)
    expect(isMockAiJobPending({ status: 'cancelled' })).toBe(false)
  })

  it('热更键第四枚在单一读取点注册，缺省与区间守卫在位', () => {
    expect(knobsSrc).toContain("'ui.list.auto-follow-ms'")
    expect(knobsSrc).toContain('LIST_AUTO_FOLLOW_DEFAULT_MS = 2000')
    expect(knobsSrc).toContain('pickInt(rows, KEY_LIST_AUTO_FOLLOW_MS, LIST_AUTO_FOLLOW_DEFAULT_MS, 1000, 60000)')
    expect(migrationSrc).toContain("'ui.list.auto-follow-ms', '2000'")
  })

  it('MockDocsView 消费共享跟随件且无第二通道：零 setInterval，抽屉挂徽标与节拍启停', () => {
    expect(viewSrc).toContain("import { useListAutoFollow } from '@/composables/useListAutoFollow'")
    expect(viewSrc).toContain('const aiJobsFollow = useListAutoFollow<MockAiJobItem>')
    expect(viewSrc).toContain('isPending: isMockAiJobPending')
    expect(viewSrc).toContain('intervalMs: () => listAutoFollowMs.value')
    expect(viewSrc).toContain('aiJobsFollow.start()')
    expect(viewSrc).toContain('data-test="mock-ai-jobs-following"')
    expect(viewSrc).not.toContain('setInterval')
    // the follow re-read rides the silent mode of the same loader (one channel)
    expect(viewSrc).toContain('loadAiJobs({ silent: true })')
  })

  it('zh/en 回执键对称且零刷新指令句式（R62④）', () => {
    const zh = mockAiBlock(zhLocales)
    const en = mockAiBlock(enLocales)
    expect(zh).toContain('jobsFollowing:')
    expect(en).toContain('jobsFollowing:')
    for (const line of zh.split('\n')) {
      expect(line, `zh instruction copy: ${line.trim()}`).not.toMatch(/(手动刷新|点击刷新|刷新页面|刷新后重试|稍后重开|重新打开本?对话)/)
    }
    for (const line of en.split('\n')) {
      expect(line, `en instruction copy: ${line.trim()}`).not.toMatch(/refresh (the )?(page|list) (manually)?|click refresh|reopen/i)
    }
  })

  it('R61 帮助章同批改：仿真文档章追加「起草任务状态自动更新」节（幂等守卫）', () => {
    expect(migrationSrc).toContain('起草任务状态自动更新')
    expect(migrationSrc).toContain("domain_code = 'T'")
    expect(migrationSrc).toContain('NOT LIKE')
    expect(migrationSrc).toContain('ON CONFLICT (config_key) DO NOTHING')
  })
})
