/**
 * interface-set request import panel (layout contract, RED first).
 *
 * Defect closed: the wizard's "reference an API test case" row only allowed
 * picking ONE request to prefill ONE step, while the real load-testing target
 * is a whole interface set (verdict GB-6: import materialises steps once,
 * decoupled from the set afterwards — never a runtime linkage).
 *
 * Contract asserted here (raw-source anchors, same style as the
 * precedent — anchors are the stable data-test surface):
 * 1. the single request dropdown is retired; a collection select plus an
 * "open import panel" door replaces it (≤3 clicks to generate N steps);
 * 2. the panel candidate list is windowed (useWindowedList + pads), never a
 * page-level full v-for of up to 500 rows;
 * 3. selection is keyed by request id (survives search filtering and
 * re-windowing; index-keyed selection is the known trap);
 * 4. instant search covers method / path / name (three fields, one box);
 * 5. sticky footer carries "N selected to import" + the import button +
 * chunked progress line "Generated N/M" (anti-freeze iron rule);
 * 6. keyboard path exists: Space toggle, Ctrl/Cmd+A select-all-filtered,
 * Enter confirm, Esc close (E2E asserts per key later);
 * 7. merged headers/auth ride the batch effective-preview endpoint in ONE
 * round trip (no per-request loop — server contract);
 * 8. URL building is single-source: fillStepFromRequest and the import both
 * go through requestStepUrl (red line 6, no second copy);
 * 9. one-step undo of a bulk import (snapshot restore), toast names the
 * count and the source set; login-type auth is never baked — the skip
 * note points at the step-level login flag + scenario login strategy.
 */
import { describe, expect, it } from 'vitest'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import apiRaw from '@/api/apiTest.ts?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T443 perf wizard interface-set import panel', () => {
  it('retires the single-request dropdown and opens a panel instead', () => {
    expect(wizardRaw).not.toContain('data-test="pw-ref-request"')
    expect(wizardRaw).toContain('data-test="pw-ref-collection"')
    expect(wizardRaw).toContain('data-test="pw-open-import"')
    expect(wizardRaw).toContain('data-test="pw-import-panel"')
  })

  it('candidate list is windowed with pads (worst-scale containment)', () => {
    expect(wizardRaw).toContain("from '@/composables/useWindowedList'")
    expect(wizardRaw).toMatch(/const importWindow = useWindowedList\(/)
    expect(wizardRaw).toContain('data-windowed-row')
    expect(wizardRaw).toContain('importPadTop')
    expect(wizardRaw).toContain('importPadBottom')
    // honest empty state when the set holds no requests
    expect(wizardRaw).toContain('importEmpty')
  })

  it('selection is keyed by request id, never by row index', () => {
    expect(wizardRaw).toMatch(/const importSelected = ref<Set<number>>\(/)
    expect(wizardRaw).toMatch(/importSelected\.value\.has\(r\.id\)/)
    expect(wizardRaw).not.toMatch(/importSelected\.value\.has\((\w+), ?idx\)/)
  })

  it('instant search filters the three candidate fields client-side', () => {
    expect(wizardRaw).toMatch(/const filteredImportRows = computed[<(]/)
    expect(wizardRaw).toMatch(/r\.method\.toLowerCase\(\)\.includes/)
    expect(wizardRaw).toMatch(/r\.urlPath\.toLowerCase\(\)\.includes/)
    expect(wizardRaw).toMatch(/r\.name\.toLowerCase\(\)\.includes/)
  })

  it('sticky footer: selected count + import door + chunked progress line', () => {
    expect(wizardRaw).toContain('data-test="pw-import-count"')
    expect(wizardRaw).toContain('data-test="pw-import-run"')
    expect(wizardRaw).toContain('data-test="pw-import-progress"')
    expect(wizardRaw).toContain('data-test="pw-import-select-all"')
    // chunked commit with frame yield (no single-block freeze)
    expect(wizardRaw).toMatch(/IMPORT_CHUNK/)
    expect(wizardRaw).toMatch(/nextTick\(\)|setTimeout/)
  })

  it('keyboard path: Space / Ctrl(Cmd)+A / Enter / Esc', () => {
    expect(wizardRaw).toMatch(/function onImportKeydown\(/)
    expect(wizardRaw).toContain("e.key === 'Escape'")
    expect(wizardRaw).toContain("e.key.toLowerCase() === 'a'")
    expect(wizardRaw).toContain("e.key === 'Enter'")
    expect(wizardRaw).toContain("e.key === ' '")
  })

  it('merged headers/auth ride the batch endpoint in ONE round trip', () => {
    expect(apiRaw).toContain("url: '/apitest/requests/effective-batch'")
    expect(wizardRaw).toContain('listEffectiveBatch')
    // consumed once per panel open — no per-request effectivePreview loop here
    expect(wizardRaw).not.toMatch(/await getEffectivePreview\(/)
  })

  it('URL building is single-source (base+path helper reused by both paths)', () => {
    expect(wizardRaw).toMatch(/function requestStepUrl\(/)
    // fillStepFromRequest must delegate instead of re-splicing base+path
    const filler = wizardRaw.slice(
      wizardRaw.indexOf('function fillStepFromRequest'),
      wizardRaw.indexOf('function fillStepFromRequest') + 500,
    )
    expect(filler).toContain('requestStepUrl(')
    expect(filler).not.toMatch(/replace\(\/\\\/\+\$\//)
  })

  it('bulk import is undoable one step and login auth is never baked', () => {
    expect(wizardRaw).toContain('data-test="pw-import-undo"')
    expect(wizardRaw).toMatch(/function undoImport\(/)
    expect(wizardRaw).toContain('importAuthSkipped')
    // the import path does NOT touch the auth flag of generated steps
    const run = wizardRaw.slice(wizardRaw.indexOf('function materializeRows'), wizardRaw.indexOf('materializeRows') + 2000)
    expect(run).not.toMatch(/\.auth = true/)
  })

  it('i18n both tracks carry the panel vocabulary', () => {
    for (const key of ['importOpen', 'importSearchPh', 'importSelectAll', 'importCount', 'importProgress', 'importRun', 'importNeedEnv', 'importEmpty', 'imported', 'importUndo', 'importAuthSkipped']) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })

  // ---------- cross-set import with a merge preview ----------
  it('the set picker takes multiple sets and keeps the pick order', () => {
    // door row binds an ARRAY model (collection pick order = between-set step order)
    expect(wizardRaw).toMatch(/const refCollectionIds = ref<number\[\]>\(/)
    expect(wizardRaw).toContain('v-model="refCollectionIds"')
    expect(wizardRaw).not.toMatch(/const refCollectionId = ref</)
    // candidate rows are concatenated per set in pick order (within a set = frozen order)
    expect(wizardRaw).toMatch(/refCollectionIds\.value\.flatMap/)
  })

  it('candidate rows carry a source-set badge (cross-set provenance visible)', () => {
    expect(wizardRaw).toContain('data-test="pw-import-origin"')
  })

  it('import is confirmed through a merge preview before steps are committed', () => {
    expect(wizardRaw).toContain('data-test="pw-import-preview"')
    expect(wizardRaw).toContain('data-test="pw-import-confirm"')
    // the panel footer opens the preview; the chunked commit runs from the confirmation
    expect(wizardRaw).toMatch(/function requestImport\(/)
    expect(wizardRaw).toMatch(/function confirmImport\(/)
    expect(wizardRaw).toMatch(/@click="requestImport"/)
    expect(wizardRaw).toMatch(/@click="confirmImport"/)
    // preview list rides the windowed renderer too (500-row worst scale)
    expect(wizardRaw).toMatch(/const previewWindow = useWindowedList\(/)
  })

  it('duplicate method+path rows are flagged, never silently dropped', () => {
    expect(wizardRaw).toMatch(/function duplicateKeyOf\(/)
    expect(wizardRaw).toMatch(/const duplicateKeys = computed/)
    expect(wizardRaw).toContain('importDupTag')
    expect(wizardRaw).toContain('importDupHint')
  })

  it('the batch effective preview is pulled once per set and merged', () => {
    // one call per selected collection, merged into effectiveRows (no per-request loop)
    expect(wizardRaw).toMatch(/listEffectiveBatch\(/)
    const opener = wizardRaw.slice(wizardRaw.indexOf('async function openImportPanel'), wizardRaw.indexOf('async function openImportPanel') + 1600)
    expect(opener).toMatch(/Promise\.all/)
    expect(opener).not.toMatch(/getEffectivePreview\(/)
  })

  it('slice-5 i18n keys ride both tracks', () => {
    for (const key of ['importPreviewTitle', 'importPreviewConfirm', 'importDupTag', 'importDupHint', 'importCancel']) {
      expect(zhRaw).toContain(`${key}:`)
      expect(enRaw).toContain(`${key}:`)
    }
  })
})
