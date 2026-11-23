/**
 * Referencing-case list contract (MCP tail package 1, frontend half):
 * - the api mirror addresses the admin door
 *   /web-elements/{id}/referencing-cases with bounded page/pageSize params,
 *   name-for-name against the controller mapping (no second URL source);
 * - the anchors dialog renders the bounded list and the old placeholder
 *   alert is fully gone (no leftover data-test, no leftover locale key);
 * - the zh/en locale key sets for webElement.refs are symmetric, and the
 *   load-failure copy names a retry path the user can actually follow (R63).
 *
 * Mechanism: static source reads + regex extraction, browser-free.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const apiSrc = readFileSync(resolve(here, '../../src/api/webElements.ts'), 'utf-8')
const viewSrc = readFileSync(
  resolve(here, '../../src/views/webelement/WebElementLedgerView.vue'),
  'utf-8',
)
const zhSrc = readFileSync(resolve(here, '../../src/utils/locales/zh-CN.ts'), 'utf-8')
const enSrc = readFileSync(resolve(here, '../../src/utils/locales/en-US.ts'), 'utf-8')
const controllerSrc = readFileSync(
  resolve(
    here,
    '../../../admin/src/main/java/com/sixbean/testpike/admin/webelement/api/controller/WebElementReferencingCasesController.java',
  ),
  'utf-8',
)

/** Extract the key names of one nested locale object (refs: { ... }) by brace scan. */
function refsLocaleKeys(src: string): string[] {
  const start = src.indexOf("anchorsTitle:")
  const refsAt = src.indexOf('refs: {', start)
  expect(refsAt).toBeGreaterThan(-1)
  let depth = 0
  let end = refsAt
  for (let i = refsAt + 'refs: '.length; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') {
      depth--
      if (depth === 0) {
        end = i
        break
      }
    }
  }
  const block = src.slice(refsAt, end)
  return [...block.matchAll(/^\s{6}(\w+):/gm)].map((m) => m[1])
}

describe('referencing-case read door (element anchors dialog)', () => {
  it('api mirror addresses the admin door with a bounded page', () => {
    expect(apiSrc).toContain('webElementReferencingCasesByElement')
    expect(apiSrc).toMatch(
      /url: `\/web-elements\/\$\{id\}\/referencing-cases`, method: 'get', params: \{ page, pageSize \}/,
    )
    const mapping = controllerSrc.match(/@GetMapping\("([^"]+)"\)/)
    expect(mapping).not.toBeNull()
    expect(mapping![1]).toBe('/api/v1/web-elements/{id}/referencing-cases')
  })

  it('dialog renders the bounded list and the placeholder alert is gone', () => {
    expect(viewSrc).toContain('data-test="ledger-refs-table"')
    expect(viewSrc).toContain('data-test="ledger-refs-pager"')
    expect(viewSrc).not.toContain('ledger-refs-placeholder')
    expect(viewSrc).not.toContain('refs-alert')
    // drawer loads page 1 on open and pages via the same bounded reader
    expect(viewSrc).toMatch(/void loadReferencingCases\(1\)/)
    expect(viewSrc).toMatch(/@current-change="loadReferencingCases"/)
    // no drawer-side counters: the page total comes from the server answer
    expect(viewSrc).toMatch(/refs\.total = data\.total/)
  })

  it('locale refs family is symmetric and the retired placeholder is fully removed', () => {
    const zh = refsLocaleKeys(zhSrc)
    const en = refsLocaleKeys(enSrc)
    expect(zh.length).toBeGreaterThan(0)
    expect(new Set(zh)).toEqual(new Set(en))
    for (const src of [zhSrc, enSrc, apiSrc, viewSrc]) {
      expect(src).not.toContain('refsPlaceholder')
    }
  })

  it('load-failure copy names a followable retry entry (R63)', () => {
    // the retry path literal must appear inside the refs.loadFailed value,
    // not somewhere else in the file (placeholder text would pass a loose scan)
    const zhFailed = zhSrc.slice(zhSrc.indexOf('refs: {')).split('\n')[8]
    const enFailed = enSrc.slice(enSrc.indexOf('refs: {')).split('\n')[8]
    expect(zhFailed).toContain('loadFailed')
    expect(zhFailed).toContain('编辑锚')
    expect(enFailed).toContain('loadFailed')
    expect(enFailed).toContain('Edit anchors')
  })
})
