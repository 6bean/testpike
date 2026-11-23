/**
 * AI generation recovery + height contract .
 *
 * The report: submitting a batch and leaving the page lost the queue — the
 * progress panel could never be re-attached, the requirement page had no
 * "case generating" marker, and both panels were capped by page-level pixel
 * maxima instead of filling the content band.
 *
 * Gates (source-text lane; the behavioral truth rides the E2E journey):
 * 1) AiGenerateView must call the recovery door (getLatestGeneration) on the
 * handle-less entry path and must NOT clear batch handles on mode switch;
 * 2) the batch/task handles must ride the URL (?batchId / ?task sync);
 * 3) the fixed page caps (440px rows / 320px queue) are gone and the height
 * rides the theme fill block since the 11-20 R65 adoption
 * (no private vh calc; --tp-layout-content-offset belongs to the chat page);
 * 4) both pollers treat a stopped (cancelled=5) run as terminal — settled
 * buckets seal the drawer, never spin;
 * 5) the requirement page consumes the live-annotation door and renders the
 * badge with a data-test anchor.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname

const genView = readFileSync(`${SRC}/views/ai/AiGenerateView.vue`, 'utf8')
const reqView = readFileSync(`${SRC}/views/requirement/RequirementListView.vue`, 'utf8')
const taskPolling = readFileSync(`${SRC}/composables/useAiTaskPolling.ts`, 'utf8')
const batchPolling = readFileSync(`${SRC}/composables/useAiBatchPolling.ts`, 'utf8')
const aiApi = readFileSync(`${SRC}/api/ai.ts`, 'utf8')

describe('T677 recovery contract — AiGenerateView', () => {
  it('re-entry without deep-link handles recovers from the server door', () => {
    expect(aiApi).toContain('getLatestGeneration')
    expect(genView).toContain('getLatestGeneration')
    expect(genView).toContain('recoverGeneration')
    // Recovery must be gated on the handle-less path, never skipped outright.
    expect(genView).toMatch(/if \(!route\.query\.task && !route\.query\.batchId\)[\s\S]*recoverGeneration\(\)/)
  })

  it('mode switch keeps both server handles (no more local amnesia)', () => {
    const fn = genView.match(/function onModeChange\(\): void \{[\s\S]*?\n\}/)
    expect(fn, 'onModeChange present').not.toBeNull()
    expect(fn![0]).not.toMatch(/batchIdRef\.value = null/)
    expect(fn![0]).not.toMatch(/batch\.value = null/)
  })

  it('handles ride the URL query (F1 page-state-in-URL)', () => {
    expect(genView).toContain('q.batchId = String(b)')
    expect(genView).toContain('router.replace({ query: q })')
    expect(genView).toMatch(/Number\(route\.query\.batchId\)/)
  })

  it('height fills the content band via the single-source token; fixed caps are gone', () => {
    // 11-20 R65 adoption: the page no longer pins its own vh formula — the
    // theme fill block owns the band height, and the row rides it with
    // flex:1. The contract now pins THAT shape (no private calc, fill via
    // flex chain); the fixed 440/320 caps stay retired exactly as before.
    expect(genView).not.toMatch(/\.ai-gen-root \{[^}]*calc\(100vh/)
    expect(genView).toMatch(/\.ai-gen-row \{[\s\S]*?flex: 1 1 0;/)
    expect(genView).toMatch(/\.ai-gen-row \{[\s\S]*?min-height: 0;/)
    expect(genView).not.toContain('max-height: 440px')
    expect(genView).not.toContain('max-height: 320px')
    expect(genView).toContain('.batch-rows-scroll {')
    expect(genView).toMatch(/\.batch-rows-scroll \{[\s\S]*?flex: 1;[\s\S]*?overflow-y: auto;/)
    expect(genView).toMatch(/\.batch-item-list \{[\s\S]*?flex: 1;[\s\S]*?overflow-y: auto;/)
  })
})

describe('T677 settled-state seal — pollers', () => {
  it('single-task poller treats cancelled(5) as terminal', () => {
    expect(taskPolling).toContain('task.value.status === 5')
  })
  it('batch poller counts the cancelled bucket as settled before sealing', () => {
    expect(batchPolling).toMatch(/b\.done \+ b\.failed \+ b\.cancelled >= b\.total/)
  })
})

describe('T677 requirement annotation — RequirementListView', () => {
  it('consumes the live door and renders the badge anchor', () => {
    expect(aiApi).toContain('listLiveRequirementGenerations')
    expect(reqView).toContain('listLiveRequirementGenerations')
    expect(reqView).toContain('req-gen-live-')
    expect(reqView).toContain('refreshGenLive')
    // Silent window closes itself: unmount must clear the 5s re-read timer.
    expect(reqView).toContain('onUnmounted(stopGenLiveTimer)')
  })
})
