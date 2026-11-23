/**
 * Door call-shape half of the transient-vs-dead renewal split (defect ticket
 * ; semantics covered against the real request.ts in the sibling
 * tailSseTransientRenewal.test.ts): a 'transient' renewal verdict must NEVER
 * let the SSE door open with the stale token — a churn burst then costs zero
 * 401 on the wire and the cursor-window poll cadence retries by itself.
 *
 * The request module is mocked here to observe CALL SHAPE only (vitest 5
 * requires module mocks at file top level — hence this file split).
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const mocks = vi.hoisted(() => ({
  getToken: vi.fn(() => 'OLD'),
  request: vi.fn(async () => [] as unknown[]),
  ensureFreshAccessToken: vi.fn(async () => 'OLD'),
  renewAccessTokenOnce: vi.fn(async (): Promise<boolean | 'transient'> => false),
}))
vi.mock('@/api/request', () => ({
  getToken: mocks.getToken,
  request: mocks.request,
  ensureFreshAccessToken: mocks.ensureFreshAccessToken,
  renewAccessTokenOnce: mocks.renewAccessTokenOnce,
}))

function statusResponse(status: number): Response {
  return { ok: false, status, body: null } as unknown as Response
}

describe('openEventTail 接线（transient 禁开窗：零 401 上 wire + 轮询接管）', () => {
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

  it('事前续期判 transient：SSE 门一次都不开（旧 token 禁撞门），降级轮询按节拍重试', async () => {
    const fetchMock = vi.fn(async () => statusResponse(200))
    vi.stubGlobal('fetch', fetchMock)
    mocks.ensureFreshAccessToken.mockRejectedValue(
      Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK_CHANGED' }),
    )
    const { openTaskProcessEventTail, TAIL_POLL_INTERVAL_MS } = await import('@/api/taskEvents')
    const rows: unknown[] = []
    const handle = openTaskProcessEventTail(6156, 0, b => rows.push(...b))
    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(TAIL_POLL_INTERVAL_MS + 50)
    expect(mocks.request).toHaveBeenCalled()
    handle.close()
  })

  it('门上收 401 后续期判 transient：禁裸重开（fetch 恰一次），轮询接管', async () => {
    const fetchMock = vi.fn(async () => statusResponse(401))
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue('transient')
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
