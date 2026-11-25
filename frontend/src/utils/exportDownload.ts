
import { BizError, getToken, ensureFreshAccessToken, renewAccessTokenOnce } from '@/api/request'


export interface SyncExportResult {
  content?: string
  fileName?: string
}


export function downloadBase64Xlsx(base64: string, fileName: string): void {
  const bytes = atob(base64)
  const arr = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i)
  const blob = new Blob([arr], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  triggerBlobDownload(blob, fileName || 'export.xlsx')
}


export async function fetchAndDownloadBlob(url: string, fallbackFileName: string): Promise<void> {
  // This door is a bare fetch outside the axios interceptor road: renew
  // through the shared non-axios doors before knocking. A transient renewal
  // verdict throws before any request leaves, so a stale token never hits
  // the wire (same contract as the SSE doors, single source).
  const doorHeaders = (token: string) => ({ Authorization: `Bearer ${token}` })
  let resp = await fetch(url, { headers: doorHeaders(await ensureFreshAccessToken()) })
  if (resp.status === 401 && (await renewAccessTokenOnce()) === true) {
    // Safety net, reopen exactly once with the rotated token — never a loop.
    resp = await fetch(url, { headers: doorHeaders(getToken()) })
  }
  if (!resp.ok) {
    const text = await resp.text()
    try {
      const j = JSON.parse(text)
      throw new BizError(j.code || String(resp.status), j.msg || '导出失败')
    } catch (e) {
      if (e instanceof BizError) throw e
      throw new BizError(String(resp.status), '导出失败')
    }
  }
  const blob = await resp.blob()
  triggerBlobDownload(
    blob,
    parseContentDispositionFileName(resp.headers.get('content-disposition')) || fallbackFileName,
  )
}


export function parseContentDispositionFileName(header: string | null): string {
  if (!header) return ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (star?.[1]) {
    try { return decodeURIComponent(star[1]) } catch { return star[1] }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain?.[1] ?? ''
}

/**
 * Text payload → browser download (chat Markdown export rides this; the
 * server hands the content inside a JSON body, so no byte-stream fetch is
 * needed). Shares the anchor tail step with the other download shapes.
 */
export function downloadTextFile(text: string, fileName: string, mime = 'text/plain;charset=utf-8'): void {
  triggerBlobDownload(new Blob([text], { type: mime }), fileName)
}


function triggerBlobDownload(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
