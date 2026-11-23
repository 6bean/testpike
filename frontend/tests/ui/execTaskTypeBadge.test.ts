/**
 * Execution-center task-kind badge layout contract.
 *
 * Capabilities pinned down:
 *  - task-kind vocabulary lives in one api-layer dictionary (single source,
 *    same td() i18n pattern as EXEC_TASK_STATUS — no second map, no hard-coded
 *    Chinese in the view);
 *  - the vocabulary covers the full core ticket set 1..6 and both locale
 *    tracks carry the dict.exec.taskType.* keys;
 *  - the trial-run table renders the badge column (EXEC_TASK_COLUMNS entry),
 *    the executor task table renders the same dictionary, and only the
 *    pressure badge deep-links to the run detail page;
 *  - a dirty pressure row without a pinned run id stays a plain badge (honest
 *    absence — never a broken link).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'

const SRC = join(__dirname, '..', '..', 'src')

function read(rel: string): string {
  return readFileSync(join(SRC, rel), 'utf8')
}

const TASK_TYPE_KEYS = ['case', 'toolDryRun', 'exploreAct', 'apiRun', 'perfRun', 'appAuto']

describe('execution center task-kind badge contract', () => {
  it('EXEC_TASK_TYPE single-source dictionary lives in the api layer (td-translated, six values)', () => {
    const api = read('api/executor.ts')
    expect(api, 'executor.ts must host the EXEC_TASK_TYPE single source').toContain('export const EXEC_TASK_TYPE')
    for (const k of TASK_TYPE_KEYS) {
      expect(api, `dictionary lacks dict.exec.taskType.${k}`).toContain(`dict.exec.taskType.${k}`)
    }
  })

  it('zh/en tracks carry identical dict.exec.taskType keys matching the six core task kinds', () => {
    const zh = (zhCN as Record<string, any>).dict.exec.taskType
    const en = (enUS as Record<string, any>).dict.exec.taskType
    expect(zh, 'zh-CN lacks the dict.exec.taskType group').toBeTruthy()
    expect(Object.keys(zh).sort()).toEqual([...TASK_TYPE_KEYS].sort())
    expect(Object.keys(en).sort(), 'en-US key set drifted from zh-CN').toEqual([...TASK_TYPE_KEYS].sort())
  })

  it('column-settings header key exec.taskType hits both locale tracks', () => {
    const zh = (zhCN as Record<string, any>).exec
    const en = (enUS as Record<string, any>).exec
    expect(typeof zh.taskType).toBe('string')
    expect(typeof en.taskType).toBe('string')
  })

  it('trial-run EXEC_TASK_COLUMNS carries taskType; both tables render the same badge piece (no second view mapping)', () => {
    const view = read('views/execution/ExecutionCenterView.vue')
    expect(view, 'EXEC_TASK_COLUMNS must define the taskType column').toMatch(/key:\s*'taskType'/)
    expect(view, 'the view must render the taskType badge branch').toMatch(/col\.key === 'taskType'/)
    // the badge piece imports the single-source dictionary (views never touch it directly = machine check against second mappings).
    const badge = read('components/ExecTaskTypeBadge.vue')
    expect(badge, 'the badge piece must import the EXEC_TASK_TYPE single source').toContain('EXEC_TASK_TYPE')
    // core SORT_COLUMNS whitelists no task_type, so the badge column stays unsortable.
    expect(view, 'SORT_KEY_MAP must not grow taskType (core whitelist untouched)').not.toMatch(/taskType:\s*'/)
  })

  it('pressure badge deep-links /perf/run/:id; empty perfRunId stays a plain badge (one piece reused by both tables)', () => {
    const badge = read('components/ExecTaskTypeBadge.vue')
    expect(badge, 'the badge piece must deep-link the run detail page').toContain('/perf/run/')
    expect(badge, 'the deep link requires taskType=5 and a non-null perfRunId').toMatch(/taskType === 5 && .*perfRunId/)
    expect(badge).toContain('perf-run-link')
    // Both tables (trial-run v-for column + K2 executor task table) consume this one badge piece.
    const view = read('views/execution/ExecutionCenterView.vue')
    expect(view).toContain('ExecTaskTypeBadge')
  })

  it('vocabulary is unique: EXEC_TASK_TYPE is defined only in api/executor.ts', () => {
    const view = read('views/execution/ExecutionCenterView.vue')
    expect(view, 'the view must not invent a second task-kind mapping').not.toContain('const TASK_TYPE')
    const component = read('components/ExecTaskResultView.vue')
    // The detail drawer shows no hard-coded Chinese task-kind words either.
    expect(component, 'the detail component must not hard-code task-kind labels').not.toMatch(/'压测任务'|「压测」/)
  })
})
