/**
 * W1c auth-editor auth-form TS mirror L2 (both-ends fixture = repo root
 * shared/auth_form_cases.json). Authority = core AuthFormContract (save gate);
 * this layer is the UX pre-gate mirror only — the same case set is consumed on
 * both ends to pin verdict drift (assertion_form_cases.json precedent).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { AUTH_TYPES, authTypeList, validateAuth } from '@/utils/authForm'

const here = dirname(fileURLToPath(import.meta.url))
const fixture = JSON.parse(
  readFileSync(resolve(here, '../../../shared/auth_form_cases.json'), 'utf-8'),
) as {
  positives: { auth: unknown }[]
  negatives: { auth: unknown; why: string; errorIncludes: string[] }[]
}

describe('auth form kernel: positives pass exactly like the core save gate', () => {
  it.each(fixture.positives.map((c, i) => [i, c.auth] as const))(
    'positive #%s is legal', (_i, auth) => {
      expect(validateAuth(auth)).toBe(null)
    },
  )
})

describe('auth form kernel: negatives fail with the same plain-language anchors', () => {
  it.each(fixture.negatives.map((c, i) => [i, c] as const))(
    'negative #%s (%s) is rejected', (_i, caseItem) => {
      const verdict = validateAuth(caseItem.auth)
      expect(verdict, caseItem.why).not.toBe(null)
      for (const anchor of caseItem.errorIncludes) {
        expect(verdict, `anchor「${anchor}」missing in「${verdict}」`).toContain(anchor)
      }
    },
  )
})

describe('auth form kernel: vocabulary mirrors the Java single source', () => {
  it('lists the five executor types sorted (mirror SUPPORTED_TYPES/typeList)', () => {
    expect(authTypeList()).toBe('api-key / basic / bearer / header / signature')
    expect([...AUTH_TYPES].sort()).toEqual(['api-key', 'basic', 'bearer', 'header', 'signature'])
  })
})
