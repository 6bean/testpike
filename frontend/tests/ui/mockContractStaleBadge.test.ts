import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Contract-removal badge contract (MJ P6): rules whose endpoint left the
 * contract keep their row for traffic history and carry one honest screen
 * word (a localized tag) in both locales, fed by a persisted flag that the
 * rule read path must carry from the database column all the way to the
 * endpoint list view.
 */

const root = resolve(__dirname, '../../src')
const endpointsSrc = readFileSync(resolve(root, 'views/mock/MockEndpointsView.vue'), 'utf8')
const apiSrc = readFileSync(resolve(root, 'api/mock.ts'), 'utf8')
const zhSrc = readFileSync(resolve(root, 'utils/locales/zh-CN.ts'), 'utf8')
const enSrc = readFileSync(resolve(root, 'utils/locales/en-US.ts'), 'utf8')

describe('contract-removal badge on the endpoint list', () => {
  it('the status cell shows one danger tag fed by the persisted flag', () => {
    expect(endpointsSrc).toContain('row.contractStale')
    expect(endpointsSrc).toContain('mock-endpoints-stale-tag')
    expect(endpointsSrc).toContain("t('mockEndpoints.staleTag')")
  })
  it('the row type carries the flag as an optional boolean', () => {
    expect(apiSrc).toContain('contractStale?: boolean | null')
  })
  it('both locales spell the screen word and the meaning', () => {
    expect(zhSrc).toContain("staleTag: '契约已移除'")
    expect(enSrc).toContain("staleTag: 'Contract removed'")
    expect(zhSrc).toContain('staleTip:')
    expect(enSrc).toContain('staleTip:')
  })
})
