/**
 * stage-3 layout contract (source-mirror posture of
 * inventoryDeeplinkT370s4.test.ts — assertions read SFC/TS sources as text).
 *
 * The contract panel retires from the document page and its whole entry set
 * moves onto the interface inventory page header, one source only:
 * 1. DocumentListView carries no spec panel, no spec upload dialog and no
 * version-chain history dialog anymore (the compare drawer on the
 * inventory page is the single version-history surface — no twins);
 * 2. the document page keeps one honest guidance line pointing at
 * /api-inventory so the retired entrance is findable, not vanished;
 * 3. ApiInventoryView header owns the upload entrance (unified upload dialog)
 * and the URL-pull entrance, both gated by the requirement write surface
 * (admin or project owner role=2, backend double-gate stays authoritative);
 * 4. the suggestion-to-generate entrance travels to inventory rows: a per-row
 * generate button reusing endpointSuggestionText and the existing
 * /ai/generate ?requirement= deep-link grammar (zero new backend);
 * 5. every new string lives in both locale files (i18n dual-track) and the
 * dead panel keys are removed from both.
 */
import { describe, expect, it } from 'vitest'

import docViewRaw from '@/views/ai/DocumentListView.vue?raw'
import inventoryViewRaw from '@/views/apitest/ApiInventoryView.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T370 stage-3 contract panel retirement', () => {
  it('document page no longer renders the in-page contract panel or its dialogs', () => {
    expect(docViewRaw).not.toContain('doc-spec')
    expect(docViewRaw).not.toContain('spec-panel')
    // history dialog retired too — the inventory compare drawer is the twin-free surface
    expect(docViewRaw).not.toContain('historyVisible')
    expect(docViewRaw).not.toContain('listApiSpecHistory')
    expect(docViewRaw).not.toContain('listApiSpecs')
    expect(docViewRaw).not.toContain('uploadApiSpec')
  })

  it('document page keeps one guidance line pointing at the inventory page', () => {
    expect(docViewRaw).toContain('doc-contract-moved-hint')
    expect(docViewRaw).toContain("t('document.specPanelGone')")
    expect(docViewRaw).toContain("path: '/api-inventory'")
  })

  it('inventory header owns the upload entrance behind the write gate', () => {
    expect(inventoryViewRaw).toContain('api-inventory-upload-btn')
    expect(inventoryViewRaw).toContain('uploadApiSpec')
    expect(inventoryViewRaw).toContain('FileUploadDialog')
    // write gate mirrors the requirement write surface (admin || owner role=2)
    expect(inventoryViewRaw).toContain('isAdmin')
    expect(inventoryViewRaw).toMatch(/role === 2/)
  })

  it('inventory header owns the URL-pull entrance behind the same write gate', () => {
    expect(inventoryViewRaw).toContain('api-inventory-url-btn')
    expect(inventoryViewRaw).toContain('fetchApiSpecByUrl')
    expect(inventoryViewRaw).toContain('api-inventory-url-dialog')
  })

  it('generate-suggestion entrance travels to inventory rows', () => {
    expect(inventoryViewRaw).toContain('api-inventory-gen-btn')
    expect(inventoryViewRaw).toContain('endpointSuggestionText')
    expect(inventoryViewRaw).toMatch(/path: '\/ai\/generate'/)
    // the full endpoint snapshot comes from the existing history chain — no new endpoint
    expect(inventoryViewRaw).toContain('listApiSpecHistory')
  })

  it('new copy exists in both locales and dead panel keys are gone from both', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toContain('specPanelGone:')
      expect(raw).toContain("urlPull:")
      expect(raw).toContain('genBtn:')
      // retired panel keys must not survive in either track
      expect(raw).not.toContain('specPanelTitle:')
      expect(raw).not.toContain('specHistoryTitle:')
      expect(raw).not.toContain('specColName:')
    }
  })
})
