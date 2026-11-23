/**
 * Paste-a-curl fast-create parser (AG batch , design F1 path-2:
 * "have a response sample = a mock in 10 seconds").
 *
 * Scope honesty: this is a PRE-FILL helper, not a validator. It turns one
 * pasted curl command (optionally followed by an HTTP response block) into a
 * stub create payload. Legality is judged once at the core save gate — the
 * parser never rejects on grounds the server would accept, and never claims a
 * shape is valid.
 *
 * Recognised forms:
 * curl [-s/-X VERB/-H 'K: V'/-d|--data[-*] 'body'] URL [more flags]
 * plus backslash line continuations and a trailing raw HTTP response block
 * ("HTTP/1.1 201 ..." header lines, blank line, body) when pasted together.
 */
import type { MockStubCreatePayload } from '@/api/mock'

/** Space-relative path root stripping: /mocks/{key} prefix, whatever host. */
const SERVING_ROOT = /\/mocks\/[^/?#]+/

export interface CurlParsedDraft extends MockStubCreatePayload {
  responseJson: string
}

export type CurlParseResult =
  | { ok: true; draft: CurlParsedDraft }
  | { ok: false; reason: 'empty' | 'no-url' }

/** Tokens quoted/escaped the shell way — supports single, double quotes and backslash escapes. */
function tokenize(input: string): string[] {
  const tokens: string[] = []
  let cur = ''
  let started = false
  let quote: "'" | '"' | null = null
  for (let i = 0; i < input.length; i++) {
    const ch = input[i]
    if (quote) {
      if (ch === '\\' && quote === '"' && i + 1 < input.length) {
        cur += input[++i]
      } else if (ch === quote) {
        quote = null
      } else {
        cur += ch
      }
      continue
    }
    if (ch === "'" || ch === '"') {
      quote = ch
      started = true
    } else if (ch === '\\' && i + 1 < input.length) {
      cur += input[++i]
      started = true
    } else if (/\s/.test(ch)) {
      if (started) tokens.push(cur)
      cur = ''
      started = false
    } else {
      cur += ch
      started = true
    }
  }
  if (started) tokens.push(cur)
  return tokens
}

/** Split a pasted block into (curl part, optional HTTP response sample). */
function splitResponseBlock(raw: string): [string, string | null] {
  const m = raw.match(/^HTTP\/\d(?:\.\d)?\s+\d{3}[^\n]*$/im)
  if (!m || m.index === undefined) return [raw, null]
  return [raw.slice(0, m.index), raw.slice(m.index)]
}

interface ResponseSample {
  status: number
  headers: Record<string, string>
  body: string
}

function parseResponseSample(block: string): ResponseSample | null {
  const blank = /\r?\n[ \t]*\r?\n/.exec(block)
  const head = !blank ? block : block.slice(0, blank.index)
  const body = !blank ? '' : block.slice(blank.index + blank[0].length)
  const lines = head.split(/\r?\n/)
  const status = /HTTP\/\d(?:\.\d)?\s+(\d{3})/i.exec(lines[0] ?? '')
  if (!status) return null
  const headers: Record<string, string> = {}
  for (const line of lines.slice(1)) {
    const kv = /^([^:]+):\s*(.*)$/.exec(line)
    if (kv) headers[kv[1].trim()] = kv[2].trim()
  }
  return { status: Number(status[1]), headers, body: body.trimEnd() }
}

/** Pull one header value out of 'K: V' the way curl -H takes it. */
function headerRow(raw: string): { kind: 'header'; key: string; matchType: 'exact'; value: string } | null {
  const kv = /^([^:]+):\s*(.*)$/.exec(raw.trim())
  if (!kv) return null
  return { kind: 'header', key: kv[1].trim(), matchType: 'exact', value: kv[2].trim() }
}

function tryUrl(raw: string): URL | null {
  try {
    // bare "//host/path" or "/path" forms still need a synthetic scheme
    return new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : `http:${raw.replace(/^\/\//, '//')}`.replace(/^(?!http)/, 'http://'))
  } catch {
    return null
  }
}

export function parseCurlDraft(input: string): CurlParseResult {
  const raw = input.replace(/\\(?:\r?\n|$)/g, ' ').trim()
  if (!raw) return { ok: false, reason: 'empty' }
  const [curlPart, responsePart] = splitResponseBlock(raw)
  const tokens = tokenize(curlPart)
  const first = (tokens[0] ?? '').toLowerCase()
  const args = first.startsWith('curl') ? tokens.slice(1) : tokens

  let url = ''
  let method: string | null = null
  let dataArg: string | null = null
  const matcherRows: Array<Record<string, unknown>> = []

  for (let i = 0; i < args.length; i++) {
    const t = args[i]
    if (/^https?:\/\//i.test(t) || t.startsWith('//') || (t.startsWith('/') && !url)) {
      if (!url) url = t
      continue
    }
    const take = () => args[++i] ?? ''
    if (t === '-X' || t === '--request') method = take().toUpperCase()
    else if (t === '-H' || t === '--header') {
      const row = headerRow(take())
      if (row && !/^content-type:/i.test(row.key)) matcherRows.push(row)
    } else if (t === '-d' || t === '--data' || t === '--data-raw' || t === '--data-binary' || t === '--data-urlencode') {
      dataArg = take()
    } else if (/^--data-.*/.test(t)) {
      dataArg = take()
    }
    // flags without values (-s, -k, -i ...) and their operands are simply skipped
  }

  if (!url) return { ok: false, reason: 'no-url' }
  const parsed = tryUrl(url)
  if (!parsed) return { ok: false, reason: 'no-url' }

  const path = parsed.pathname.replace(SERVING_ROOT, '') || parsed.pathname
  const finalMethod = method ?? (dataArg !== null ? 'POST' : 'GET')

  for (const [key, value] of parsed.searchParams.entries()) {
    matcherRows.unshift({ kind: 'query', key, matchType: 'exact', value })
  }
  if (dataArg !== null) {
    matcherRows.push({ kind: 'body', bodyType: 'exact', value: dataArg })
  }

  const sample = responsePart ? parseResponseSample(responsePart) : null
  const response = {
    status: sample?.status ?? 200,
    headers: sample
      ? Object.fromEntries(
          Object.entries(sample.headers).filter(([k]) => !/^(date|server|connection|content-length|transfer-encoding)$/i.test(k)),
        )
      : { 'Content-Type': 'application/json' },
    bodyMode: 'static',
    // With a pasted sample, its body is used as-is (even an empty one — the
    // user edits it in the builder); without one, a placeholder keeps the
    // generated stub answering something instead of nothing.
    body: sample ? sample.body : '{"mock":true}',
  }
  if (sample && !sample.body) delete (response as Record<string, unknown>).body

  const relPath = path.startsWith('/') ? path : `/${path}`
  return {
    ok: true,
    draft: {
      name: `${finalMethod} ${relPath}`.slice(0, 120),
      method: finalMethod,
      pathMatchType: 'exact',
      pathPattern: relPath,
      matchersJson: matcherRows.length ? JSON.stringify(matcherRows) : null,
      responseJson: JSON.stringify(response),
      source: 'manual',
    },
  }
}
