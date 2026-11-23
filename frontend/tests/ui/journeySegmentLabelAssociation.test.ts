/**
 * Segment-form label association contract (Chromium iron rule 5c).
 *
 * An el-form-item caption auto-binds label[for] to the single registered
 * control id; el-radio-group registers its id on the root DIV, which is not
 * a labelable target, so Blink reports FormLabelForMatchesNonExistingIdError
 * (surfaced by the golden journey E2E Issues gate). The pinned fix shape is
 * the CaseDetailView precedent: for="" on the form item plus id="" on the
 * group. This contract pins that shape so a refactor cannot silently
 * reintroduce the dangling association.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const viewSrc = readFileSync(
  fileURLToPath(new URL('../../src/views/journey/JourneyPlanDetailView.vue', import.meta.url)),
  'utf-8',
)

describe('journey segment form label opt-out', () => {
  const kindItem = /<el-form-item[^>]*journeys\.colTargetKind[^>]*>([\s\S]*?)<\/el-form-item>/.exec(viewSrc)

  it('keeps the target-kind caption off the dangling auto label-for association', () => {
    expect(kindItem, 'target-kind form item not found').toBeTruthy()
    expect(kindItem![0]).toContain('for=""')
    expect(kindItem![1]).toMatch(/<el-radio-group\s+id=""/)
  })

  it('no labeled form item wraps a bare el-radio-group without the group opt-out', () => {
    const items = viewSrc.match(/<el-form-item\b[\s\S]*?<\/el-form-item>/g) ?? []
    for (const item of items) {
      if (!/:label=/.test(item)) continue
      if (/<el-radio-group/.test(item)) {
        expect(item, 'labeled radio group must opt out with for=""').toContain('for=""')
      }
    }
  })
})
