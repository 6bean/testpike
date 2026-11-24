/**
 * Cross-project conversation boundary contract (BE batch ,
 * design option A §two.4):
 * - the conversation body survives a page switch: switching the header
 * the next first question starts clean in the new segment;
 * - when the conversation belongs to another project than the page the
 * conversation in the current segment;
 * - the owning segment is persisted next to the pinned session pointer and
 * cleared together with it (no stale owner behind a fresh thread);
 * - the hint sentences carry operation info only (W6: both locales, the
 * two project names as parameters — never internal governance wording).
 */
import { describe, expect, it } from 'vitest'
import panelRaw from '@/components/ChatPanel.vue?raw'
import { ownsOtherSlice } from '@/composables/useChatSessionOwnership'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('slice switch keeps a live conversation (option A: no auto swap)', () => {
  it('the project watch resets the thread only when nothing is open yet', () => {
    const guard = panelRaw.match(
      /watch\(\s*\(\) => ctx\.primaryId,[\s\S]{0,500}?\n\)/,
    )
    const block = guard ? guard[0] : ''
    expect(block, 'project watch block not found').not.toBe('')
    expect(block).toMatch(
      /if \(sessionId\.value == null \|\| turns\.value\.length === 0\) \{[\s\S]*resetThread\(\)[\s\S]*loadHistory\(\)/,
    )
  })
})

describe('owning-slice bookkeeping', () => {
  it('ownsOtherSlice is honest for null inputs (no slice, no boundary)', () => {
    expect(ownsOtherSlice(null, 3)).toBe(false)
    expect(ownsOtherSlice(2, null)).toBe(false)
    expect(ownsOtherSlice(2, 2)).toBe(false)
    expect(ownsOtherSlice(2, 3)).toBe(true)
  })

  it('the owner is stamped on every door that opens a thread', () => {
    // creation carries the server row, restore/open carry the segment they
    // were read from (the session pointer is per user × segment)
    expect(panelRaw).toMatch(/sessionId\.value = session\.id\n\s*sessionProjectId\.value = session\.projectId/)
    expect(panelRaw).toMatch(/sessionId\.value = stored\n[\s\S]{0,240}sessionProjectId\.value = ctx\.primaryId/)
    expect(panelRaw).toMatch(/sessionId\.value = id\n\s*sessionProjectId\.value = ctx\.primaryId/)
  })

  it('the thread reset drops the owner together with the session (no stale owner)', () => {
    expect(panelRaw).toMatch(/pendingTurnId\.value = null\n\s*sessionProjectId\.value = null\n\s*sessionId\.value = null/)
  })
})

describe('boundary UI wiring (named + one-click fresh conversation)', () => {
  it('the boundary band renders with a stable data-test anchor', () => {
    expect(panelRaw).toMatch(/data-test="chat-project-boundary"/)
    expect(panelRaw).toMatch(/data-test="chat-project-boundary-new"/)
    expect(panelRaw).toMatch(/layout\.chatDock\.projectBoundaryHint/)
    expect(panelRaw).toMatch(/layout\.chatDock\.projectBoundaryNew/)
  })

  it('the one click starts a fresh conversation in the current slice', () => {
    // the button handler goes through the one newConversation owner path
    expect(panelRaw).toMatch(
      /function startConversationInCurrentSlice\(\)[\s\S]{0,320}resetThread\(\)/,
    )
  })

  it('both locales carry the two sentences with the two project names', () => {
    for (const raw of [zhRaw, enRaw]) {
      for (const key of ['projectBoundaryHint', 'projectBoundaryNew']) {
        const line = raw.split('\n').find((l) => l.trim().startsWith(`${key}:`))
        expect(line, `${key} missing in one locale`).toBeTruthy()
        expect(line).toContain('{owner}')
        expect(line).toContain('{current}')
      }
    }
  })
})
