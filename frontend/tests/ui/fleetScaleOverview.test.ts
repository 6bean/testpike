/**
 * T294d fleet elasticity overview contract (frontend .
 *
 * The autoscaler round (core ExecutorPoolScaleOrchestrationService) answers one
 * plan per pool key: the demand snapshot plus the decision taken on it. The
 * resource-pool page is the single surface that shows that answer, so this
 * contract pins three things:
 * - api/cluster.ts mirrors the admin fleet view records field for field
 * (Jackson record component names, no invented members),
 * - ExecutorPoolView renders one elasticity card per plan row straight from
 * the server answer and never re-derives a verdict client-side (red line 6:
 * the decision authority stays in core),
 * - the i18n dual track carries the elasticity family with identical shape,
 * including one label per action value the core enum can emit.
 */
import { describe, expect, it } from 'vitest'
import zhCN from '../../src/utils/locales/zh-CN'
import enUS from '../../src/utils/locales/en-US'
import { lookupLocale as lookup } from '../_localeLookup'
import { apiSrc, viewSrc } from './fleetPageSources'

const ACTIONS = ['SCALE_OUT', 'SCALE_IN', 'HELD', 'COOLDOWN_HELD', 'PAUSED', 'BUDGET_PAUSED']

describe('T294d fleet elasticity overview contract', () => {
  it('api mirror declares the demand/decision/plan trio with every admin field', () => {
    expect(apiSrc).toMatch(/export interface FleetPoolDemand \{/)
    expect(apiSrc).toMatch(/export interface FleetPoolDecision \{/)
    expect(apiSrc).toMatch(/export interface FleetPoolScalePlan \{/)
    for (const key of ['executorType', 'pendingDispatchDepth', 'currentReplicas', 'activeExecutors', 'p95WaitSeconds', 'lastScaleEpochSeconds']) {
      expect(apiSrc, `demand 镜像缺 ${key}`).toContain(`  ${key}:`)
    }
    for (const key of ['action', 'desiredReplicas', 'targetReplicas', 'drainFirst', 'reason']) {
      expect(apiSrc, `decision 镜像缺 ${key}`).toContain(`  ${key}:`)
    }
    // The single read endpoint mirrors admin GET /api/v1/cluster/pools/scale-plan.
    expect(apiSrc).toMatch(/export function getFleetScalePlan\(\)[\s\S]*?\/cluster\/pools\/scale-plan/)
  })

  it('page renders one elasticity card per plan row behind a skeleton and an honest empty state', () => {
    expect(viewSrc).toContain('data-test="fleet-scale-band"')
    expect(viewSrc).toMatch(/v-for="plan in scalePlans"[\s\S]*?data-test="`fleet-scale-card-\$\{plan\.executorType\}`"/)
    // Skeleton while the round is loading, and a stated empty case — never a blank band.
    expect(viewSrc).toMatch(/scaleLoading[\s\S]*?data-test="fleet-scale-skeleton"/)
    expect(viewSrc).toMatch(/data-test="fleet-scale-empty"/)
    // User-facing fields all present: expected vs actual replicas, occupancy bar, health light, elasticity state.
    for (const anchor of ['fleet-scale-desired', 'fleet-scale-actual', 'fleet-scale-occupancy', 'fleet-scale-health', 'fleet-scale-state', 'fleet-scale-reason']) {
      expect(viewSrc, `缺弹性卡锚 ${anchor}`).toContain(anchor)
    }
  })

  it('page is a pure consumer: no client-side verdict recomputation', () => {
    // The action label comes straight from plan.decision.action; occupancy is the
    // server's own active/current ratio rendered as a bar width, and nothing else
    // may compare desired against current or re-classify an action.
    expect(viewSrc).toMatch(/t\(`pool\.elasticAction\.\$\{plan\.decision\.action\}`\)/)
    expect(viewSrc).not.toMatch(/plan\.decision\.desiredReplicas\s*[<>]=?\s*plan\.demand\.currentReplicas/)
    expect(viewSrc).not.toMatch(/if\s*\(plan\.demand\.pendingDispatchDepth\s*>\s*0\)\s*return/)
  })

  it('i18n dual track carries the elasticity family with identical shape and one label per action', () => {
    for (const key of ['elasticTitle', 'elasticEmpty', 'elasticDesired', 'elasticActual', 'elasticOccupancy', 'elasticHealth', 'elasticState', 'elasticReason', 'elasticPending', 'elasticWaitP95']) {
      expect(typeof lookup(zhCN, `pool.${key}`), `zh 缺 pool.${key}`).toBe('string')
      expect(typeof lookup(enUS, `pool.${key}`), `en 缺 pool.${key}`).toBe('string')
      expect((lookup(zhCN, `pool.${key}`) as string).length, `zh pool.${key} 空值`).toBeGreaterThan(0)
    }
    for (const action of ACTIONS) {
      expect(typeof lookup(zhCN, `pool.elasticAction.${action}`), `zh 缺动作 ${action}`).toBe('string')
      expect(typeof lookup(enUS, `pool.elasticAction.${action}`), `en 缺动作 ${action}`).toBe('string')
    }
  })
})
