
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'


const src = readFileSync(fileURLToPath(new URL('../../src/components/JourneyCardsRow.vue', import.meta.url)), 'utf8')

const onMountedBody = src.slice(src.indexOf('onMounted('))

describe('T343 包A2 旅程探针快照刷新时序', () => {
  it('onMounted 内探针发起前先 await loadMe 刷新 /me 快照（q4 purge 后陈旧快照 403 根因修复）', () => {
    const refreshIdx = onMountedBody.indexOf('await authStore.loadMe()')
    const probesIdx = onMountedBody.indexOf('runJourneyProbes')
    expect(refreshIdx, 'onMounted 缺 loadMe 快照刷新').toBeGreaterThan(-1)
    expect(probesIdx).toBeGreaterThan(-1)
    expect(refreshIdx, 'loadMe 刷新必须在 runJourneyProbes 发起之前').toBeLessThan(probesIdx)
  })

  it('刷新失败兜底=try/catch 以现有快照继续（禁 loadMe 网络失败断整屏进度）', () => {
    const seg = onMountedBody.slice(0, onMountedBody.indexOf('runJourneyProbes'))
    expect(seg, 'loadMe 刷新必须包 try/catch（失败静默降级到现有快照）').toMatch(/try\s*\{[\s\S]*loadMe[\s\S]*catch/)
  })
})
