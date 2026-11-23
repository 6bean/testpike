/**
 * Every locale string is compiled by vue-i18n at display time; a message that
 * fails the message-compiler surfaces as a browser console error only when the
 * dialog renders, which E2E catches late. This contract compiles all message
 * leaves of both dictionaries up front so any raw placeholder syntax in a
 * message (e.g. an unescaped {{CRED:key}} example) turns the suite red at the
 * change that introduced it. Literal examples must be wrapped in the
 * literal-interpolation form {'...'} — the established precedent form across
 * the dictionaries.
 */
import { describe, expect, it } from 'vitest'
import { baseCompile } from '@intlify/message-compiler'
import zhCN from '@/utils/locales/zh-CN'
import enUS from '@/utils/locales/en-US'

type Leaf = { key: string; text: string }

function collectLeaves(dict: unknown, prefix: string, out: Leaf[]): void {
  if (typeof dict === 'string') {
    out.push({ key: prefix, text: dict })
    return
  }
  if (dict && typeof dict === 'object') {
    for (const [name, value] of Object.entries(dict)) {
      collectLeaves(value, prefix ? `${prefix}.${name}` : name, out)
    }
  }
}

function findUncompilable(locale: string, dict: unknown): string[] {
  const leaves: Leaf[] = []
  collectLeaves(dict, '', leaves)
  const offenders: string[] = []
  for (const leaf of leaves) {
    const errors: string[] = []
    try {
      baseCompile(leaf.text, { onError: (err: { message: string }) => errors.push(err.message) })
    } catch (err) {
      errors.push(String(err))
    }
    if (errors.length > 0) {
      offenders.push(`${locale} ${leaf.key}: ${errors.join(' | ')}`)
    }
  }
  return offenders
}

describe('locale message compilation contract', () => {
  it('zh-CN dictionary compiles every message without syntax errors', () => {
    expect(findUncompilable('zh-CN', zhCN)).toEqual([])
  })

  it('en-US dictionary compiles every message without syntax errors', () => {
    expect(findUncompilable('en-US', enUS)).toEqual([])
  })
})
