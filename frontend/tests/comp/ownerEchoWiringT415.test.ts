/**
 * ownership-echo wiring contract for the pages the old S-3
 * scanner missed (i18n-blind create entries — labels live in locales, so the
 * literal create-word probe never saw them).
 *
 * Clauses (same posture as ownerEchoWiringT396b.test.ts, whole-site
 * ownership-echo ruling documented under docs/change-eval):
 * 1. every create/write dialog on a context-consuming page renders the shared
 * <ProjectOwnerEcho /> read-only ownership line, placed before the first
 * form field / upload affordance (value single-sourced from the store);
 * 2. the create entry button stays disabled while the header context is empty
 * (`:disabled="!projectId"`), so the echo is never the only safeguard.
 *
 * Dialog blocks cover both plain <el-dialog> pages and the shared
 * <FileUploadDialog> wrapper pages (the wrapper exposes an #extra slot that
 * renders at the top of the dialog body — the echo slot).
 */
import { describe, expect, it } from 'vitest'

import documentRaw from '@/views/ai/DocumentListView.vue?raw'
import apiInventoryRaw from '@/views/apitest/ApiInventoryView.vue?raw'
import popupRuleRaw from '@/views/appauto/PopupRuleApprovalView.vue?raw'
import testReportRaw from '@/views/report/TestReportListView.vue?raw'

interface Site {
  name: string
  src: string
  /** start anchor of the create/write dialog block in the page source */
  blockStart: string
  /** closing tag that ends the block */
  blockEnd: string
  /** data-test anchor of the context-gated create button */
  btnTest: string
}

const sites: Site[] = [
  {
    name: 'DocumentListView (upload dialog)',
    src: documentRaw,
    // Indentation rides the post- shell-retire shape (8-space children).
    blockStart: '<FileUploadDialog\n        v-model="uploadDialogVisible"',
    blockEnd: '</FileUploadDialog>',
    btnTest: 'doc-upload-btn',
  },
  {
    name: 'ApiInventoryView (contract upload)',
    src: apiInventoryRaw,
    blockStart: '<FileUploadDialog\n        v-model="specUploadVisible"',
    blockEnd: '</FileUploadDialog>',
    btnTest: 'api-inventory-upload-btn',
  },
  {
    name: 'ApiInventoryView (URL pull dialog)',
    src: apiInventoryRaw,
    blockStart: 'data-test="api-inventory-url-dialog"',
    blockEnd: '</el-dialog>',
    btnTest: 'api-inventory-url-btn',
  },
  {
    name: 'PopupRuleApprovalView (self-learn dialog)',
    src: popupRuleRaw,
    blockStart: 'data-test="popup-rule-learn-dialog"',
    blockEnd: '</el-dialog>',
    btnTest: 'popup-rule-learn-btn',
  },
  {
    name: 'TestReportListView (generate dialog)',
    src: testReportRaw,
    blockStart: 'data-test="report-gen-dialog"',
    blockEnd: '</el-dialog>',
    btnTest: 'report-generate-btn',
  },
]

function blockOf(site: Site): string {
  const start = site.src.indexOf(site.blockStart)
  expect(start, `${site.name}: dialog anchor missing`).toBeGreaterThan(-1)
  const end = site.src.indexOf(site.blockEnd, start)
  expect(end, `${site.name}: dialog block never closes`).toBeGreaterThan(start)
  return site.src.slice(start, end + site.blockEnd.length)
}

describe('T415 rule 1: missed create dialogs echo the owning project read-only', () => {
  it.each(sites)('%s hosts <ProjectOwnerEcho /> inside the dialog block', (site) => {
    expect(site.src).toContain("import ProjectOwnerEcho from '@/components/ProjectOwnerEcho.vue'")
    expect(blockOf(site)).toMatch(/<ProjectOwnerEcho\s*\/?>/)
  })

  it('retires no second project channel inside these dialogs', () => {
    for (const site of sites) {
      expect(blockOf(site)).not.toMatch(/<ProjectSelect/)
    }
  })
})

describe('T415 rule 2: the same create entries stay gated on the header context', () => {
  it.each(sites)('%s create button carries :disabled="!projectId"', (site) => {
    const re = new RegExp(
      `:disabled="!projectId"[\\s\\S]{0,240}data-test="${site.btnTest}"` +
        `|data-test="${site.btnTest}"[\\s\\S]{0,240}:disabled="!projectId"`,
    )
    expect(site.src).toMatch(re)
  })
})
