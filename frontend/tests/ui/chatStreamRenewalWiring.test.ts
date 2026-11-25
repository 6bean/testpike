/**
 * Chat SSE door token renewal wiring (defect ticket: the chat stream opens
 * with a bare fetch that never goes through the non-axios renewal doors, so a
 * session older than the access TTL answers the send itself with a 401 and
 * the bubble degrades to a refusal the user cannot act on).
 *
 * Contract mirrors the tail SSE road (tailSseRenewalWiring.test.ts) against
 * the SAME single-source helpers — no second refresh implementation (R40):
 *   1. await ensureFreshAccessToken BEFORE opening the door and send the
 *      returned token in the Authorization header,
 *   2. a transient renewal verdict keeps the door SHUT (stale token never on
 *      the wire) and surfaces the honest network failure for the resend door,
 *   3. on a 401 response, renewAccessTokenOnce and — only on success — reopen
 *      EXACTLY ONCE with the fresh header.
 *
 * The request module is mocked here to observe CALL SHAPE only; the refresh
 * semantics themselves are covered against the real implementation in
 * tailSseTokenRenewal.test.ts.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const mocks = vi.hoisted(() => ({
  getToken: vi.fn(() => 'OLD'),
  ensureFreshAccessToken: vi.fn(async () => 'FRESH'),
  renewAccessTokenOnce: vi.fn(async () => false as boolean | 'transient'),
}))
vi.mock('@/api/request', () => ({
  getToken: mocks.getToken,
  ensureFreshAccessToken: mocks.ensureFreshAccessToken,
  renewAccessTokenOnce: mocks.renewAccessTokenOnce,
}))

const FRAME = 'id: 1\nevent: delta\ndata: hi\n\n'

function sseResponse(frames: string): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      c.enqueue(new TextEncoder().encode(frames))
      c.close()
    },
  })
  return { ok: true, status: 200, body: stream } as unknown as Response
}

function statusResponse(status: number): Response {
  return {
    ok: false,
    status,
    body: null,
    text: async () => '',
  } as unknown as Response
}

describe('openChatStream 续期接线（事前续 + transient 不敲门 + 401 恰一次重开）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.getToken.mockReturnValue('OLD')
    mocks.ensureFreshAccessToken.mockResolvedValue('FRESH')
    mocks.renewAccessTokenOnce.mockResolvedValue(false)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('开窗前先 await ensureFreshAccessToken，Authorization 头用续期后的 token', async () => {
    const fetchMock = vi.fn(async () => sseResponse(FRAME))
    vi.stubGlobal('fetch', fetchMock)
    const { openChatStream } = await import('@/api/aiChat')
    const errors: unknown[] = []
    const handle = openChatStream(1, { content: 'q' } as never, () => {}, f => errors.push(f))
    await handle.settled
    expect(mocks.ensureFreshAccessToken).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer FRESH')
    expect(errors).toEqual([])
  })

  it('续期判定 transient：门不开（陈旧 token 不上线），失败按 network 形态落给重发门', async () => {
    const fetchMock = vi.fn(async () => sseResponse(FRAME))
    vi.stubGlobal('fetch', fetchMock)
    mocks.ensureFreshAccessToken.mockRejectedValue(new Error('transient'))
    const { openChatStream } = await import('@/api/aiChat')
    const errors: { kind?: string }[] = []
    const handle = openChatStream(2, { content: 'q' } as never, () => {}, f => errors.push(f))
    await handle.settled
    expect(fetchMock).not.toHaveBeenCalled()
    expect(errors.length).toBe(1)
    expect(errors[0].kind).toBe('network')
  })

  it('401：renewAccessTokenOnce 成功才恰一次重开并带新头；失败不循环', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(statusResponse(401))
      .mockResolvedValueOnce(sseResponse(FRAME))
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue(true)
    mocks.getToken.mockReturnValue('FRESH2')
    const { openChatStream } = await import('@/api/aiChat')
    const errors: unknown[] = []
    const frames: unknown[] = []
    const handle = openChatStream(3, { content: 'q' } as never, f => frames.push(f), f => errors.push(f))
    await handle.settled
    expect(mocks.renewAccessTokenOnce).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const retryInit = fetchMock.mock.calls[1][1] as RequestInit
    expect((retryInit.headers as Record<string, string>).Authorization).toBe('Bearer FRESH2')
    expect(errors).toEqual([])
    expect(frames.length).toBeGreaterThan(0)
  })

  it('401 且续期失败：不重开，按现有拒收形态收尾（恰一次敲门）', async () => {
    const fetchMock = vi.fn(async () => statusResponse(401))
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue(false)
    const { openChatStream } = await import('@/api/aiChat')
    const errors: { kind?: string }[] = []
    const handle = openChatStream(4, { content: 'q' } as never, () => {}, f => errors.push(f))
    await handle.settled
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(errors.length).toBe(1)
    expect(errors[0].kind).toBe('refused')
  })
})
