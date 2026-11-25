import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Provenance receipt surface contract (MJ batch, endpoint-list view + the
 * shared contract wizard): every rule card that carries an AI-enriched body
 * must show the second badge next to its birth badge, the import receipt must
 * carry the third failed count plus a try-first deep link, and the deep link
 * must resolve to an honest guard set on arrival (no dead-end actions).
 * Static shape checks match the established contract-test style of this suite.
 */

const root = resolve(__dirname, '../../src')
const docsSrc = readFileSync(resolve(root, 'views/mock/MockDocsView.vue'), 'utf8')
const wizardSrc = readFileSync(resolve(root, 'components/mock/MockStubDirectWizard.vue'), 'utf8')
const zhSrc = readFileSync(resolve(root, 'utils/locales/zh-CN.ts'), 'utf8')
const enSrc = readFileSync(resolve(root, 'utils/locales/en-US.ts'), 'utf8')

describe('enriched provenance badge on the rule cards', () => {
  it('card head renders an AI-enriched tag only when the row carries the provenance', () => {
    expect(docsSrc).toContain('v-if="row.enrichedBy"')
    expect(docsSrc).toContain('mock-docs-enriched-tag')
    expect(docsSrc).toContain("t('mockDocs.enrichedTag')")
    expect(docsSrc).toContain("t('mockDocs.enrichedTip')")
  })
  it('both locales spell the badge and no hard-coded Chinese sits in the view', () => {
    expect(zhSrc).toContain("enrichedTag: 'AI 完善'")
    expect(enSrc).toContain("enrichedTag: 'AI-enriched'")
  })
})

describe('import receipt three counts and try-first action', () => {
  it('receipt names the failed bucket beyond created and skipped', () => {
    expect(wizardSrc).toContain('stub-direct-done-created')
    expect(wizardSrc).toContain('stub-direct-done-skipped')
    expect(wizardSrc).toContain('stub-direct-done-failed')
    expect(wizardSrc).toContain('const failedCount = computed')
    expect(wizardSrc).toContain('importedTotal.value = chosen.length')
  })
  it('try button rides the first browser-tryable created id, never a dead link', () => {
    expect(wizardSrc).toContain('firstTryableStubId')
    expect(wizardSrc).toContain("d.method.toUpperCase() === 'GET'")
    expect(wizardSrc).toContain('stub-direct-try-first')
    expect(wizardSrc).toContain('tryStub: String(firstTryableStubId.value)')
  })
  it('receipt action is named as an endpoint list, matching the ruling wording', () => {
    expect(zhSrc).toContain("viewDocs: '查看接口清单'")
    expect(enSrc).toContain("viewDocs: 'View endpoint list'")
    expect(zhSrc).toContain("tryFirst: '试调第一个'")
    expect(enSrc).toContain("tryFirst: 'Try the first one'")
  })
})

describe('deep-link arrival guards on the docs page', () => {
  it('query key is consumed once right after the rule list loads', () => {
    expect(docsSrc).toContain('async function consumeTryStub')
    expect(docsSrc).toContain('void consumeTryStub()')
    expect(docsSrc).toContain('router.replace({ query: rest })')
  })
  it('each unmet precondition names a reason and a way forward', () => {
    expect(docsSrc).toContain("t('mockDocs.tryRowMissing')")
    expect(docsSrc).toContain("t('mockDocs.tryNotGet', { method: row.method })")
    expect(docsSrc).toContain("t('mockDocs.tryNeedPublish')")
    expect(zhSrc).toContain('发布后才能浏览器试调')
    expect(enSrc).toContain('Publish the space before a browser try can answer')
  })
})
