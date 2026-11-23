/**
 * Minimal SSE block decoder for the chat dock stream .
 *
 * The dialog stream is a POST-SSE body (fetch + ReadableStream — EventSource
 * cannot carry the JWT or the request body), so the browser gives us raw text
 * chunks with no framing guarantees: a chunk may cut mid-line or mid-block.
 * This decoder turns the byte stream back into complete frames per the SSE
 * wire contract (event/id/data fields, blank line seals a block, `:` comment
 * lines are transport-only heartbeats that must never reach the ledger or the
 * UI). Kept as a pure, dependency-free module so the dock client and unit
 * spectra share one parsing point (no second copy in the component).
 */

/** One decoded SSE frame: event name, numeric id (session seq), joined data and optional meta. */
export interface SseFrame {
  /** SSE `event:` field; defaults to 'message' when absent. */
  event: string
  /** SSE `id:` field parsed as the ledger seq; null when the frame carries none. */
  id: number | null
  /** All `data:` lines joined with '\n' (answer deltas legitimately contain newlines). */
  data: string
  /**
 * Machine-readable side-channel carried by the `:meta ` comment of the same
 * block .
 * Legacy producers (heartbeats, non-cite frames) send none — the field stays
 * undefined and old clients never see it as content (SSE comments are
 * ignored by any spec-compliant parser, so this is wire-compatible).
 */
  meta?: string
}

/** Incremental decoder state: the tail of an unfinished chunk plus the open block. */
interface OpenBlock {
  event: string
  id: number | null
  dataLines: string[]
  metaLines: string[]
}

export interface SseFrameDecoder {
  /** Feed one text chunk; returns every frame whose blank line landed in it. */
  push(chunk: string): SseFrame[]
}

export function createSseFrameDecoder(): SseFrameDecoder {
  let tail = ''
  let block: OpenBlock = { event: 'message', id: null, dataLines: [], metaLines: [] }
  let blockHasField = false

  const sealBlock = (out: SseFrame[]) => {
    if (!blockHasField) {
      // A blank line with no accumulated fields = separator/padding, not a frame.
      return
    }
    const frame: SseFrame = { event: block.event, id: block.id, data: block.dataLines.join('\n') }
    if (block.metaLines.length > 0) {
      frame.meta = block.metaLines.join('\n')
    }
    out.push(frame)
    block = { event: 'message', id: null, dataLines: [], metaLines: [] }
    blockHasField = false
  }

  const applyLine = (line: string, out: SseFrame[]) => {
    if (line === '') {
      sealBlock(out)
      return
    }
    if (line.startsWith(':')) {
      // Comment line: transport-only by the SSE spec. Heartbeats (":hb") never
      // surface; the ":meta ..." side-channel is attached to the open block so
      // the cite-card fields ride with their frame without becoming content.
      const text = line.slice(1)
      if (text.startsWith('meta')) {
        const value = text.slice(4)
        block.metaLines.push(value.startsWith(' ') ? value.slice(1) : value)
      }
      return
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    blockHasField = true
    if (field === 'event') {
      block.event = value
    } else if (field === 'id') {
      const parsed = Number(value)
      block.id = Number.isFinite(parsed) ? parsed : null
    } else if (field === 'data') {
      block.dataLines.push(value)
    }
    // Other fields (retry etc.) are ignored: this stream pins resume in the body.
  }

  return {
    push(chunk: string): SseFrame[] {
      tail += chunk
      const out: SseFrame[] = []
      // Normalize CRLF/CR line endings before splitting (SSE allows all three).
      const text = tail.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
      const lines = text.split('\n')
      // The last element is either '' (text ended on a newline) or a partial
      // line that must wait for its remainder in the next chunk.
      tail = lines.pop() ?? ''
      for (const line of lines) {
        applyLine(line, out)
      }
      return out
    },
  }
}
