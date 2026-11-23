/**
 * stage 3b layout contract (RED first, ?raw source-scan posture following
 * the mockHeaderLayoutT396 precedent). Authority = the AO adjudication doc §AO-7
 * stage-3 line: the batch report page gains a failure-insight card that
 * - renders ONLY when the read door returns a row (honest absence, v-if;
 * no placeholder shell, no dialog, no accent stripe — halo card style);
 * - lists clusters with an honest unattributed line when some failures have
 * not finished AI attribution yet;
 * - offers an ask-root-cause button that pushes /ai/chat carrying the question
 * pre-filled (?ask=) plus batchId (context whitelist already carries it);
 * prefill only — ChatPanel must NOT auto-send (human-in-loop contract);
 * - i18n is bilingual (zh + en key family), zero hard-coded Chinese in the
 * template beyond locale lookups.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')
const viewRaw = readFileSync(join(SRC, 'views/batch/BatchDetailView.vue'), 'utf8')
const panelRaw = readFileSync(join(SRC, 'components/ChatPanel.vue'), 'utf8')
const apiRaw = readFileSync(join(SRC, 'api/batch.ts'), 'utf8')
const zhRaw = readFileSync(join(SRC, 'utils/locales/zh-CN.ts'), 'utf8')
const enRaw = readFileSync(join(SRC, 'utils/locales/en-US.ts'), 'utf8')

describe('T432-3b insight card contract (BatchDetailView)', () => {
  it('api mirror exports getBatchInsight on the /batches/:id/insight door', () => {
    expect(apiRaw).toMatch(/export function getBatchInsight/)
    expect(apiRaw).toContain('/batches/${id}/insight')
  })

  it('the card renders only with a non-null insight (honest absence)', () => {
    const m = /<el-card[^>]*v-if="insight"[^>]*data-test="batch-insight-card"/.exec(viewRaw)
    expect(m, 'insight card must be gated by v-if="insight"').not.toBeNull()
  })

  it('the card never rides a dialog/popups (halo card style per AO ruling)', () => {
    const start = viewRaw.indexOf('data-test="batch-insight-card"')
    expect(start).toBeGreaterThan(-1)
    const end = viewRaw.indexOf('</el-card>', start)
    const block = viewRaw.slice(start, end)
    expect(block).not.toContain('el-dialog')
    expect(block).not.toContain('ElMessageBox')
  })

  it('clusters render as ranked rows plus an honest unattributed line', () => {
    expect(viewRaw).toMatch(/v-for="\(?\s*c\b[^"]*insight\.clusters"/)
    expect(viewRaw).toContain('batch-insight-unattributed')
    expect(viewRaw).toContain('insight.unattributedCount > 0')
  })

  it('the ask button deep-links the chat page with ask+batchId prefill', () => {
    expect(viewRaw).toContain('data-test="batch-insight-ask"')
    const fn = /function goAskInsight[\s\S]*?\n\}/.exec(viewRaw)
    expect(fn, 'goAskInsight must exist').not.toBeNull()
    expect(fn![0]).toContain("path: '/ai/chat'")
    expect(fn![0]).toContain('ask')
    expect(fn![0]).toContain('batchId')
  })

  it('insight loads for terminal batches and clears otherwise (mirror of loadReport posture)', () => {
    const fn = /async function loadInsight[\s\S]*?\n\}/.exec(viewRaw)
    expect(fn, 'loadInsight must exist').not.toBeNull()
    expect(fn![0]).toContain('[6, 7]')
    expect(fn![0]).toContain('getBatchInsight')
    expect(viewRaw).toContain('loadInsight()')
  })

  it('i18n key family exists in BOTH locales (dual track, no hard Chinese)', () => {
    for (const key of ['insightTitle', 'insightAsk', 'insightUnattributed', 'insightAskText']) {
      expect(zhRaw, `zh missing ${key}`).toContain(`${key}:`)
      expect(enRaw, `en missing ${key}`).toContain(`${key}:`)
    }
  })
})

describe('T432-3b chat ask-prefill contract (ChatPanel)', () => {
  it('a deep-link ask query prefills the draft without auto-sending', () => {
    expect(panelRaw).toContain('route.query.ask')
    // prefill assigns draft.value; the send door (submit/send function) must
    // not be invoked from the mount/prefill path — grep the ask handling block
    const idx = panelRaw.indexOf('route.query.ask')
    const block = panelRaw.slice(Math.max(0, idx - 400), idx + 600)
    expect(block).toMatch(/draft\.value\s*=\s*ask/)
    expect(block).not.toMatch(/void\s+send\(/)
    expect(block).not.toMatch(/\bsend\(\)/)
  })
})
