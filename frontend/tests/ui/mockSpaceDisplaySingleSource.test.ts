import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { mockSpaceLabel } from '@/utils/mockSpaceLabel'

/**
 * Contract for the mock-space display-name single source and the entry-band
 * action hierarchy on the mock documentation page.
 *
 * Why: the same space was rendered in two formats at once — the picker built
 * the name with the alias in full-width parentheses while the contract-import
 * wizard echoed the bare name handed down by the page — so the user saw two
 * different labels for one object. The label builder was also copied into
 * every mock view, which is how
 * the drift happened in the first place. One builder, every consumer.
 *
 * Second assertion: the entry band carried two filled primary buttons
 * (structured create + contract import). One band keeps one primary action;
 * the import door reads as a secondary outline button.
 */

const ROOT = resolve(__dirname, '../..')

const SPACE_VIEWS = [
  'src/views/mock/MockDocsView.vue',
  'src/views/mock/MockInspectorView.vue',
  'src/views/mock/MockRecordingView.vue',
  'src/views/mock/MockScenarioListView.vue',
  'src/components/mock/MockStubDirectWizard.vue',
]

function source(rel: string): string {
  return readFileSync(resolve(ROOT, rel), 'utf8')
}

describe('mock space display name is built in exactly one place', () => {
  it('no mock view keeps a local spaceLabel copy', () => {
    const copies = SPACE_VIEWS.filter((rel) => /function spaceLabel\s*\(/.test(source(rel)))
    expect(copies).toEqual([])
  })

  it('the shared label builder is the one consumers import', () => {
    const util = source('src/utils/mockSpaceLabel.ts')
    expect(util).toMatch(/export function mockSpaceLabel/)
    // the alias separator is composed once, inside the builder itself
    expect(mockSpaceLabel({ name: 'Alpha', alias: 'Beta' })).toBe('Alpha\uff08Beta\uff09')
    expect(mockSpaceLabel({ name: 'Alpha', alias: null })).toBe('Alpha')
    expect(mockSpaceLabel(null)).toBe('')
    for (const rel of SPACE_VIEWS) {
      expect(source(rel), rel).toMatch(/mockSpaceLabel/)
    }
  })

  it('the contract-import wizard echo rides the shared label, not the bare name', () => {
    expect(source('src/views/mock/MockDocsView.vue')).toMatch(
      /:preset-space-name="activeSpace \? mockSpaceLabel\(activeSpace\) : ''"/,
    )
  })
})

describe('mock docs entry band keeps one filled primary action', () => {
  it('the contract import door is a secondary outline button', () => {
    const docs = source('src/views/mock/MockDocsView.vue')
    const importBlock = docs.slice(
      docs.indexOf('data-test="mock-docs-import-contract"') - 320,
      docs.indexOf('data-test="mock-docs-import-contract"') + 60,
    )
    expect(importBlock).not.toMatch(/type="primary"/)
    expect(importBlock).not.toMatch(/\bplain\b/)
    // the structured create door stays the band's single primary action
    const createBlock = docs.slice(
      docs.indexOf('data-test="mock-docs-new-structured"') - 200,
      docs.indexOf('data-test="mock-docs-new-structured"') + 60,
    )
    expect(createBlock).toMatch(/type="primary"/)
  })
})
