/**
 * Prompt-template family-first-screen contract .
 * The management page must open on the family enumeration ("which families
 * exist") served by the admin-bridge GET /prompt-templates/families endpoint,
 * instead of demanding a hand-typed name+domain pair on an empty screen.
 * - the api mirror exposes listPromptTemplateFamilies with the seven-field
 * family item (core PromptTemplateFamilyItem double-mirror precedent);
 * - the view renders the family table as the first pane, with a per-row
 * action entering the existing within-family version view;
 * is gone — the first screen comes from live enumeration only;
 * - R25 scope-aware single control: each pane carries its own useColumnConfig
 * viewKey and the page-level ColumnSettings panel follows the active pane;
 * - every family-column header key is registered in both locales (no
 * hardcoded header text, locale single source).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const api = readFileSync(
  new URL('../../src/api/promptTemplate.ts', import.meta.url),
  'utf8',
)
const page = readFileSync(
  new URL('../../src/views/prompt/PromptTemplateListView.vue', import.meta.url),
  'utf8',
)
const zh = readFileSync(new URL('../../src/utils/locales/zh-CN.ts', import.meta.url), 'utf8')
const en = readFileSync(new URL('../../src/utils/locales/en-US.ts', import.meta.url), 'utf8')

function promptTplBlock(src: string): string {
  const start = src.indexOf('\n  promptTpl: {')
  expect(start).toBeGreaterThan(-1)
  const end = src.indexOf('\n  }', start + 1)
  expect(end).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('prompt-template family first screen', () => {
  it('mirrors the seven-field family item and its list endpoint', () => {
    for (const field of ['domain', 'name', 'latestId', 'latestVersion', 'latestStatus', 'versionCount', 'updatedAt']) {
      expect(api).toContain(field)
    }
    expect(api).toContain('PromptTemplateFamilyItem')
    expect(api).toContain('export function listPromptTemplateFamilies')
    expect(api).toContain("url: '/prompt-templates/families'")
  })

  it('consumes the family enumeration and renders the family pane first', () => {
    expect(page).toContain('listPromptTemplateFamilies')
    expect(page).toContain('data-test="prompt-tpl-family-table"')
    expect(page).toContain('data-test="prompt-tpl-open-family"')
    // back action returns from the version pane to the family pane
    expect(page).toContain('data-test="prompt-tpl-back-family"')
  })

  it('drops the hardcoded seed family from onMounted', () => {
    expect(page).not.toContain("query.name = 'case-gen-prompt'")
    expect(page).not.toContain("query.domain = '\\u7528\\u4f8b\\u751f\\u6210'")
    expect(page).not.toContain('用例生成」')
    // first screen loads the live enumeration instead
    expect(page).toContain('loadFamilies()')
  })

  it('keeps one scope-aware column-settings control with a per-pane viewKey', () => {
    expect(page).toContain("useColumnConfig('prompt-template-family'")
    expect(page).toContain("useColumnConfig('prompt-template', PROMPT_TEMPLATE_COLUMNS)")
    // the settings panel follows the active pane (R25 mode ①)
    expect(page).toContain('activeColCfg')
  })

  it('registers the family header keys in both locales', () => {
    for (const loc of [zh, en]) {
      const block = promptTplBlock(loc)
      for (const key of ['colLatestVersion', 'colVersionCount', 'colUpdatedAt', 'openFamilyBtn', 'backFamilyBtn']) {
        expect(block).toContain(`${key}:`)
      }
    }
  })
})
