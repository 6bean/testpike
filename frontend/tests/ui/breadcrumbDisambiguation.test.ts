import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Contract: every breadcrumb rung on the mock space pages reads as a distinct,
 * human name and links only to real addresses.
 *
 * Why: the space workbench route reused the parent menu leaf's title key, so
 * the topbar showed the same words on two rungs side by side, and a page whose
 * parent is not a menu leaf fell back to the bare route identifier on screen.
 * The templated parent path was additionally rendered as a clickable link to
 * an address containing a ':' placeholder that does not exist — a dead end
 * dressed as navigation.
 *
 * Second half: the docs tab empty state inherited the shared component's
 * roomy full-page frame while sitting between two sections, so the band read
 * as dead space. The paddings are tightened in the docs page scope and the
 * stray vertical margins (14/18) join the registered 8/12/16 rhythm.
 */

const ROOT = resolve(__dirname, '../..')

function source(rel: string): string {
  return readFileSync(resolve(ROOT, rel), 'utf8')
}

const routes = source('src/router/routes.ts')
const layout = source('src/layouts/MainLayout.vue')
const workbench = source('src/views/mock/MockSpaceWorkbenchView.vue')
const docs = source('src/views/mock/MockDocsView.vue')
const zh = source('src/utils/locales/zh-CN.ts')
const en = source('src/utils/locales/en-US.ts')

/** The meta object declared right after a named route entry. */
function metaOfRoute(name: string): string {
  const idx = routes.indexOf(`name: '${name}'`)
  if (idx < 0) throw new Error(`route ${name} not found`)
  const metaIdx = routes.indexOf('meta:', idx)
  const end = routes.indexOf('}', metaIdx)
  return routes.slice(metaIdx, end)
}

describe('breadcrumb rungs are distinct human names', () => {
  it('the workbench route carries its own title key, not the parent leaf key', () => {
    const meta = metaOfRoute('MockSpaceWorkbench')
    expect(meta).toMatch(/titleKey: 'layout\.mockSpaceWorkbench'/)
    expect(meta).not.toMatch(/titleKey: 'layout\.mockSpaces'/)
  })

  it('both locales ship the new title key', () => {
    expect(zh).toMatch(/mockSpaceWorkbench: '[^']+'/)
    expect(en).toMatch(/mockSpaceWorkbench: '[^']+'/)
  })

  it('a parent that is not a menu leaf resolves through the route table, never the bare identifier', () => {
    expect(layout).not.toContain("String(route.name ?? '')")
    expect(layout).toMatch(/router\.getRoutes\(\)/)
  })

  it('a templated parent path renders as plain text, not a dead link', () => {
    expect(layout).toMatch(/includes\(':'\)/)
  })

  it('the workbench names the current rung after the opened space and clears it on leave', () => {
    expect(workbench).toMatch(/setBreadcrumbTitle\(\s*'MockSpaceWorkbench',\s*mockSpaceLabel/)
    expect(workbench).toMatch(/setBreadcrumbTitle\('MockSpaceWorkbench',\s*null\)/)
  })
})

describe('docs tab empty state keeps its band tight', () => {
  it('the old wide paddings are gone inside the docs page scope', () => {
    expect(docs).not.toMatch(/\.empty-block\s*\{\s*padding:\s*24px 0/)
    expect(docs).toMatch(/\.empty-block :deep\(\.empty-state\)/)
  })

  it('vertical rhythm uses the registered scale, stray 14/18 margins removed', () => {
    expect(docs).not.toMatch(/margin-bottom:\s*14px/)
    expect(docs).not.toMatch(/margin-top:\s*18px/)
  })
})
