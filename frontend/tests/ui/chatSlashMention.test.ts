/**
 * knife 4a — slash commands and @-object mentions (client contract).
 *
 * Three red lines this file pins down:
 * 1. word tables are locale single-source: the command list and every display
 * string come from `layout.chatDock.slash.*` / `layout.chatDock.mention.*`
 * — the composables ship no hard-coded Chinese (mechanical CJK check on the
 * two source files, per the AO word-table adjudication);
 * 2. candidate sources are EXISTING endpoints only (cases/defects via
 * /search, batches via /batches, docs via /knowledge/docs) — a mention
 * must never open a second backend door (TC-AO-05 posture: reuse, not rebuild);
 * 3. the chip is the single truth of a reference: the send payload is derived
 * from the chip list (kind/id/title), typed "@text" without a chip is
 * plain text, and chips dedupe by kind+id.
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const globalSearch = vi.fn()
const listBatches = vi.fn()
const listKnowledgeDocs = vi.fn()

vi.mock('../../src/api/search', () => ({
  globalSearch: (kw: string) => globalSearch(kw),
}))
vi.mock('../../src/api/batch', () => ({
  listBatches: (params: { keyword?: string }) => listBatches(params),
}))
vi.mock('../../src/api/knowledge', () => ({
  listKnowledgeDocs: (q: { keyword?: string }) => listKnowledgeDocs(q),
}))

import {
  SLASH_COMMAND_KEYS,
  detectTrigger,
  slashCommandKeys,
} from '../../src/composables/chatSlashCommands'
import {
  assembleMentionCandidates,
  dedupeRefs,
  toRefPayload,
} from '../../src/composables/chatObjectCandidates'

describe('slash command word table (E4): locale single source', () => {
  it('exposes stable keys — labels resolve through locale entries, not code', () => {
    const keys = slashCommandKeys()
    expect(keys.length).toBeGreaterThan(0)
    expect(keys).toEqual(SLASH_COMMAND_KEYS)
    // Every key must have a locale entry on BOTH tracks (i18n two-track rule).
    for (const track of ['zh-CN', 'en-US']) {
      const src = readFileSync(resolve(__dirname, `../../src/utils/locales/${track}.ts`), 'utf8')
      for (const k of keys) {
        expect(src, `${track} must carry layout.chatDock.slash.${k}`).toContain(`${k}:`)
      }
    }
  })

  it('composables contain no hard-coded Chinese (zero-CJK mechanical gate)', () => {
    for (const f of ['chatSlashCommands.ts', 'chatObjectCandidates.ts']) {
      const src = readFileSync(resolve(__dirname, `../../src/composables/${f}`), 'utf8')
      // comments are excluded: only executable string literals must stay CJK-free
      const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
      const strings = code.match(/'[^']*'|`[^`]*`|"[^"]*"/g) ?? []
      const dirty = strings.filter((s) => /[\u4e00-\u9fff]/.test(s))
      expect(dirty, `${f} carries CJK literals: ${dirty.join(' | ')}`).toEqual([])
    }
  })
})

describe('trigger detection (one input, two popups)', () => {
  it('slash trigger at line start opens the command list with the filter token', () => {
    expect(detectTrigger('/su', 3)).toMatchObject({ kind: 'slash', token: 'su' })
    expect(detectTrigger('/su ', 4)).toMatchObject({ kind: 'slash', token: 'su ' })
  })
  it('mention trigger after whitespace opens the object list', () => {
    expect(detectTrigger('请查 @def', 7)).toMatchObject({ kind: 'mention', token: 'def' })
    expect(detectTrigger('@', 1)).toMatchObject({ kind: 'mention', token: '' })
  })
  it('mid-word punctuation never fires a trigger (email/path safety)', () => {
    expect(detectTrigger('a@b.com', 7)).toMatchObject({ kind: null, token: '' })
    expect(detectTrigger('path/x/y', 8)).toMatchObject({ kind: null, token: '' })
  })
  it('no trigger when the caret sits before the marker', () => {
    expect(detectTrigger('@abc', 0)).toMatchObject({ kind: null, token: '' })
  })
})

describe('@-object candidates (E3): existing endpoints only', () => {
  beforeEach(() => {
    globalSearch.mockReset()
    listBatches.mockReset()
    listKnowledgeDocs.mockReset()
    globalSearch.mockResolvedValue({
      cases: [{ id: 77, projectId: 1, title: '登录失败校验', subtitle: null }],
      requirements: [],
      defects: [{ id: 9, projectId: 1, title: '导出乱码', subtitle: null }],
      projects: [],
    })
    listBatches.mockResolvedValue({ list: [{ id: 5, name: '夜间回归批' }], total: 1 })
    listKnowledgeDocs.mockResolvedValue({ list: [{ id: 3, title: '压测手册' }], total: 1 })
  })

  it('assembles case+defect from /search, batch from /batches, doc from /knowledge/docs', async () => {
    const items = await assembleMentionCandidates('log', 1)
    expect(globalSearch).toHaveBeenCalledWith('log')
    expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1, keyword: 'log' }))
    expect(listKnowledgeDocs).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1 }))
    expect(items.map((i) => i.kind).sort()).toEqual(['batch', 'case', 'defect', 'doc'])
    expect(items.find((i) => i.kind === 'case')).toMatchObject({ id: 77, title: '登录失败校验' })
  })

  it('an endpoint being down degrades honestly (other groups still land)', async () => {
    listBatches.mockRejectedValue(new Error('batch door down'))
    const items = await assembleMentionCandidates('log', 1)
    expect(items.map((i) => i.kind).sort()).toEqual(['case', 'defect', 'doc'])
  })

  it('empty keyword still returns the visible-domain heads (≤10 per group)', async () => {
    const items = await assembleMentionCandidates('', 1)
    expect(listBatches).toHaveBeenCalledWith(expect.objectContaining({ projectId: 1 }))
    expect(items.length).toBeGreaterThan(0)
  })
})

describe('chip is the single truth of the reference', () => {
  it('payload shape is {kind,id,title} — typed text without a chip is never parsed', () => {
    expect(toRefPayload({ kind: 'case', id: 77, title: '登录失败校验' })).toEqual({
      kind: 'case', id: 77, title: '登录失败校验',
    })
  })
  it('chips dedupe by kind+id (same object twice = one reference)', () => {
    const a = { kind: 'case' as const, id: 1, title: 'A' }
    const b = { kind: 'case' as const, id: 1, title: 'A(重命名)' }
    const c = { kind: 'doc' as const, id: 1, title: '同名不同类' }
    expect(dedupeRefs([a, b, c])).toEqual([b, c])
  })
})
