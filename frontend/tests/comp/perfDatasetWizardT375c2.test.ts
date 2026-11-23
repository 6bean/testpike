/**
 * 2: wizard data-pool wiring (RED first).
 *
 * The backend already carries the CSV dataset ledger through the admin doors
 * (upload/page/view/delete + one alloc-policy door exposing the engine x
 * row-allocation matrix). This contract pins the frontend half:
 * 1. api/perf.ts mirrors all five dataset doors (paged window + policy);
 * 2. the wizard renders a dataset picker with paged load-more (display-scale
 * iron law: never pull the unbounded ledger list);
 * 3. the allocation strategy group consumes the policy door — modes the
 * current engine cannot honour are honestly disabled with a plain-words
 * reason naming the engine, never silently hidden;
 * 4. buildPayload carries datasetId/rowAllocMode and openWith back-fills the
 * two persisted columns (edit round-trip zero-loss);
 * 5. both locale files ship the key family (i18n dual-track, zero hard-coded
 * Chinese in the vue source).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('T375 4c-2 dataset ledger mirror in the perf api layer', () => {
  it('mirrors all five dataset doors of the admin contract', () => {
    // upload rides the shared multipart door (postMultipart single source, ),
    // not a hand-rolled url:/method: literal
    expect(apiRaw).toContain("postMultipart<number>('/perf/datasets'")
    expect(apiRaw).toContain("'/perf/datasets/page'")
    expect(apiRaw).toContain('/perf/datasets/alloc-policy')
    expect(apiRaw).toContain('export function uploadPerfDataset')
    expect(apiRaw).toContain('export function pagePerfDatasets')
    expect(apiRaw).toContain('export function allocPerfDatasetPolicy')
    expect(apiRaw).toContain('export function deletePerfDataset')
  })

  it('declares the dataset row / page / policy shapes', () => {
    expect(apiRaw).toContain('export interface PerfDatasetItem')
    expect(apiRaw).toContain('export interface PerfDatasetPage')
    expect(apiRaw).toContain('export interface PerfAllocPolicy')
    // scenario item mirrors the two persisted binding columns (core SELECT already returns them)
    expect(apiRaw).toContain('dataset_id')
    expect(apiRaw).toContain('row_alloc_mode')
  })
})

describe('T375 4c-2 wizard data-pool section', () => {
  it('renders a dataset picker with an honest load-more footer', () => {
    expect(wizardRaw).toContain('data-test="pw-dataset-select"')
    expect(wizardRaw).toContain('data-test="pw-dataset-loadmore"')
    expect(wizardRaw).toContain('data-test="pw-dataset-upload"')
  })

  it('renders the allocation strategy group fed by the policy door', () => {
    expect(wizardRaw).toContain('data-test="pw-alloc-group"')
    expect(wizardRaw).toContain('allocPerfDatasetPolicy')
    // unsupported modes stay visible but honestly disabled (never hidden)
    expect(wizardRaw).toMatch(/:disabled="!isModeSupported/)
  })

  it('carries the binding through save and back-fills it on edit', () => {
    const payloadBlock = wizardRaw.slice(
      wizardRaw.indexOf('function buildPayload'),
      wizardRaw.indexOf('function nextStep'),
    )
    expect(payloadBlock).toContain('datasetId')
    expect(payloadBlock).toContain('rowAllocMode')
    const openBlock = wizardRaw.slice(
      wizardRaw.indexOf('function openWith'),
      wizardRaw.indexOf('defineExpose'),
    )
    expect(openBlock).toContain('sc.dataset_id')
    expect(openBlock).toContain('sc.row_alloc_mode')
  })

  it('ships the i18n key family on both tracks', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toContain('dataset: ')
      expect(raw).toContain('datasetUpload: ')
      expect(raw).toContain('allocMode: ')
      expect(raw).toContain('allocUnsupported: ')
      expect(raw).toContain('SEQUENTIAL_EXCLUSIVE: ')
      expect(raw).toContain('VUSER_EXCLUSIVE: ')
      expect(raw).toContain('CYCLE: ')
    }
  })

  it('no hard-coded Chinese in the new dataset block of the vue source', () => {
    // bounded by the two data-pool anchors (the next template section legitimately
    // carries Chinese comments, which are out of this contract's scope)
    const block = wizardRaw.slice(
      wizardRaw.indexOf('data-test="pw-dataset-select"'),
      wizardRaw.indexOf('data-test="pw-alloc-group"'),
    )
    expect(block.length).toBeGreaterThan(200)
    expect(block).not.toMatch(/[\u4e00-\u9fff]/)
  })
})
