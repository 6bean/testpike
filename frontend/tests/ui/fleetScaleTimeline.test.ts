/**
 * T294d fleet scale-event timeline contract (frontend .
 *
 * The timeline band renders the newest-first window of recorded autoscale actions
 * (core ledger -> admin bridge -> this page). Pinned disciplines:
 * - api mirror: getFleetScaleEvents binds GET /cluster/pools/scale-events with
 * optional poolKey/limit params and the FleetScaleEvent interface carries the
 * admin ExecutorPoolScaleEventView field names verbatim, so the envelope binds
 * without a second mapping layer;
 * - honest empty state: the apply channel is not wired yet, so a zero-row ledger
 * is a designed answer — the band must show its stated empty line, never fake
 * rows and never a silent blank;
 * - single source: the event instant renders through TimeCell, the action label
 * reuses the pool.elasticAction value domain and the badge tone reuses the one
 * elasticTone mapping already on the page — the timeline must not grow its own
 * verdict mapping (R40: a copy is how drift starts);
 * - one refresh command: the timeline loads in parallel with the pool list, the
 * elasticity round and the node matrix, so no band can show an older round.
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { apiSrc, viewSrc } from './fleetPageSources'

const TIMELINE_KEYS = [
  'timelineFilterPlaceholder', 'timelineWindowAll', 'timelineShowAll',
  'timelineTitle', 'timelineEmpty',
  'timelineColTime', 'timelineColPool', 'timelineColAction',
  'timelineColChange', 'timelineColReason', 'timelineWindow',
]

describe('T294d fleet scale-event timeline contract', () => {
  it('api mirrors the admin scale-event view and binds the bridge endpoint with optional params', () => {
    expect(apiSrc).toMatch(/export interface FleetScaleEvent \{/)
    for (const field of ['poolKey', 'action', 'desiredReplicas', 'currentReplicas', 'reason', 'createdEpochSeconds']) {
      expect(apiSrc, `FleetScaleEvent 缺字段 ${field}`).toMatch(new RegExp(`\\b${field}[?]?:`))
    }
    expect(apiSrc).toMatch(/export function getFleetScaleEvents\(/)
    expect(apiSrc).toMatch(/url: '\/cluster\/pools\/scale-events'/)
    expect(apiSrc).toMatch(/poolKey/)
    expect(apiSrc).toMatch(/limit/)
  })

  it('timeline band renders skeleton, honest empty state and the five anchored columns', () => {
    for (const anchor of ['fleet-timeline-band', 'fleet-timeline-skeleton', 'fleet-timeline-empty', 'fleet-timeline-table']) {
      expect(viewSrc, `缺时间线锚 ${anchor}`).toContain(anchor)
    }
    // The instant column goes through TimeCell (relative display, absolute tooltip).
    expect(viewSrc).toMatch(/<TimeCell :iso="eventIso\(row\.createdEpochSeconds\)"/)
    // The reason sentence is taken from the ledger as-is; no client re-write.
    expect(viewSrc).toMatch(/\{\{ row\.reason/)
  })

  it('timeline reuses the shared action tone and value domain, no second verdict mapping', () => {
    // Exactly one tone mapping exists in the whole view (owns it).
    const toneDefs = viewSrc.match(/function elasticTone\(/g) ?? []
    expect(toneDefs.length, '动作色彩映射必须单源一处').toBe(1)
    // The action label consumes the pool.elasticAction keys, not a copied table.
    expect(viewSrc).toMatch(/pool\.elasticAction\./)
    expect(viewSrc).not.toMatch(/function timelineActionText\(/)
    // The page stays a pure reader: no write request anywhere in this view.
    expect(viewSrc).not.toMatch(/method: 'post'/)
  })

  it('refresh reloads the timeline together with the other read bands', () => {
    expect(viewSrc).toMatch(/async function loadTimeline\(/)
    expect(viewSrc).toMatch(/const timeline = loadTimeline\(\)/)
    expect(viewSrc).toMatch(/Promise\.all\(\[scale, matrix, timeline, commands, watermark\]\)/)
  })

  it('i18n dual track carries the timeline family with identical shape', () => {
    for (const key of TIMELINE_KEYS) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
      expect((lookup(zhCN, `pool.${key}`) as string).length, `zh pool.${key} 空值`).toBeGreaterThan(0)
      expect((lookup(enUS, `pool.${key}`) as string).length, `en pool.${key} 空值`).toBeGreaterThan(0)
    }
  })

  it('timeline band head carries instant filter and a show-all entry on the existing limit param', () => {
    expect(viewSrc).toContain('data-test=\"fleet-timeline-filter\"')
    expect(viewSrc).toContain('data-test=\"fleet-timeline-show-all\"')
    expect(viewSrc).toMatch(/:data="visibleScaleEvents"/)
    expect(viewSrc).toMatch(/getFleetScaleEvents\(undefined, timelineAll\.value \? 200 : undefined\)/)
  })

  it('timeline rows expose action-token and change anchors so the E2E can pin direction parity', () => {
    expect(viewSrc).toContain('fleet-timeline-action-${row.action}-${$index}')
    expect(viewSrc).toContain('fleet-timeline-change-${$index}')
  })
})
