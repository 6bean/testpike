/**
 * Blob export door token renewal wiring (defect ticket: export downloads open
 * with a bare fetch using getToken() directly, so an expired access token
 * answers the click with a plain export-failed toast instead of renewing once).
 *
 * Same single-source helpers as the other non-axios doors — this spec judges
 * CALL SHAPE only against a mocked request module; the refresh semantics
 * themselves are covered against the real implementation in
 * tailSseTokenRenewal.test.ts.
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const mocks = vi.hoisted(() => ({
  getToken: vi.fn(() => 'OLD'),
  ensureFreshAccessToken: vi.fn(async () => 'FRESH'),
  renewAccessTokenOnce: vi.fn(async () => false as boolean | 'transient'),
}))
vi.mock('@/api/request', () => ({
  BizError: class extends Error {
    code: string
    constructor(code: string, message: string) {
      super(message)
      this.code = code
    }
  },
  getToken: mocks.getToken,
  ensureFreshAccessToken: mocks.ensureFreshAccessToken,
  renewAccessTokenOnce: mocks.renewAccessTokenOnce,
}))

function blobResponse(): Response {
  return {
    ok: true,
    status: 200,
    blob: async () => new Blob(['x']),
    headers: new Headers(),
  } as unknown as Response
}

function unauthorizedResponse(): Response {
  return {
    ok: false,
    status: 401,
    text: async () => JSON.stringify({ code: '401', msg: '' }),
    headers: new Headers(),
  } as unknown as Response
}

describe('fetchAndDownloadBlob 续期接线（事前续 + 401 恰一次重试 + transient 不敲门）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.getToken.mockReturnValue('OLD')
    mocks.ensureFreshAccessToken.mockResolvedValue('FRESH')
    mocks.renewAccessTokenOnce.mockResolvedValue(false)
    // Minimal door for the anchor-click download (node env, no jsdom dep):
    URL.createObjectURL = vi.fn(() => 'blob:stub')
    URL.revokeObjectURL = vi.fn()
    const anchor = { click: vi.fn(), remove: vi.fn(), style: {}, setAttribute: vi.fn() }
    vi.stubGlobal('document', { createElement: () => anchor, body: { appendChild: vi.fn() } })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('下载前先 await ensureFreshAccessToken，Authorization 头用续期后的 token', async () => {
    const fetchMock = vi.fn(async () => blobResponse())
    vi.stubGlobal('fetch', fetchMock)
    const { fetchAndDownloadBlob } = await import('@/utils/exportDownload')
    await fetchAndDownloadBlob('/api/v1/cases/export', 'x.xlsx')
    expect(mocks.ensureFreshAccessToken).toHaveBeenCalledTimes(1)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer FRESH')
  })

  it('401：续期成功恰一次重试并带新头，下载照常完成', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(unauthorizedResponse()).mockResolvedValueOnce(blobResponse())
    vi.stubGlobal('fetch', fetchMock)
    mocks.renewAccessTokenOnce.mockResolvedValue(true)
    mocks.getToken.mockReturnValue('FRESH2')
    const { fetchAndDownloadBlob } = await import('@/utils/exportDownload')
    await expect(fetchAndDownloadBlob('/api/v1/cases/export', 'x.xlsx')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const retryInit = fetchMock.mock.calls[1][1] as RequestInit
    expect((retryInit.headers as Record<string, string>).Authorization).toBe('Bearer FRESH2')
  })

  it('401 且续期失败：维持现有 BizError 拒收形态，不循环重试', async () => {
    const fetchMock = vi.fn(async () => unauthorizedResponse())
    vi.stubGlobal('fetch', fetchMock)
    const { fetchAndDownloadBlob } = await import('@/utils/exportDownload')
    await expect(fetchAndDownloadBlob('/api/v1/cases/export', 'x.xlsx')).rejects.toBeInstanceOf(Error)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('续期判定 transient：陈旧 token 不上线，零外发', async () => {
    const fetchMock = vi.fn(async () => blobResponse())
    vi.stubGlobal('fetch', fetchMock)
    mocks.ensureFreshAccessToken.mockRejectedValue(new Error('transient'))
    const { fetchAndDownloadBlob } = await import('@/utils/exportDownload')
    await expect(fetchAndDownloadBlob('/api/v1/cases/export', 'x.xlsx')).rejects.toBeInstanceOf(Error)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
