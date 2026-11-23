/**
 * segment B-12 (front-end consume face): the report head gains an export
 * door that downloads the same single-source report as pdf or html through
 * the shared authenticated blob-download helper.
 *
 * Contract asserted here:
 * 1. the api layer mirrors the admin door (GET /perf/runs/{id}/report-export
 * with projectId/format as query) and rides fetchAndDownloadBlob — no
 * second download implementation, no browser-side file-name assembly
 * (Content-Disposition is composed server-side, red line 6);
 * 2. the report view consumes it only through the api mirror (no raw URL
 * in the view) behind one "export report" door with pdf/html variants;
 * 3. the door is double-click safe: loading + disabled while in flight
 * (R22 discipline — one click ships exactly one download request);
 * 4. refusal wording rides through untranslated (bizErrorMsg pass-through);
 * 5. every new string lives in both locales (i18n dual-track, zero hard
 * CJK in the view).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import reportRaw from '@/views/perf/PerfReportView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'
import { parseContentDispositionFileName } from '@/utils/exportDownload'

describe('perf api export mirror (report export)', () => {
  it('exports exportPerfReport against GET /perf/runs/{id}/report-export', () => {
    expect(apiRaw).toMatch(/export function exportPerfReport\(/)
    expect(apiRaw).toMatch(/\/perf\/runs\/\$\{runId\}\/report-export\?projectId=/)
    expect(apiRaw).toMatch(/&format=\$\{format\}/)
  })

  it('rides the shared blob-download helper (no second download path)', () => {
    expect(apiRaw).toMatch(/import \{ fetchAndDownloadBlob \} from '@\/utils\/exportDownload'/)
    expect(apiRaw).toMatch(/return fetchAndDownloadBlob\(/)
  })

  it('parses server-composed file names (fallback only, never a self-built name)', () => {
    expect(parseContentDispositionFileName(
      "attachment; filename*=UTF-8''perf-run-7.pdf",
    )).toBe('perf-run-7.pdf')
    expect(parseContentDispositionFileName(null)).toBe('')
  })
})

describe('report export door (B-12 consume face)', () => {
  it('consumes the door through the api mirror only', () => {
    expect(reportRaw).toMatch(/exportPerfReport/)
    expect(reportRaw).not.toMatch(/\/api\/v1\//) // no direct request path in the view
    expect(reportRaw).not.toMatch(/fetch\(/) // no second download implementation
  })

  it('one door with pdf/html variants, verb + object wording', () => {
    expect(reportRaw).toMatch(/data-test="perf-report-export-btn"/)
    expect(reportRaw).toMatch(/data-test="perf-report-export-pdf"/)
    expect(reportRaw).toMatch(/data-test="perf-report-export-html"/)
  })

  it('the format toggle names its object and shows the dropdown affordance (vision close-out)', () => {
    // a bare "format" label left the split door unreadable and the menu-look
    // button carried no caret — both fixed per the B-12 vision ledger
    expect(zhRaw).toMatch(/exportFormat: '导出格式'/)
    expect(enRaw).toMatch(/exportFormat: 'Export format'/)
    const at = reportRaw.indexOf('data-test="perf-report-export-format-toggle"')
    const toggle = reportRaw.slice(at, at + 240)
    expect(toggle).toMatch(/<el-icon class="el-icon--right"><ArrowDown \/><\/el-icon>/)
    expect(reportRaw).toMatch(/import \{ ArrowDown \} from '@element-plus\/icons-vue'/)
    // narrow-container safety (vision dim-4): the button row wraps instead of
    // overflowing when the report head is squeezed
    expect(reportRaw).toMatch(/\.perf-report__head \{ display: flex[^}]*flex-wrap: wrap/)
  })

  it('the door is double-click safe: loading + disabled while in flight', () => {
    const at = reportRaw.indexOf('data-test="perf-report-export-pdf"')
    const btn = reportRaw.slice(at - 300, at + 120)
    expect(btn).toMatch(/:loading="exporting"/)
    expect(btn).toMatch(/:disabled="exporting"/)
  })

  it('refusal wording rides through untranslated (bizErrorMsg)', () => {
    const fn = reportRaw.slice(reportRaw.indexOf('async function exportReport'),
      reportRaw.indexOf('async function exportReport') + 700)
    expect(fn).toMatch(/bizErrorMsg/)
    expect(fn).toMatch(/exporting\.value = false/)
  })
})

describe('export i18n dual-track', () => {
  it('zh and en both carry the export keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/exportGo: '.*'/)
      expect(raw).toMatch(/exportPdf: '.*'/)
      expect(raw).toMatch(/exportHtml: '.*'/)
      expect(raw).toMatch(/exportHint: '.*'/)
      expect(raw).toMatch(/exportDone: '.*'/)
    }
  })

  it('the view renders zero hard-coded CJK strings', () => {
    const template = reportRaw.slice(reportRaw.indexOf('<template>'))
    expect(template).not.toMatch(/[\u4e00-\u9fff]/)
  })
})
