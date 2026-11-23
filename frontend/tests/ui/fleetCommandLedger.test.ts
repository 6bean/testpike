/**
 * T294d fleet command-ledger contract (frontend , package D read side).
 *
 * The ledger band on the executor-pool page shows one row per fleet command the
 * protocol plane produced (admin GET /cluster/commands, mirrored from core
 * without re-deriving anything). Three disciplines are pinned:
 * - single source: the rows come from api/cluster listFleetCommands (the admin
 * ClusterCommandView mirror) — the view must not fetch the path itself and
 * must not branch on numeric status values; status label and colour are a
 * display lookup over the core value domain (0..5), exactly as elasticTone
 * does for scale actions (red line 6, R40);
 * - honest absence: lastProgress / resultDetail / claimedBy render the server
 * value or the em-dash placeholder — the page never invents a progress line,
 * a receipt or a claimant for rows the protocol plane has not advanced;
 * - band layout: skeleton / empty / bounded table (max-height viewport like the
 * timeline band), heartbeat time via TimeCell, and the page refresh reloads
 * the ledger together with the other bands so no band shows an older round.
 * R21/R25 shape: the band head carries only its title and reading note — no
 * submit/query control inside the band (this page has no filter conditions, the
 * view-state actions stay in the page action band).
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { viewSrc, apiSrc } from './fleetPageSources'

const COMMAND_KEYS = [
  'commandsFilterPlaceholder',
  'commandsTitle', 'commandsNote', 'commandsEmpty', 'commandsTotal',
  'commandsColTime', 'commandsColType', 'commandsColTarget', 'commandsColStatus',
  'commandsColProgress', 'commandsColReceipt', 'commandsColClaimedBy',
]
const STATUS_VALUES = ['PENDING', 'CLAIMED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'REVOKED']

/** segment of the template belonging to the commands band (up to the watermark band). */
function commandsBand(): string {
  const start = viewSrc.indexOf('data-test="fleet-commands-band"')
  expect(start, '缺 fleet-commands-band 区').toBeGreaterThan(-1)
  const end = viewSrc.indexOf('fleet-watermark-section', start)
  return viewSrc.slice(start, end === -1 ? start + 4000 : end)
}

describe('T294d fleet command-ledger contract', () => {
  it('api mirror carries the admin ClusterCommandView shape field-by-field on one endpoint', () => {
    expect(apiSrc).toMatch(/export interface FleetCommand \{/)
    for (const field of ['id', 'commandType', 'targetType', 'targetId', 'payload',
      'status', 'claimedBy', 'lastProgress', 'resultDetail', 'expiresAt', 'createdAt']) {
      expect(apiSrc, `FleetCommand 缺字段 ${field}`).toMatch(new RegExp(`^\\s+${field}[?]?:`, 'm'))
    }
    expect(apiSrc).toMatch(/url: '\/cluster\/commands',\s*method: 'get'/)
    // The view consumes only the mirrored api function — no second fetch channel.
    expect(viewSrc).toMatch(/listFleetCommands/)
    expect(viewSrc).not.toMatch(/cluster\/commands/)
  })

  it('ledger band renders skeleton, honest empty, bounded table and TimeCell heart', () => {
    const band = commandsBand()
    for (const anchor of ['fleet-commands-skeleton', 'fleet-commands-empty',
      'fleet-commands-table', 'fleet-command-status-']) {
      expect(band, `缺命令台账锚 ${anchor}`).toContain(anchor)
    }
    expect(band).toMatch(/:max-height="480"/)
    expect(band).toMatch(/<TimeCell :iso="row\.createdAt" \/>/)
    // Honest absence: optional protocol fields fall back to the dash, never a
    // fabricated progress line / receipt / claimant.
    expect(band).toMatch(/row\.lastProgress\?\.message/)
    expect(band).toMatch(/row\.resultDetail/)
    expect(band).toMatch(/row\.claimedBy/)
  })

  it('status is a display lookup over the whole core value domain, no page verdict', () => {
    const band = commandsBand()
    expect(band).toMatch(/commandStatusLabel\(row\.status\)/)
    expect(band).toMatch(/commandStatusTone\(row\.status\)/)
    // Negative pins (red line 6): the page never compares numeric statuses to
    // derive a verdict or an extra state the server did not report.
    expect(viewSrc).not.toMatch(/status === \d/)
    expect(viewSrc).not.toMatch(/status >= \d|status <= \d/)
  })

  it('i18n dual track carries the ledger family, status domain and target labels', () => {
    for (const key of COMMAND_KEYS) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
    }
    for (const value of STATUS_VALUES) {
      expect(typeof lookup(zhCN, `pool.commandsStatus.${value}`), `zh 缺状态值 ${value}`).toBe('string')
      expect(typeof lookup(enUS, `pool.commandsStatus.${value}`), `en 缺状态值 ${value}`).toBe('string')
    }
    for (const target of ['executor', 'enroll-token']) {
      expect(typeof lookup(zhCN, `pool.commandsTarget.${target}`), `zh 缺目标型 ${target}`).toBe('string')
      expect(typeof lookup(enUS, `pool.commandsTarget.${target}`), `en 缺目标型 ${target}`).toBe('string')
    }
  })

  it('band head shows the honest total row count from the same rows source', () => {
    // The core list endpoint answers without LIMIT, so the total equals the
    // rows already loaded — one counter, no second fetch channel (R40).
    const band = commandsBand()
    expect(band).toMatch(/data-test="fleet-commands-total"/)
    expect(band).toMatch(/t\('pool\.commandsTotal',\s*\{\s*n:\s*commandRows\.length\s*\}\)/)
    expect(lookup(zhCN, 'pool.commandsTotal')).toContain('{n}')
    expect(lookup(enUS, 'pool.commandsTotal')).toContain('{n}')
  })

  it('page refresh reloads the ledger together with the sibling bands (R25 no stale band)', () => {
    expect(viewSrc).toMatch(/const commands = loadCommands\(\)/)
    expect(viewSrc).toMatch(/Promise\.all\(\[[^\]]*commands[^\]]*\]\)/)
  })


  it('command band head carries one instant filter and no query submit control (R21)', () => {
    const band = commandsBand()
    expect(band).toMatch(/data-test="fleet-commands-filter"/)
    expect(band).not.toMatch(/type="primary"/)
    expect(band).not.toMatch(/el-select/)
    const inputs = band.match(/el-input/g) ?? []
    expect(inputs.length, 'command band head carries exactly one instant filter input').toBe(1)
  })
})
