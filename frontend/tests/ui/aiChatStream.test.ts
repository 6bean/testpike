/**
 * stage 2 — chat stream client contract (src/api/aiChat.ts).
 *
 * The dock talks to the single POST-SSE door (fresh run = body prompt,
 * reconnect = body resumeCursor; Last-Event-ID travels in the body because the
 * server trusts it there). Pinned behaviors, all against a fake fetch (L2
 * isolation of the transport only — the live chain is proven by L3/E2E):
 * - request shape: /api/v1 prefix, Bearer JWT from the token store, JSON body;
 * - frames are delivered in arrival order with their ledger seq;
 * - the cursor advances with every seq-carrying frame and a resume sends the
 * last acknowledged cursor (never opens a second run behind the user);
 * - heartbeats never reach the consumer;
 * - a non-OK response surfaces the backend's human-readable msg;
 * - close aborts the in-flight fetch.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { openChatStream, type ChatStreamFailure } from '@/api/aiChat'
import { setToken } from '@/api/request'

/** In-memory localStorage for the node vitest environment (token store seam). */
function installMemoryStorage() {
  const map = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
  })
}

/** The fetch spy's recorded call arguments, narrowed once for all spectra. */
interface FetchCall {
  url: string
  method: string
  headers: Record<string, string>
  body: string
  signal?: AbortSignal
}

function callsOf(spy: ReturnType<typeof vi.fn>): FetchCall[] {
  return spy.mock.calls.map((c) => {
    const [url, opts] = c as [string, RequestInit]
    return {
      url,
      method: String(opts.method),
      headers: opts.headers as Record<string, string>,
      body: String(opts.body),
      signal: opts.signal ?? undefined,
    }
  })
}

/** Build a ReadableStream<Uint8Array> from fixed text chunks (what fetch would hand back). */
function byteStream(chunks: string[]) {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder()
      for (const c of chunks) controller.enqueue(enc.encode(c))
      controller.close()
    },
  })
}

function fakeFetch(chunks: string[], init: { status?: number; body?: string } = {}) {
  const spy = vi.fn(async (_url: string, _opts?: RequestInit) => ({
    ok: (init.status ?? 200) >= 200 && (init.status ?? 200) < 300,
    status: init.status ?? 200,
    headers: new Headers({ 'content-type': 'text/event-stream' }),
    body: byteStream(chunks),
    text: async () => init.body ?? '',
  }))
  vi.stubGlobal('fetch', spy)
  return spy
}

beforeEach(() => {
  vi.unstubAllGlobals()
  installMemoryStorage()
  setToken('jwt-token-for-test')
})

describe('chat stream client', () => {
  it('posts the prompt to the session stream door with the bearer token', async () => {
    const spy = fakeFetch(['event: done\nid: 1\ndata: {}\n\n'])
    const frames: { event: string; id: number | null }[] = []
    const handle = openChatStream(42, { prompt: 'hello' }, (f) => frames.push(f))
    await handle.settled
    expect(spy).toHaveBeenCalledTimes(1)
    const [call] = callsOf(spy)
    expect(call.url).toBe('/api/v1/ai/chat/sessions/42/stream')
    expect(call.method).toBe('POST')
    expect(call.headers.Authorization).toBe('Bearer jwt-token-for-test')
    expect(JSON.parse(call.body)).toEqual({ prompt: 'hello' })
    expect(frames).toEqual([{ event: 'done', id: 1, data: '{}' }])
  })

  it('advances the cursor per seq and resumes with the last acknowledged cursor', async () => {
    fakeFetch(['event: state\nid: 3\ndata: a\n\nevent: delta\nid: 5\ndata: b\n\n'])
    const handle = openChatStream(7, { prompt: 'q' }, () => {})
    await handle.settled
    expect(handle.cursor()).toBe(5)
    const spy2 = fakeFetch(['event: done\nid: 6\ndata: c\n\n'])
    const resume = openChatStream(7, { resumeCursor: handle.cursor() }, () => {})
    await resume.settled
    const [call] = callsOf(spy2)
    expect(JSON.parse(call.body)).toEqual({ resumeCursor: 5 })
  })

  it('never delivers heartbeat comments to the consumer', async () => {
    fakeFetch([':hb\n\n', 'event: trace\nid: 2\ndata: t\n\n', ':hb\n\n'])
    const frames: string[] = []
    const handle = openChatStream(1, { prompt: 'x' }, (f) => frames.push(f.event))
    await handle.settled
    expect(frames).toEqual(['trace'])
  })

  it('surfaces the human-readable msg when the door refuses (non-OK JSON)', async () => {
    fakeFetch([], { status: 403, body: JSON.stringify({ code: '403', msg: '该对话会话不属于当前用户，已拒绝访问' }) })
    const errors: ChatStreamFailure[] = []
    const handle = openChatStream(1, { prompt: 'x' }, () => {}, (f) => errors.push(f))
    await handle.settled
    expect(errors).toEqual([{ kind: 'refused', serverMsg: '该对话会话不属于当前用户，已拒绝访问' }])
  })

  it('close() aborts the in-flight request', async () => {
    let capturedSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn(async (_url: string, opts?: RequestInit) => {
      capturedSignal = opts?.signal ?? undefined
      return {
        ok: true,
        status: 200,
        headers: new Headers(),
        body: new ReadableStream({ start() { /* never closes: a hung stream */ } }),
        text: async () => '',
      }
    }))
    const handle = openChatStream(1, { prompt: 'x' }, () => {})
    handle.close()
    await handle.settled
    expect(capturedSignal?.aborted).toBe(true)
  })
})
