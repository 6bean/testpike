import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Shared-wizard mount contract: the contract-direct stub wizard is one
 * component file with exactly two mount points — the API-test page and the
 * mock space workbench (docs tab). A second copy of the wizard or a page
 * importing an old path is a fork; both fail here (single-source rule for
 * parallel-editable shared components).
 */

const root = resolve(__dirname, '../../src')
const wizardPath = 'components/mock/MockStubDirectWizard.vue'
const wizardSrc = readFileSync(resolve(root, wizardPath), 'utf8')
const apiTestSrc = readFileSync(resolve(root, 'views/apitest/ApiTestView.vue'), 'utf8')
const docsSrc = readFileSync(resolve(root, 'views/mock/MockDocsView.vue'), 'utf8')

describe('mock stub direct wizard shared mount', () => {
  it('wizard lives once under components/mock and no legacy copy remains', () => {
    expect(wizardSrc).toContain('defineProps')
    let legacyExists = false
    try {
      readFileSync(resolve(root, 'views/apitest/MockStubDirectWizard.vue'))
      legacyExists = true
    } catch {
      legacyExists = false
    }
    expect(legacyExists, 'old wizard copy under views/apitest must be deleted').toBe(false)
  })

  it('both pages import the same shared component path', () => {
    const shared = `@/${wizardPath}`
    expect(apiTestSrc).toContain(shared)
    expect(docsSrc).toContain(shared)
  })

  it('workbench docs tab hosts the entry action and passes the space down', () => {
    expect(docsSrc).toContain('mock-docs-import-contract')
    expect(docsSrc).toContain(':preset-space-id="spaceId"')
    expect(docsSrc).toContain(':preset-space-name="activeSpace?.name ?? \'\'"')
  })

  it('wizard step 1 offers the two contract sources and uses the upload single door', () => {
    expect(wizardSrc).toContain('stub-direct-source-radio')
    expect(wizardSrc).toContain('sourceExisting')
    expect(wizardSrc).toContain('sourceUpload')
    expect(wizardSrc).toContain('uploadApiSpec')
    expect(wizardSrc).toContain('stub-direct-upload-panel')
  })

  it('preset space collapses the space picker into a read-only echo', () => {
    expect(wizardSrc).toContain('presetSpaceId')
    expect(wizardSrc).toContain('stub-direct-fixed-space')
  })

  it('upload success receipt renders outside the mode-conditional panel', () => {
    // A finished upload auto-jumps back to the existing-contract view, so a
    // receipt left inside the upload panel is unmounted the moment it is set.
    // Structural order pins it after the panel's closing div.
    expect(wizardSrc).toMatch(
      /data-test="stub-direct-upload-error"[\s\S]*?<\/div>[\s\S]*?data-test="stub-direct-upload-done"/,
    )
    // A manual source switch voids the previous receipt.
    expect(wizardSrc).toContain('@change="onSourceModeChange"')
  })
})
