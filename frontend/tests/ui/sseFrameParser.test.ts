/**
 * stage 2 — SSE frame decoder contract for the chat dock.
 *
 * The dialog stream crosses the wire as plain SSE blocks (`event:` + `id:` +
 * one or more `data:` lines, blank-line terminated) with transport-only
 * comment heartbeats (`:hb`). The decoder must survive the three shapes that
 * actually break naive splits: chunks cut mid-line, multi-line data payloads
 * (answer deltas legitimately contain newlines), and comment lines that must
 * never surface as frames (a replayed heartbeat would shift reconnect
 * cursors — the same reason the ledger refuses to persist them).
 */
import { describe, expect, it } from 'vitest'
import { createSseFrameDecoder, type SseFrame } from '@/api/sseFrameParser'

function collect(chunks: string[]): SseFrame[] {
  const decoder = createSseFrameDecoder()
  const out: SseFrame[] = []
  for (const c of chunks) out.push(...decoder.push(c))
  return out
}

describe('sse frame decoder', () => {
  it('carries a :meta comment of the same block onto the frame (T428 cite cards)', () => {
    const frames = collect([
      'event: cite\nid: 5\n:meta {"index":1,"kind":"help"}\ndata: [C1] 权限说明\n\n',
    ])
    expect(frames).toHaveLength(1)
    expect(frames[0].data).toBe('[C1] 权限说明')
    expect(frames[0].meta).toBe('{"index":1,"kind":"help"}')
  })

  it('omits meta entirely when the block has none and a plain :hb stays transport-only', () => {
    const frames = collect([':hb\n', 'event: delta\nid: 6\ndata: hi\n\n'])
    expect(frames).toEqual([{ event: 'delta', id: 6, data: 'hi' }])
    expect(frames[0].meta).toBeUndefined()
  })

  it('decodes a complete single frame with event name, id and data', () => {
    const frames = collect(['event: delta\nid: 3\ndata: {"text":"hi"}\n\n'])
    expect(frames).toEqual([{ event: 'delta', id: 3, data: '{"text":"hi"}' }])
  })

  it('joins multi-line data payloads with newlines (delta text carries line breaks)', () => {
    const frames = collect(['event: delta\nid: 4\ndata: line1\ndata: line2\n\n'])
    expect(frames).toHaveLength(1)
    expect(frames[0].data).toBe('line1\nline2')
    expect(frames[0].id).toBe(4)
  })

  it('survives a chunk split mid-line and mid-block', () => {
    const frames = collect(['event: re', 'ason\nid: 7\ndata: thi', 'nking\n', '\n'])
    expect(frames).toEqual([{ event: 'reason', id: 7, data: 'thinking' }])
  })

  it('emits every frame from one chunk holding several blocks', () => {
    const frames = collect(['event: state\nid: 1\ndata: a\n\nevent: done\nid: 2\ndata: b\n\n'])
    expect(frames.map((f) => [f.event, f.id])).toEqual([['state', 1], ['done', 2]])
  })

  it('never surfaces heartbeat comment lines as frames', () => {
    const frames = collect([':hb\n\n', 'event: trace\nid: 5\ndata: t\n\n'])
    expect(frames).toEqual([{ event: 'trace', id: 5, data: 't' }])
  })

  it('keeps an unterminated tail buffered until its blank line lands', () => {
    const decoder = createSseFrameDecoder()
    expect(decoder.push('event: delta\ndata: half\n')).toEqual([])
    expect(decoder.push('\n')).toEqual([{ event: 'delta', id: null, data: 'half' }])
  })

  it('defaults the event name to message and the id to null when absent', () => {
    const frames = collect(['data: plain\n\n'])
    expect(frames).toEqual([{ event: 'message', id: null, data: 'plain' }])
  })

  it('treats a bare "data:" field as an empty payload line', () => {
    const frames = collect(['event: error\ndata:\n\n'])
    expect(frames[0].data).toBe('')
  })
})
