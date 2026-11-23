/**
 * Queue-notice render contract .
 *
 * The backend assembles a three-part waiting sentence (reason / current
 * backlog / recovery estimate) on AiBatchInfo.queueNotice and the admin
 * record must carry it through to the browser. The batch progress panel
 * shows that sentence as a live status region while the batch still has
 * queued items — no local copy of the sentence, no manual-refresh wording.
 *
 * Gates (source-text lane, same style as the recovery contract):
 * 1) the api type mirrors the wire field;
 * 2) the panel renders the server string verbatim under a stable anchor
 * with a polite live-region role;
 * 3) the notice sits between the progress bar and the item list, so it
 * reads as progress context, not as an error strip;
 * 4) the view keeps no hardcoded sentence text (single assembly source is
 * the server side).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const SRC = new URL('../../src', import.meta.url).pathname

const genView = readFileSync(`${SRC}/views/ai/AiGenerateView.vue`, 'utf8')
const aiApi = readFileSync(`${SRC}/api/ai.ts`, 'utf8')

describe('queue-notice render contract', () => {
  it('api type mirrors the server queueNotice field', () => {
    const iface = aiApi.match(/export interface AiBatch \{[\s\S]*?\n\}/)
    expect(iface, 'AiBatch interface present').not.toBeNull()
    expect(iface![0]).toMatch(/queueNotice\??:\s*string\s*\|\s*null/)
  })

  it('panel renders the server sentence under a stable anchor with live-region role', () => {
    expect(genView).toContain('data-test="ai-gen-queue-notice"')
    expect(genView).toMatch(/v-if="batch\.queueNotice"[\s\S]{0,160}role="status"/)
    expect(genView).toMatch(/data-test="ai-gen-queue-notice"[\s\S]*?\{\{\s*batch\.queueNotice\s*\}\}/)
  })

  it('notice sits between the progress bar and the batch item list', () => {
    const bar = genView.indexOf('class="batch-progress-bar"')
    const notice = genView.indexOf('data-test="ai-gen-queue-notice"')
    const items = genView.indexOf('data-test="ai-gen-batch-items"')
    expect(bar).toBeGreaterThan(-1)
    expect(notice).toBeGreaterThan(bar)
    expect(items).toBeGreaterThan(notice)
  })

  it('view carries no hardcoded copy of the server sentence (single assembly source)', () => {
    expect(genView).not.toContain('条任务正在排队')
    expect(genView).not.toContain('预计约')
    // R62: a waiting face must never instruct the user to refresh by hand.
    const noticeBlock = genView.match(/v-if="batch\.queueNotice"[\s\S]*?<\/div>/)
    if (noticeBlock) {
      expect(noticeBlock[0]).not.toMatch(/刷新/)
    }
  })
})
