/**
 * Conclusion-level traceability contract (behaviour-kernel round-2 item 3,
 * RED first): the cite frame carries the knowledge chunkId end to end — the
 * reducer folds it onto the source card, one shared route builder aims the
 * /knowledge door at the paragraph, and the knowledge page hosts the anchor
 * (expand + scroll + flash). All three anchor families (citation, extract
 * evidence, execution step) must reuse this single door — no per-domain
 * hand-rolled jump query (R40).
 */
import { describe, expect, it } from 'vitest'
import { applyFrameToTurn, createAssistantTurn } from '@/composables/chatDockReducer'
import { knowledgeCitationRoute } from '@/composables/sourceAnchorJump'
import chatPanelRaw from '@/components/ChatPanel.vue?raw'
import knowledgeViewRaw from '@/views/knowledge/KnowledgeDocListView.vue?raw'

/** Wire shape of one cite frame meta side-channel (citeMeta in ChatRunOrchestrator). */
function citeMeta(over: Record<string, unknown> = {}): string {
  return JSON.stringify({
    index: 1, kind: 'knowledge', refId: 99, domainCode: null,
    title: '压测口径说明', excerpt: '并发档位含义', score: 0.8, revision: null, ...over,
  })
}

describe('cite frame chunkId passthrough (reducer fold)', () => {
  it('folds the paragraph anchor onto the source card', () => {
    const turn = createAssistantTurn(1)
    applyFrameToTurn(turn, {
      event: 'cite', data: '[C1] 压测口径说明', meta: citeMeta({ chunkId: '77' }),
    } as never)
    expect(turn.citations.length).toBe(1)
    expect(turn.citations[0].chunkId).toBe('77')
  })
  it('keeps an honest null when the server omits it (help rows, old wire)', () => {
    const turn = createAssistantTurn(2)
    applyFrameToTurn(turn, {
      event: 'cite', data: '[C1] x', meta: citeMeta(),
    } as never)
    expect(turn.citations[0].chunkId).toBeNull()
  })
})

describe('single-source knowledge door (shared by the three anchor families)', () => {
  it('aims the paragraph when a chunkId rides along', () => {
    expect(knowledgeCitationRoute(99, '77')).toEqual({
      path: '/knowledge', query: { docId: '99', chunkId: '77' },
    })
  })
  it('falls back to the document door with no empty chunkId key', () => {
    expect(knowledgeCitationRoute(99, null)).toEqual({
      path: '/knowledge', query: { docId: '99' },
    })
  })
  it('ChatPanel consumes the shared door instead of hand-rolling the query', () => {
    expect(chatPanelRaw).toContain('knowledgeCitationRoute')
  })
  it('knowledge page reads the chunkId pointer and hosts the reveal anchor', () => {
    expect(knowledgeViewRaw).toContain('route.query.chunkId')
    expect(knowledgeViewRaw).toContain('kb-chunk-reveal')
  })
})
