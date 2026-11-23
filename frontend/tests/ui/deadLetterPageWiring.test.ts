/**
 * Dead-letter ops page wiring contract .
 * Mirrors the api-token/mock/role column-config precedent: the page consumes
 * the shared useColumnConfig piece (no second implementation), the read doors
 * are exactly the three admin endpoints, backlog basis stays honest (ledger
 * status count, never Rabbit depth) and the ENTITY_GONE replay ban renders
 * from the core enum literal without re-deriving classification client-side.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const page = readFileSync(new URL('../../src/views/system/DeadLetterQueueView.vue', import.meta.url), 'utf8')
const api = readFileSync(new URL('../../src/api/deadletter.ts', import.meta.url), 'utf8')

describe('dead-letter page wiring', () => {
  it('api mirror covers the three read doors and the four write doors of the admin controller', () => {
    expect(api).toContain("url: '/mq-dead-letter/queue-backlog'")
    expect(api).toContain("url: '/mq-dead-letter/class-distribution'")
    expect(api).toContain("url: '/mq-dead-letter/browse'")
    // read side stays GET-only, the replay/void surface is POST-only by contract
    expect(api).toContain("url: '/mq-dead-letter/replay/one'")
    expect(api).toContain("url: '/mq-dead-letter/replay/by-class'")
    expect(api).toContain("url: '/mq-dead-letter/replay/queue'")
    expect(api).toContain("url: '/mq-dead-letter/void'")
    expect(api).not.toContain("method: 'put'")
    expect(api).not.toContain("method: 'delete'")
    // exactly the four write doors emit POST — no hidden fifth write
    expect((api.match(/method: 'post'/g) ?? []).length).toBe(4)
  })

  it('failure class and status literals mirror the core enums once', () => {
    for (const cls of ['ENTITY_GONE', 'PROVIDER_TRANSIENT', 'CONFIG_MISSING', 'LLM_TIMEOUT', 'DESERIALIZATION_FAIL', 'UNKNOWN']) {
      expect(api).toContain(`'${cls}'`)
    }
    for (const st of ['pending', 'replayed', 'voided']) {
      expect(api).toContain(`'${st}'`)
    }
    // the page never re-encodes the replay ban: it references the exported constant
    expect(page).toContain('DEAD_LETTER_REPLAY_FORBIDDEN_CLASS')
  })

  it('consumes the shared column-config piece with a stable view key', () => {
    expect(page).toContain("import { useColumnConfig, type ColumnDef } from '@/composables/useColumnConfig'")
    expect(page).toContain("const colCfg = useColumnConfig('dead-letter-browse', BROWSE_COLUMNS)")
    expect(page).toContain('v-for="col in colCfg.visibleColumns.value"')
    expect(page).toContain(':size="colCfg.state.density"')
    expect(page).toContain('`dead-letter-col-${col.key}`')
  })

  it('keeps the backlog basis honest and the halo band on theme tokens', () => {
    expect(page).toContain("t('deadLetter.basisNote')")
    expect(page).toContain('dead-letter-backlog-halo')
    expect(page).toContain('var(--tp-danger-weak)')
  })

  it('rides the shared bands: query inline at the filter tail, view-state actions in the trailing slot', () => {
    expect(page).toContain('class="filter-bar"')
    expect(page).toContain('class="filter-bar-actions"')
    // R21 second fix: the submit button sits inline before the trailing slot, never inside it
    const actionsIdx = page.indexOf('filter-bar-actions')
    const queryIdx = page.indexOf('dead-letter-query')
    expect(queryIdx).toBeGreaterThan(-1)
    expect(queryIdx).toBeLessThan(actionsIdx)
    // R23: no page-name band inside the card — the name rides the global top bar
    expect(page).not.toContain('<h2')
  })

  it('E2E anchors exist for the acceptance spectrum (grouping/class/browse/pagination)', () => {
    for (const anchor of [
      'dead-letter-page',
      'dead-letter-queue-table',
      'dead-letter-class-row',
      'dead-letter-table',
      'dead-letter-pagination',
      'dead-letter-status-filter',
      'dead-letter-queue-filter',
      'dead-letter-class-filter',
      'dead-letter-payload-open',
    ]) {
      expect(page).toContain(`data-test="${anchor}"`)
    }
  })
})
