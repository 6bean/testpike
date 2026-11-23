/**
 * 2 (step-editor shared-asset overlay) front-end pure-logic L2.
 *
 * Semantic source (single-source red line, no invented shapes):
 * - catalog rows come from GET /shared-assets (published heads only, core
 * resolves the version chain; this module must stay passive and never
 * re-resolve chains — R40);
 * - per-kind payload shapes are the publish contract:
 * case_fragment = step-array snapshot, action_component = single step
 * object, login_recipe / gen_function = text payloads inserted verbatim
 * into the current field;
 * - tree insertion reuses the shared insertStepsAtSelection kernel in
 * StepTree.vue; this module only converts a row into an insert descriptor.
 * The live component surface (el-select overlay, lazy loading, pin call)
 * stays with E2E (strict-testing layering).
 */
import { describe, expect, it } from 'vitest'
import {
  assetInsertOf,
  buildAssetGroups,
  isStepKind,
  type AssetRowLike,
} from '@/utils/assetRef'

const row = (over: Partial<AssetRowLike>): AssetRowLike => ({
  id: 1,
  kind: 'case_fragment',
  name: 'login-flow-fragment',
  ownerProjectName: 'Provider Project',
  assetVersion: 2,
  status: 1,
  head: true,
  payloadJson: '[]',
  ...over,
})

describe('isStepKind: step-tree kinds vs text-field kinds', () => {
  it('case_fragment and action_component land as steps', () => {
    expect(isStepKind('case_fragment')).toBe(true)
    expect(isStepKind('action_component')).toBe(true)
  })
  it('login_recipe and gen_function insert as text', () => {
    expect(isStepKind('login_recipe')).toBe(false)
    expect(isStepKind('gen_function')).toBe(false)
  })
  it('unknown kind is not a step kind (fail-closed, never silently inserted)', () => {
    expect(isStepKind('mystery')).toBe(false)
  })
})

describe('assetInsertOf: payload snapshot -> insert descriptor', () => {
  it('case_fragment valid step array passes the steps through untouched', () => {
    const steps = [
      { action: 'navigate', url: 'https://a.test' },
      { action: 'click', selector: '#go' },
    ]
    const ins = assetInsertOf(row({ payloadJson: JSON.stringify(steps) }))
    expect(ins.mode).toBe('steps')
    expect(ins.mode === 'steps' && ins.steps).toEqual(steps)
  })
  it('case_fragment array element without an action verb is invalid', () => {
    const ins = assetInsertOf(row({ payloadJson: '[{"url":"x"}]' }))
    expect(ins.mode).toBe('invalid')
  })
  it('case_fragment unparsable payload is invalid (never a half-built insert)', () => {
    expect(assetInsertOf(row({ payloadJson: 'not json' })).mode).toBe('invalid')
    expect(assetInsertOf(row({ payloadJson: '{"action":"click"}' })).mode).toBe('invalid')
  })
  it('action_component single step object becomes a one-step insert', () => {
    const ins = assetInsertOf(row({
      kind: 'action_component',
      payloadJson: '{"action":"input","selector":"#u","value":"bob"}',
    }))
    expect(ins.mode === 'steps' && ins.steps).toEqual([
      { action: 'input', selector: '#u', value: 'bob' },
    ])
  })
  it('action_component missing the action verb is invalid', () => {
    expect(assetInsertOf(row({ kind: 'action_component', payloadJson: '{"selector":"#u"}' })).mode)
      .toBe('invalid')
  })
  it('login_recipe inserts the snapshot text verbatim (no client-side reshaping)', () => {
    const payload = '{"label":"t1","assemblies":[{"into":"env","name":"K","value":"V"}]}'
    const ins = assetInsertOf(row({ kind: 'login_recipe', payloadJson: payload }))
    expect(ins.mode === 'text' && ins.text).toBe(payload)
  })
  it('gen_function unwraps a content snapshot and falls back to verbatim text', () => {
    const ins = assetInsertOf(row({ kind: 'gen_function', payloadJson: '{"type":1,"content":"1000 rows"}' }))
    expect(ins.mode === 'text' && ins.text).toBe('1000 rows')
    const raw = assetInsertOf(row({ kind: 'gen_function', payloadJson: 'plain script text' }))
    expect(raw.mode === 'text' && raw.text).toBe('plain script text')
  })
  it('blank payload is invalid for every kind', () => {
    for (const kind of ['case_fragment', 'action_component', 'login_recipe', 'gen_function']) {
      expect(assetInsertOf(row({ kind, payloadJson: '   ' })).mode).toBe('invalid')
    }
  })
})

describe('buildAssetGroups: catalog rows -> grouped overlay candidates', () => {
  it('groups by kind in first-seen order and keeps row order inside a group', () => {
    const groups = buildAssetGroups([
      row({ id: 10, kind: 'case_fragment', name: 'frag-a' }),
      row({ id: 11, kind: 'gen_function', name: 'gen-a', payloadJson: '{"content":"c"}' }),
      row({ id: 12, kind: 'case_fragment', name: 'frag-b' }),
    ])
    expect(groups.map((g) => g.kind)).toEqual(['case_fragment', 'gen_function'])
    expect(groups[0].options.map((o) => o.name)).toEqual(['frag-a', 'frag-b'])
    expect(groups[0].options[0]).toMatchObject({
      assetRowId: 10,
      ownerProjectName: 'Provider Project',
      assetVersion: 2,
    })
    expect(groups[0].options[0].insert.mode).toBe('steps')
    expect(groups[1].options[0].insert).toEqual({ mode: 'text', text: 'c' })
  })
  it('superseded or withdrawn rows never surface (published heads only)', () => {
    const groups = buildAssetGroups([
      row({ id: 20, head: false }),
      row({ id: 21, status: 2 }),
      row({ id: 22 }),
    ])
    expect(groups).toHaveLength(1)
    expect(groups[0].options.map((o) => o.assetRowId)).toEqual([22])
  })
  it('invalid payload rows keep a visible option with an invalid descriptor (honest empty, not hidden)', () => {
    const groups = buildAssetGroups([row({ id: 30, payloadJson: 'broken' })])
    expect(groups[0].options).toHaveLength(1)
    expect(groups[0].options[0].insert.mode).toBe('invalid')
  })
  it('empty catalog yields no placeholder groups', () => {
    expect(buildAssetGroups([])).toEqual([])
  })
})
