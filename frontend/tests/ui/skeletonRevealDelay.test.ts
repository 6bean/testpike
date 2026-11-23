// Contract guard for the skeleton flash suppression (, user screenshot
// report 2026-11-04): the shared TableSkeleton must stay undrawn until the
// in-flight delay named by the theme token has elapsed, and the threshold
// must live in exactly one place — theme.scss :root plus this component.
// Source-on-disk assertions per tests/ui convention (same shape as the
// empty-flash guard it complements).
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SKELETON = readFileSync(join(__dirname, '../../src/components/TableSkeleton.vue'), 'utf-8')
const THEME = readFileSync(join(__dirname, '../../src/styles/theme.scss'), 'utf-8')
const SHELL = readFileSync(join(__dirname, '../../src/components/DataTablePage.vue'), 'utf-8')
const KEY = '--ds-skeleton-reveal-delay-ms'

function collectVueFiles(dir: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...collectVueFiles(p))
    else if (name.endsWith('.vue') || name.endsWith('.ts')) out.push(p)
  }
  return out
}

describe('skeleton reveal delay single sources', () => {
  it('theme defines the named hot key with the shipped default', () => {
    const re = new RegExp(`${KEY.replace(/[-]/g, '\\-')}:\\s*250;`)
    expect(THEME).toMatch(re)
  })

  it('shared component gates its own paint on the named key', () => {
    expect(SKELETON).toContain(`"${KEY}"`)
    expect(SKELETON).toMatch(/getPropertyValue\(REVEAL_DELAY_KEY\)/)
    // the wrapper renders nothing until the reveal timer fires
    expect(SKELETON).toMatch(/<div v-if="shown" class="table-skeleton"/)
    expect(SKELETON).toMatch(/setTimeout\(/)
    // and the pending timer is cancelled on unmount (no late paints)
    expect(SKELETON).toMatch(/clearTimeout\(revealTimer\)/)
    expect(SKELETON).toMatch(/onBeforeUnmount\(/)
  })

  it('no page-level skeleton-delay patches anywhere outside the single sources', () => {
    const hits = collectVueFiles(join(__dirname, '../../src'))
      .filter((f) => !f.endsWith('TableSkeleton.vue') && !f.endsWith('theme.scss'))
      .filter((f) => readFileSync(f, 'utf-8').includes(KEY))
    expect(hits, `page-level overrides found: ${hits.join(', ')}`).toEqual([])
  })

  it('the shell still routes its in-flight empty slot through the shared gate', () => {
    expect(SHELL).toMatch(/<TableSkeleton v-if="loading"/)
  })
})
