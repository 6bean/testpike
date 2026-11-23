/**
 * WE-S7 observation-ticket closure: the census result dialog used to show
 * one shared hint for both doors, so the script-reference door — whose only
 * failure mode is "swapped steps now point at still-pending library
 * entries" — never told the user where to approve them. This contract pins
 * the per-kind dispatch (reason + literal repair path) and the bilingual
 * symmetry of the new key.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const viewSource = readFileSync(
  resolve(here, '../../src/views/webelement/WebElementLedgerView.vue'),
  'utf8'
)
const zhSource = readFileSync(resolve(here, '../../src/utils/locales/zh-CN.ts'), 'utf8')
const enSource = readFileSync(resolve(here, '../../src/utils/locales/en-US.ts'), 'utf8')

function censusBlock(source: string): string {
  const start = source.indexOf('census: {')
  expect(start, 'census locale block missing').toBeGreaterThan(-1)
  const end = source.indexOf('}', start)
  return source.slice(start, end)
}

describe('census result dialog：提示按 censusKind 分派（脚本引用门有专属句子）', () => {
  it('script 门用 scriptGateHint，ledger 门沿用 gateHint', () => {
    expect(viewSource).toMatch(
      /censusKind === 'script' \? t\('webElement\.census\.scriptGateHint'\) : t\('webElement\.census\.gateHint'\)/
    )
  })

  it('普查弹窗不再无条件渲染共享 gateHint', () => {
    const unconditional = /<div class="form-hint">\{\{ t\('webElement\.census\.gateHint'\) \}\}<\/div>/
    expect(viewSource.match(unconditional) ?? []).toHaveLength(0)
  })
})

describe('scriptGateHint locale：双语对称，修法入口用屏显字面词', () => {
  it('zh census 块定义 scriptGateHint，点名页面名与按钮名', () => {
    const block = censusBlock(zhSource)
    const value = block.match(/scriptGateHint:\s*'([^']+)'/)
    expect(value, 'zh scriptGateHint missing').not.toBeNull()
    expect(value![1]).toContain('页面元素库')
    expect(value![1]).toContain('全部批准入库')
    const legacy = block.match(/gateHint:\s*'([^']+)'/)
    expect(value![1]).not.toBe(legacy![1])
  })

  it('en census 块镜像同键与同一组入口字面', () => {
    const block = censusBlock(enSource)
    const value = block.match(/scriptGateHint:\s*'([^']+)'/)
    expect(value, 'en scriptGateHint missing').not.toBeNull()
    expect(value![1]).toContain('Page Element Library')
    expect(value![1]).toContain('Approve all')
  })
})
