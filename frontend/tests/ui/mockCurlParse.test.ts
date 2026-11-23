/**
 * RED anchor for the paste-a-curl fast-create parser (AG batch ,
 * design F1 path-2: paste a curl / HTTP message -> skeleton stub).
 *
 * The parser is deliberately frontend-side PRE-FILL only: legality is judged
 * once at the core save gate (MockRuleValidator), never a second time here.
 * These tests pin the parsing contract: serving-root stripping, method
 * inference, matcher rows, and the response-sample capture.
 */
import { describe, it, expect } from 'vitest'
import { parseCurlDraft } from '../../src/utils/mockCurlParse'

function okDraft(text: string) {
  const r = parseCurlDraft(text)
  if (!r.ok) throw new Error(`expected ok parse but got reason=${r.reason}`)
  return r.draft
}

describe('paste-curl fast-create parser', () => {
  it('strips the serving root and keeps the space-relative path with query matchers', () => {
    const d = okDraft(`curl -s 'http://localhost:18086/mocks/orders/api/orders?page=2&status=NEW'`)
    expect(d.method).toBe('GET')
    expect(d.pathMatchType).toBe('exact')
    expect(d.pathPattern).toBe('/api/orders')
    const rows = JSON.parse(String(d.matchersJson))
    expect(rows).toContainEqual({ kind: 'query', key: 'page', matchType: 'exact', value: '2' })
    expect(rows).toContainEqual({ kind: 'query', key: 'status', matchType: 'exact', value: 'NEW' })
  })

  it('infers POST from -d and maps -H rows into header matchers', () => {
    const d = okDraft(
      `curl -X POST http://host/mocks/k/api/pay -H 'X-Token: abc' -d '{"amount":10}'`,
    )
    expect(d.method).toBe('POST')
    expect(d.pathPattern).toBe('/api/pay')
    const rows = JSON.parse(String(d.matchersJson))
    expect(rows).toContainEqual({ kind: 'header', key: 'X-Token', matchType: 'exact', value: 'abc' })
    expect(rows).toContainEqual({ kind: 'body', bodyType: 'exact', value: '{"amount":10}' })
  })

  it('honours an explicit -X even with a data payload', () => {
    const d = okDraft(`curl -X PUT http://h/mocks/k/r -H 'Content-Type: application/json' --data 'x=1'`)
    expect(d.method).toBe('PUT')
  })

  it('captures a pasted HTTP response block as the stub response', () => {
    const d = okDraft(
      [
        'curl -s http://h/mocks/k/api/ping',
        'HTTP/1.1 201 Created',
        'Content-Type: application/json',
        '',
        '{"pong":true}',
      ].join('\n'),
    )
    const resp = JSON.parse(d.responseJson)
    expect(resp.status).toBe(201)
    expect(resp.bodyMode).toBe('static')
    expect(resp.body).toBe('{"pong":true}')
  })

  it('defaults to a 200 placeholder response when no sample is pasted', () => {
    const resp = JSON.parse(okDraft('curl http://h/mocks/k/a/b').responseJson)
    expect(resp.status).toBe(200)
    expect(typeof resp.body).toBe('string')
    expect(resp.body.length).toBeGreaterThan(0)
  })

  it('joins backslash line continuations before parsing', () => {
    const d = okDraft("curl -s \\\n  'http://h/mocks/k/api/x' \\\n  -H 'A: b'")
    expect(d.pathPattern).toBe('/api/x')
    expect(String(d.matchersJson)).toContain('"A"')
  })

  it('names the draft after method and path so the card reads meaningful out of the box', () => {
    const d = okDraft('curl http://h/mocks/k/api/users')
    expect(d.name).toBe('GET /api/users')
  })

  it('refuses empty input and input without any URL', () => {
    const empty = parseCurlDraft('   ')
    expect(empty.ok).toBe(false)
    expect(empty.reason).toBe('empty')
    const noUrl = parseCurlDraft('curl -s -H "X: y"')
    expect(noUrl.ok).toBe(false)
    expect(noUrl.reason).toBe('no-url')
  })
})
