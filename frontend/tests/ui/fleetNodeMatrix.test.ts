/**
 * T294d fleet node-matrix contract (frontend .
 *
 * The node matrix on the executor-pool page shows one row per reported component
 * version across the fleet. Two disciplines are pinned here:
 * - single source: the ledger comes from api/cluster listClusterComponents (the
 * same data surface the upgrade centre panorama reads) and the tri-colour
 * badge comes from @/utils/verdict — the page must not grow its own
 * supportState mapping or its own ledger fetch (R40: a copy is how drift starts);
 * - honest affordance: the fleet ops (scale N / drain / pause elasticity) sit on
 * the write channel, which this delivery round does not wire. The buttons are
 * rendered disabled with a tooltip that states the current behaviour, and the
 * page issues no write request at all (R25: visible-but-dead control is the
 * defect, honest-disabled is the fallback).
 * The heartbeat column must reuse TimeCell (relative time + absolute tooltip),
 * and the running-task jump only appears for nodes that match a registered
 * executor by name — no match renders the honest dash, never a dead link.
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { viewSrc } from './fleetPageSources'

const MATRIX_KEYS = [
  'matrixFilterPlaceholder',
  'matrixTitle', 'matrixEmpty', 'matrixActionNotWired',
  'matrixActionScaleOut', 'matrixActionDrain', 'matrixActionPause',
  'matrixColNode', 'matrixColFamily', 'matrixColCur', 'matrixColTarget',
  'matrixColState', 'matrixColHeartbeat', 'matrixColJump', 'matrixJump',
]

describe('T294d fleet node-matrix contract', () => {
  it('matrix consumes the shared ledger api and the shared verdict badge source, no second copy', () => {
    expect(viewSrc).toMatch(/listClusterComponents/)
    expect(viewSrc).toMatch(/import \{ verdictTagType, supportStateLabel \} from '@\/utils\/verdict'/)
    expect(viewSrc).toMatch(/TimeCell from '@\/components\/TimeCell\.vue'/)
    // Negative pins: no local re-implementation of the state mapping, no raw
    // supportState comparisons in the page (the verdict is core's, red line 6).
    expect(viewSrc).not.toMatch(/function stateType\(/)
    expect(viewSrc).not.toMatch(/supportState\s*===\s*['"]/)
  })

  it('matrix band renders skeleton, honest empty state, table anchors and the heartbeat cell', () => {
    for (const anchor of ['fleet-matrix-band', 'fleet-matrix-skeleton', 'fleet-matrix-empty',
      'fleet-matrix-state-', 'fleet-matrix-jump-']) {
      expect(viewSrc, `缺节点矩阵锚 ${anchor}`).toContain(anchor)
    }
    // Heartbeat uses TimeCell (relative display, absolute time in tooltip), single source.
    expect(viewSrc).toMatch(/<TimeCell :iso="row\.reportedAt" \/>/)
  })

  it('fleet ops actions are honest-disabled and the page issues no write request', () => {
    for (const anchor of ['fleet-action-scale-out', 'fleet-action-drain', 'fleet-action-pause']) {
      expect(viewSrc, `缺操作面锚 ${anchor}`).toContain(anchor)
    }
    const disabledOps = viewSrc.match(/disabled[^>]*data-test="fleet-action-(scale-out|drain|pause)"/g) ?? []
    expect(disabledOps.length, '操作面三钮必须 disabled 形态').toBe(3)
    // The tooltip names the current behaviour with one shared key, and the page
    // stays a pure reader: no POST anywhere in this view.
    expect(viewSrc).toMatch(/t\('pool\.matrixActionNotWired'\)/)
    expect(viewSrc).not.toMatch(/method: 'post'/)
  })

  it('i18n dual track carries the matrix family with identical shape', () => {
    for (const key of MATRIX_KEYS) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
      expect((lookup(zhCN, `pool.${key}`) as string).length, `zh pool.${key} 空值`).toBeGreaterThan(0)
      expect((lookup(enUS, `pool.${key}`) as string).length, `en pool.${key} 空值`).toBeGreaterThan(0)
    }
  })

  it('matrix band head carries an instant filter wired to the rendered rows (R20 scale safety)', () => {
    expect(viewSrc).toContain('data-test=\"fleet-matrix-filter\"')
    expect(viewSrc).toMatch(/:data="visibleMatrixRows"/)
    expect(viewSrc).toMatch(/const visibleMatrixRows = computed\(/)
  })
})
