/**
 * waterline contract: the dialog panel renders the context
 * waterline strictly as a mirror of the server door.
 *
 * Same disk-truth posture as mergeBandFilterBarActions.test.ts (templates read from
 * source, no memory lists). Asserts:
 * 1. the api mirror carries exactly the seven fields of the admin
 * ChatContextWaterlineMirrorView and rides GET
 * /ai/chat/sessions/{id}/context-waterline;
 * 2. the bar never re-computes ledger numbers: no token summation
 * anywhere in the component, the meter width is the only derived
 * value (display mapping of the two server numbers);
 * 3. budgetUnlimited reads as an honest "no cap" line and the exhausted
 * warning status can only come from the finite-budget branch;
 * 4. the folded-turns disclosure is a retrieval anchor onto the texts
 * the parent already loaded (no second message fetch in the bar);
 * 5. ChatPanel mounts the bar only for the thread view (not the session
 * list swap) and feeds it the active session id;
 * 6. zh/en waterline key sets are symmetric (i18n dual track).
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..', 'src')

const read = (rel: string): string => readFileSync(join(SRC, rel), 'utf-8')

const api = read('api/aiChat.ts')
const bar = read('components/ChatWaterlineBar.vue')
const panel = read('components/ChatPanel.vue')
const zh = read('utils/locales/zh-CN.ts')
const en = read('utils/locales/en-US.ts')

describe('context waterline bar contract (T452 slice 2)', () => {
  it('mirrors exactly the seven fields of the admin door', () => {
    const iface = api.match(/export interface ChatContextWaterlineView \{([\s\S]*?)\n\}/)
    expect(iface, 'waterline interface exported from api mirror').not.toBeNull()
    const fields = (iface![1].match(/^\s+\w+:.*$/gm) ?? []).map((l) => l.trim().split(':')[0])
    expect(fields.sort()).toEqual(
      [
        'budgetTokens',
        'budgetUnlimited',
        'foldedTurns',
        'keepRecentTurns',
        'pinnedFactsCount',
        'promptTokensUsed',
        'sealedUserTurns',
      ].sort(),
    )
  })

  it('rides the GET context-waterline door and nothing else', () => {
    expect(api).toMatch(/fetchContextWaterline[\s\S]*?url: `\/ai\/chat\/sessions\/\$\{sessionId\}\/context-waterline`[\s\S]*?method: 'get'/)
  })

  it('never re-computes ledger numbers in the panel', () => {
    // No summation of tokens anywhere in the bar component.
    expect(bar).not.toMatch(/reduce\(/)
    expect(bar).not.toMatch(/prompt_tokens/i)
    // The only derived display value is the meter width from the two
    // server numbers, clamped and rounded for presentation.
    const meter = bar.match(/const meterWidth = computed\(\(\) => \{[\s\S]*?\n\}\)/)
    expect(meter, 'meter width is a declared display mapping').not.toBeNull()
    expect(meter![0]).toContain('promptTokensUsed')
    expect(meter![0]).toContain('budgetTokens')
  })

  it('renders unlimited budget honestly and gates the warning status on the finite branch', () => {
    expect(bar).toContain('chat-waterline-unlimited')
    // The exhausted-looking warning status must sit in the v-else branch
    // (finite budget): grep the template segment between the unlimited span
    // closing and the fold section to confirm ordering.
    const unlimitedIdx = bar.indexOf('chat-waterline-unlimited')
    const statusIdx = bar.indexOf("meterWidth >= 90 ? 'warning'")
    const foldIdx = bar.indexOf('chat-waterline-fold')
    expect(unlimitedIdx).toBeGreaterThan(-1)
    expect(statusIdx).toBeGreaterThan(unlimitedIdx)
    expect(statusIdx).toBeLessThan(foldIdx)
    // budgetUnlimited short-circuits the meter width to zero.
    expect(bar).toMatch(/if \(!w \|\| w\.budgetUnlimited \|\| w\.budgetTokens <= 0\) return 0/)
  })

  it('discloses folded turns from already-loaded texts, no second retrieval chain', () => {
    // The bar imports only its own waterline door — never the message list.
    const imports = bar.match(/import \{([^}]*)\} from '@\/api\/aiChat'/)
    expect(imports, 'single api import statement').not.toBeNull()
    expect(imports![1]).not.toMatch(/getChatHistory/)
    // The preview slices the parent-provided user texts (retrieval anchor).
    expect(bar).toMatch(/props\.userTurnTexts \?\? \[\]\)\.slice\(/)
    // And is capped so worst-case session size cannot blow up the bar.
    expect(bar).toMatch(/Math\.min\(foldedCount\.value, 10\)/)
  })

  it('ChatPanel mounts the bar for the thread view only', () => {
    expect(panel).toMatch(/import ChatWaterlineBar from '@\/components\/ChatWaterlineBar\.vue'/)
    const mount = panel.match(/<ChatWaterlineBar[^>]*>/)
    expect(mount, 'bar is mounted in the panel template').not.toBeNull()
    expect(mount![0]).toMatch(/v-if="!historyOpen"/)
    expect(mount![0]).toContain(':session-id="sessionId"')
    expect(mount![0]).toContain(':user-turn-texts=')
  })

  it('refetches on the sealed-ledger tick and skips envelope keys in the fact count', () => {
    // closure fixes proven as disk truth: the bar must follow every
    // seal (no frozen first-snapshot), and route/projectId are navigation
    // envelope, not pinned facts.
    expect(bar).toMatch(/refreshToken\?: number/)
    expect(bar).toMatch(/watch\(\s*\(\) => props\.refreshToken,/)
    // Latest-wins guard: overlapping refreshes must not let a stale
    // (pre-seal) response overwrite a newer snapshot (E2E-proven freeze).
    expect(bar).toMatch(/const seq = \+\+waterlineReqSeq/)
    expect(bar).toMatch(/if \(seq === waterlineReqSeq\) waterline\.value = view/)
    expect(panel).toMatch(/:refresh-token="sealedTurnCount"/)
    expect(panel).toMatch(/m\.state === 'sealed'/)
    const waterlineImpl = readFileSync(
      join(__dirname, '..', '..', '..', 'core', 'src', 'main', 'java', 'com', 'sixbean', 'testpike',
        'core', 'aichat', 'domain', 'service', 'impl', 'ChatContextWaterlineServiceImpl.java'),
      'utf-8')
    expect(waterlineImpl).toMatch(/CONTEXT_ENVELOPE_KEYS\.contains\(entry\.getKey\(\)\)/)
  })

  it('layers the meter line above the fold line (vision review: weak hierarchy)', () => {
    // The five-dimension pass read "number line and human wording share one
    // flex-wrap line, weak hierarchy" as a real defect: the bar root must
    // stack its sections vertically instead of wrapping them side by side.
    const root = bar.match(/\.chat-waterline \{[\s\S]*?\n\}/)
    expect(root, 'root style block exists').not.toBeNull()
    expect(root![0]).toContain('flex-direction: column')
    // Bottom breathing room aligns with the 16px theme single source
    // (vision finding Di: measured bottom gap was under 12px).
    expect(root![0]).toMatch(/padding: \d+px 16px 16px/)
  })

  it('counts the disclosure list and marks bullet items explicitly', () => {
    // Vision review true gap: the preview list had no "total N"
    // caption, so users cannot tell how many originals are in view.
    expect(bar).toContain('data-test="chat-waterline-preview-count"')
    expect(bar).toContain("t('layout.chatDock.waterline.previewCount'")
    // Honest cap note when fewer entries are listed than were folded.
    expect(bar).toContain("t('layout.chatDock.waterline.previewMore'")
    // Vision review: two image-level reads disagreed on whether the
    // 12px bullets are visible at all — the marker relied on the UA
    // default. Declare list-style explicitly so the disclosure rendering
    // never depends on that default.
    const list = bar.match(/\.chat-waterline-preview \{[\s\S]*?\n\}/)
    expect(list, 'preview list style block exists').not.toBeNull()
    expect(list![0]).toContain('list-style: disc')
  })

  it('formats token figures without a bare .0 and groups plain digits', () => {
    // meaningless ".0" on a round budget. Disk-truth contract for the fix:
    // whole thousands print bare ("200k"), fractional keep one decimal,
    // sub-10k values carry thousands separators ("4,618").
    expect(bar).toMatch(/Number\.isInteger\(k\) \? String\(k\) : k\.toFixed\(1\)/)
    expect(bar).toMatch(/n\.toLocaleString\('en-US'\)/)
  })

  it('keeps zh/en waterline key sets symmetric', () => {
    const keysOf = (src: string): string[] => {
      const m = src.match(/waterline: \{([\s\S]*?)\n    \},/)
      expect(m, 'waterline block exists').not.toBeNull()
      return (m![1].match(/^\s+(\w+):/gm) ?? []).map((l) => l.trim().split(':')[0])
    }
    const zhKeys = keysOf(zh).sort()
    const enKeys = keysOf(en).sort()
    expect(zhKeys).toEqual(enKeys)
    expect(zhKeys.length).toBeGreaterThanOrEqual(7)
  })
})
