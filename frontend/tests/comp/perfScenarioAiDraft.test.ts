/**
 * (B-11): the wizard gets an AI drafting door — one sentence of
 * load-test intent becomes a pre-filled step table behind the default-off
 * switch, and every step the save gate trims is named on screen (no silent
 * drops, no second validation in the browser).
 *
 * Contract asserted here:
 * 1. the api layer mirrors the admin door (POST /perf/scenarios/draft with
 * {projectId, description} and the {steps, droppedCount, droppedReasons}
 * reply shape);
 * 2. the wizard renders the draft input + button inside the target step with
 * a proper label/id pairing (no dangling label = Issues gate);
 * 3. the door is honest: disabled without a project context, without typed
 * text, and while a draft request is in flight (double-click guard);
 * 4. pre-fill rides normalizeHydratedSteps — the SAME hydration path an edit
 * or a template pick uses (no second mapping, red line 6);
 * 5. trimmed steps are surfaced per-reason via a warning toast, never
 * swallowed;
 * 6. every new string lives in both locales (i18n dual-track, zero hard CJK
 * in the view).
 */
import { describe, expect, it } from 'vitest'
import apiRaw from '@/api/perf.ts?raw'
import wizardRaw from '@/views/perf/PerfScenarioWizard.vue?raw'
import zhRaw from '@/utils/locales/zh-CN.ts?raw'
import enRaw from '@/utils/locales/en-US.ts?raw'

describe('perf api contract mirror (AI scenario draft)', () => {
  it('exports draftPerfScenario against POST /perf/scenarios/draft', () => {
    expect(apiRaw).toMatch(/export function draftPerfScenario\(/)
    expect(apiRaw).toMatch(/url:\s*'\/perf\/scenarios\/draft',\s*method:\s*'post'/)
  })

  it('mirrors the reply shape the core orchestration assembles', () => {
    // core PerfScenarioDraftServiceImpl puts steps/droppedCount/droppedReasons
    // into the reply map — the frontend view type must carry all three keys.
    expect(apiRaw).toMatch(/interface PerfScenarioDraftView \{[\s\S]*?steps:[\s\S]*?droppedCount:[\s\S]*?droppedReasons:[\s\S]*?\}/)
  })
})

describe('wizard AI draft door (B-11)', () => {
  it('consumes the draft endpoint through the api mirror only', () => {
    expect(wizardRaw).toMatch(/draftPerfScenario/)
    // no direct axios/request to the draft path inside the view
    expect(wizardRaw).not.toMatch(/\/perf\/scenarios\/draft/)
  })

  it('renders a labelled one-sentence input plus the draft button in the target step', () => {
    expect(wizardRaw).toMatch(/data-test="pw-ai-draft-input"/)
    expect(wizardRaw).toMatch(/data-test="pw-ai-draft"/)
    // label points at the control id (no dangling label = Issues gate)
    expect(wizardRaw).toMatch(/for="pw-ai-draft-input"/)
    expect(wizardRaw).toMatch(/id="pw-ai-draft-input"/)
  })

  it('the door is honest: no project / no text / in flight all disable it', () => {
    // G-group: missing context disables with a human pointer, never a late generic error
    expect(wizardRaw).toMatch(/:disabled="!form\.projectId \|\| !aiDraftText\.trim\(\) \|\| aiDrafting"/)
    expect(wizardRaw).toMatch(/importBlockedReason|aiDraftBlockedReason/)
  })

  it('pre-fill rides the shared hydration path, never a second mapping', () => {
    const fn = wizardRaw.slice(wizardRaw.indexOf('async function runAiDraft'))
    expect(fn).toMatch(/normalizeHydratedSteps/)
    expect(fn).toMatch(/paramsOpen\.value = form\.steps\.map/)
  })

  it('trimmed steps are named on screen, never swallowed', () => {
    const fn = wizardRaw.slice(wizardRaw.indexOf('async function runAiDraft'))
    expect(fn).toMatch(/droppedReasons/)
    expect(fn).toMatch(/ElMessage\.warning/)
  })
})

describe('i18n dual-track', () => {
  it('both locales carry the new keys', () => {
    for (const raw of [zhRaw, enRaw]) {
      expect(raw).toMatch(/aiDraft: '/)
      expect(raw).toMatch(/aiDraftPh: '/)
      expect(raw).toMatch(/aiDraftHint: '/)
      expect(raw).toMatch(/aiDraftDone: '/)
      expect(raw).toMatch(/aiDraftDropped: '/)
      expect(raw).toMatch(/aiDraftBlockedNoProject: '/)
    }
  })
})
