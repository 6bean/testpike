/**
 * Layer 2 of the tail SSE renewal contract (see tailSseTokenRenewal.test.ts
 * for the helpers themselves): openEventTail's runStream must
 *   1. await ensureFreshAccessToken BEFORE opening the SSE door and send the
 *      returned token in the Authorization header (no 401 on the wire for a
 *      healthy session),
 *   2. on a 401 response, call renewAccessTokenOnce and — only on success —
 *      reopen EXACTLY ONCE with the fresh header (never a reopen loop),
 *   3. on refresh failure, degrade to the existing cursor-window poll without
 *      crashing or silently claiming completion.
 *
 * The request module is mocked here to observe CALL SHAPE only; the refresh
 * semantics are covered against the real implementation in the sibling spec.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const mocks = vi.hoisted(() => ({
  getToken: vi.fn(() => 'OLD'),
  request: vi.fn(async () => [] as unknown[]),
  ensureFreshAccessToken: vi.fn(async () => 'OLD'),
  renewAccessTokenOnce: vi.fn(async () => false),
}))
vi.mock('@/api/request', () => ({
  getToken: mocks.getToken,
  request: mocks.request,
  ensureFreshAccessToken: mocks.ensureFreshAccessToken,
  renewAccessTokenOnce: mocks.renewAccessTokenOnce,
}))

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
  return { ok: false, status, body: null } as unknown as Response
}

describe('openEventTail runStream 接线（事前续 + 401 恰一次重开 + 失败降级轮询）', () => {
  const FRAME = 'id: 7\nevent: step\ndata: 已完成 1/2\n\n'

  beforeEach(() => {
    vi.resetAllMocks()
    mocks.getToken.mockReturnValue('OLD')
    mocks.request.mockResolvedValue([])
    mocks.ensureFreshAccessToken.mockResolvedValue('OLD')
    mocks.renewAccessTokenOnce.mockResolvedValue(false)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('开窗前先 await ensureFreshAccessToken，Authorization 头用续期后的 token', async () => {
    const fetchMock = vi.fn(async () => sseResponse(FRAME))
    vi.stubGlobal('fetch', fetchMock)
    mocks.ensureFreshAccessToken.mockResolvedValue('FRESH')
    const { openTaskProcessEventTail } = await import('@/api/taskEvents')
    const rows: { cursor: number }[] = []
    const handle = openTaskProcessEventTail(6156, 0, b => rows.push(...b))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(mocks.ensureFreshAccessToken).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer FRESH')
    await vi.waitFor(() => expect(rows.map(r => r.cursor)).toEqual([7]))
    handle.close()
  })

  it('收 401 且续期成功：换新 header 重开恰一次，事件流恢复', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(statusResponse(401))
      .mockResolvedValueOnce(sseResponse(FRAME))
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue(true)
    mocks.getToken.mockReturnValue('NEW')
    const { openTaskProcessEventTail } = await import('@/api/taskEvents')
    const rows: { cursor: number }[] = []
    const handle = openTaskProcessEventTail(6156, 0, b => rows.push(...b))
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(mocks.renewAccessTokenOnce).toHaveBeenCalledTimes(1)
    const second = fetchMock.mock.calls[1][1] as RequestInit
    expect((second.headers as Record<string, string>).Authorization).toBe('Bearer NEW')
    await vi.waitFor(() => expect(rows.map(r => r.cursor)).toEqual([7]))
    handle.close()
  })

  it('收 401 且续期失败：不裸重开（fetch 恰一次），降级游标窗口轮询接管', async () => {
    const fetchMock = vi.fn(async () => statusResponse(401))
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue(false)
    const { openTaskProcessEventTail, TAIL_POLL_INTERVAL_MS } = await import('@/api/taskEvents')
    const rows: unknown[] = []
    const handle = openTaskProcessEventTail(6156, 9, b => rows.push(...b))
    await vi.waitFor(() => expect(mocks.renewAccessTokenOnce).toHaveBeenCalled())
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS + 50)
    expect(mocks.request).toHaveBeenCalled()
    handle.close()
  })
})
