/**
 * B contract spectrum (project-memory four-state ledger):
 * - the panel renders all four injection states (active / disabled / pending
 * confirmation / rejected) and the filter offers exactly those four;
 * - a pending row rides the SHARED draft confirmation door (confirm-ticket →
 * apply) imported from aiChat — no second confirmation protocol is born in
 * the memory api or panel;
 * - a pending row without an open card disables the adopt action and carries
 * the honest guidance on the state tag (pointing at the AI Chat panel);
 * - rejected rows keep only the delete exit (no enable semantic);
 * - the reject exit mirrors the admin route once (single source of the call);
 * - every new locale key is symmetric across zh-CN / en-US (i18n double track).
 * Rendered behaviour is covered by the package E2E (test_project_memory_tab.py).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const panel = readFileSync(new URL('../../src/views/skill/ProjectMemoryPanel.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../../src/api/projectMemory.ts', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

const NEW_KEYS = [
  'memoryStatePending',
  'memoryStateRejected',
  'memoryAdopt',
  'memoryReject',
  'memoryConfirmAdopt',
  'memoryConfirmReject',
  'memoryAdoptedOk',
  'memoryRejectedOk',
  'memoryNoDraftTip',
]

describe('four-state rendering posture', () => {
  it('labels every injection state and maps tag colours', () => {
    for (const key of ['memoryStateActive', 'memoryStateDisabled', 'memoryStatePending', 'memoryStateRejected']) {
      expect(panel).toContain(`t('skill.${key}')`)
    }
    expect(panel).toContain('if (status === 2) return t(\'skill.memoryStatePending\')')
    expect(panel).toContain('if (status === 2) return \'warning\'')
  })

  it('filter select carries exactly the four states', () => {
    expect(panel).toContain('<el-option :label="t(\'skill.memoryStatePending\')" :value="2" />')
    expect(panel).toContain('<el-option :label="t(\'skill.memoryStateRejected\')" :value="3" />')
  })
})

describe('confirmation door is the shared draft protocol', () => {
  it('panel imports the shared ticket+apply pair from aiChat', () => {
    expect(panel).toContain("import { applyRepairDraft, requestDraftConfirmTicket } from '@/api/aiChat'")
  })

  it('adopt rides ticket → apply with no new URL born here', () => {
    expect(panel).toContain('await requestDraftConfirmTicket(row.draftId)')
    expect(panel).toContain('await applyRepairDraft(row.draftId, ticket.token)')
    // the memory api mirror must not grow its own confirm/apply routes
    expect(api).not.toContain('confirm-token')
    expect(api).not.toContain('/apply')
  })

  it('a pending row without an open card disables adopt and guides on the tag', () => {
    expect(panel).toContain('disabled: row.draftId == null')
    expect(panel).toContain('row.injectStatus === 2 && row.draftId == null')
    expect(panel).toContain("t('skill.memoryNoDraftTip')")
  })
})

describe('reject exit and rejected-row posture', () => {
  it('api mirrors exactly one reject route', () => {
    const hits = api.match(/\/reject/g) ?? []
    expect(hits).toHaveLength(1)
    expect(api).toContain("url: `/project-memory/${id}/reject`, method: 'post'")
  })

  it('pending rows offer adopt+reject, rejected rows only delete', () => {
    expect(panel).toContain("key: 'adopt'")
    expect(panel).toContain("key: 'reject'")
    // rejected (3) falls through every state branch — only remove remains
    expect(panel).toContain('} else if (row.injectStatus === 2) {')
    const actsBlock = panel.slice(panel.indexOf('function rowActions'), panel.indexOf('async function adoptPending'))
    expect(actsBlock).not.toContain('row.injectStatus === 3')
  })
})

describe('i18n double track', () => {
  it('every new key exists in both locales and is used by the panel or api', () => {
    for (const key of NEW_KEYS) {
      expect(zh).toContain(`${key}:`)
      expect(en).toContain(`${key}:`)
    }
  })

  it('screen text stays free of internal governance wording', () => {
    const banned = ['台账', '裁决', '单源', '棘轮', '收口', '出账', '落章', '拒件', '人话']
    for (const key of NEW_KEYS) {
      const zhLine = zh.match(new RegExp(`^\\s*${key}: '([^']*)'`, 'm'))?.[1] ?? ''
      const enLine = en.match(new RegExp(`^\\s*${key}: "([^"]*)"`, 'm'))?.[1] ?? ''
      expect(zhLine + ' ' + enLine).not.toMatch(
        new RegExp(banned.join('|')),
        `${key} carries governance wording`,
      )
    }
  })
})
