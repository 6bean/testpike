/**
 * Unit contract for the version-compare model builder .
 * Pure logic: joins two ApiSpecView endpoint snapshots by endpointKey into the
 * added/changed/removed triple and parses schema strings into field rows for
 * the side-by-side panel. Categories prefer the core diffSummary ledger when
 * the picked pair is directly adjacent (next.prevSpecId === prev.id); any
 * non-adjacent pair is derived by key set + structureHash comparison — a
 * display helper over the two snapshots, never a second authoritative ledger.
 * Schema parse failures degrade to a raw-text fold ({ ok: false, raw }) so the
 * UI can label the row honestly instead of dumping unparsed JSON.
 */
import { describe, expect, it } from 'vitest'

import {
  buildVersionCompare,
  parseSchemaFields,
  type CompareModel,
} from '@/utils/specCompare'
import type { ApiEndpointView, ApiSpecView } from '@/api/apiSpec'

function ep(over: Partial<ApiEndpointView> & { endpointKey: string }): ApiEndpointView {
  return {
    method: over.endpointKey.split(' ')[0],
    path: over.endpointKey.split(' ')[1],
    operationId: null,
    title: '',
    parameters: [],
    requestContentType: null,
    requestSchema: null,
    responseSchemas: {},
    securityRefs: [],
    structureHash: 'h-' + over.endpointKey,
    ...over,
  }
}

function spec(over: Partial<ApiSpecView> & { id: number; endpoints: ApiEndpointView[] }): ApiSpecView {
  return {
    projectId: 1,
    documentId: null,
    name: 'demo',
    specFormat: 'openapi',
    specTitle: null,
    contentSha256: 'x',
    specVersion: 1,
    prevSpecId: null,
    diffSummary: null,
    createdBy: null,
    createdAt: '2026-01-01 00:00:00',
    ...over,
  }
}

describe('parseSchemaFields', () => {
  it('returns null for absent schema (no request body)', () => {
    expect(parseSchemaFields(null)).toBeNull()
    expect(parseSchemaFields('')).toBeNull()
  })

  it('extracts field name/type/required rows from an object schema', () => {
    const json = JSON.stringify({
      type: 'object',
      properties: {
        username: { type: 'string' },
        age: { type: 'integer' },
      },
      required: ['username'],
    })
    const res = parseSchemaFields(json)
    expect(res).not.toBeNull()
    expect(res!.ok).toBe(true)
    if (res!.ok) {
      expect(res!.fields).toEqual([
        { name: 'username', type: 'string', required: true },
        { name: 'age', type: 'integer', required: false },
      ])
    }
  })

  it('falls back to top-level scalar type for primitive schemas', () => {
    const res = parseSchemaFields(JSON.stringify({ type: 'string' }))
    expect(res!.ok).toBe(true)
    if (res!.ok) expect(res!.fields).toEqual([{ name: '(body)', type: 'string', required: false }])
  })

  it('degrades honestly on unparsable payloads (ok=false with raw text)', () => {
    const res = parseSchemaFields('{not json')
    expect(res).not.toBeNull()
    expect(res!.ok).toBe(false)
    if (!res!.ok) expect(res!.raw).toBe('{not json')
  })
})

describe('buildVersionCompare', () => {
  const a = ep({ endpointKey: 'GET /a' })
  const b = ep({ endpointKey: 'POST /b' })
  const cOld = ep({ endpointKey: 'PUT /c', title: 'old title' })
  const cNew = ep({ endpointKey: 'PUT /c', title: 'new title', structureHash: 'h-different' })

  it('uses the core diffSummary ledger verbatim for adjacent versions', () => {
    const prev = spec({ id: 10, specVersion: 1, endpoints: [a, b, cOld] })
    const next = spec({
      id: 11,
      specVersion: 2,
      prevSpecId: 10,
      endpoints: [b, cNew],
      diffSummary: { added: ['DELETE /d'], changed: ['PUT /c'], removed: ['GET /a'] },
    })
    const m: CompareModel = buildVersionCompare(prev, next)
    // ledger keys win even though DELETE /d has no snapshot row on either side
    expect(m.added.map((r) => r.endpointKey)).toEqual(['DELETE /d'])
    expect(m.removed.map((r) => r.endpointKey)).toEqual(['GET /a'])
    expect(m.changed.map((r) => r.endpointKey)).toEqual(['PUT /c'])
    expect(m.source).toBe('core-ledger')
  })

  it('derives added/removed by key set and changed by structureHash for non-adjacent pairs', () => {
    const prev = spec({ id: 10, specVersion: 1, endpoints: [a, cOld] })
    const next = spec({ id: 12, specVersion: 3, prevSpecId: 11, endpoints: [b, cNew] })
    const m = buildVersionCompare(prev, next)
    expect(m.added.map((r) => r.endpointKey)).toEqual(['POST /b'])
    expect(m.removed.map((r) => r.endpointKey)).toEqual(['GET /a'])
    expect(m.changed.map((r) => r.endpointKey)).toEqual(['PUT /c'])
    expect(m.source).toBe('snapshot-join')
  })

  it('same structureHash on both sides counts as unchanged (not changed)', () => {
    const same = ep({ endpointKey: 'PUT /c', title: 'renamed only? no — same hash', structureHash: cOld.structureHash })
    const prev = spec({ id: 10, specVersion: 1, endpoints: [cOld] })
    const next = spec({ id: 11, specVersion: 2, prevSpecId: 9, endpoints: [same] })
    const m = buildVersionCompare(prev, next)
    expect(m.changed).toHaveLength(0)
    expect(m.added).toHaveLength(0)
    expect(m.removed).toHaveLength(0)
  })

  it('changed rows carry both prev and next snapshots for the side-by-side panel', () => {
    const prev = spec({ id: 10, specVersion: 1, endpoints: [cOld] })
    const next = spec({ id: 11, specVersion: 2, prevSpecId: 10, endpoints: [cNew], diffSummary: { added: [], changed: ['PUT /c'], removed: [] } })
    const m = buildVersionCompare(prev, next)
    expect(m.changed[0].prev.title).toBe('old title')
    expect(m.changed[0].next.title).toBe('new title')
  })

  it('changed list is sorted by endpointKey for a stable screen', () => {
    const prev = spec({ id: 1, specVersion: 1, endpoints: [ep({ endpointKey: 'PUT /z' }), ep({ endpointKey: 'GET /a', structureHash: 'x' })] })
    const next = spec({ id: 2, specVersion: 2, prevSpecId: null, endpoints: [ep({ endpointKey: 'GET /a', structureHash: 'y' }), ep({ endpointKey: 'PUT /z' })] })
    const m = buildVersionCompare(prev, next)
    expect(m.changed.map((r) => r.endpointKey)).toEqual(['GET /a'])
  })
})
