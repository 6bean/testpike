/**
 * R63 error-guidance three-element rule · frontend mechanism piece (single source;
 * page-level patches are forbidden, pages consume this module or ErrorNotice.vue).
 *
 * Splits an on-screen error string into reason part + next-step guidance part.
 * A sentence counts as guidance only when it carries BOTH a fix-action word and
 * an entry-path literal (「」 path / config-key shape / explicit "correct usage"
 * example) — mirroring the verdict set of tools/error_guidance_coverage_gate.py.
 * Rules:
 *  - guidance present  -> it must be displayed (dedicated next-step slot);
 *  - guidance absent   -> the slot must not render an empty shell (hasGuidance=false).
 * Pure module: no element-plus / vue imports so node-env contract tests can import it.
 */

/** Fix-action word family; kept aligned with gate default gate.error-guidance.words. */
const FIX_ACTION_WORDS = [
  '请',
  '下一步',
  '建议',
  '可在',
  '前往',
  '打开',
  '改成',
  '点击',
  '启用',
  '停用',
  '填写',
  '选择',
  '配置',
]

/**
 * Entry-path literal family:
 *  - 「…」 quoted menu/field path;
 *  - config-key shape (>=2 dot/hyphen segments starting with a letter, e.g. ai.doc.fidelity-retention-floor);
 *  - explicit correct-usage example marker.
 * Single-dot forms like "e.g." intentionally do not match.
 */
const PATH_LITERAL_RE = /「[^」]+」|正确写法|[a-z][a-z0-9]*(?:[.-][a-z0-9]+){2,}/

/** Sentence splitter: CJK/latin full stops, semicolons, bangs, question marks, newlines. */
const SENTENCE_SPLIT_RE = /[。；;！!？?\n]+/

export interface ErrorGuidanceParts {
  /** What went wrong (everything that is not a guidance sentence). */
  reason: string
  
  guidance: string
}

function isGuidanceSentence(sentence: string): boolean {
  const hasAction = FIX_ACTION_WORDS.some((w) => sentence.includes(w))
  return hasAction && PATH_LITERAL_RE.test(sentence)
}

/**
 * Split raw error text into reason + next-step guidance.
 * If everything qualifies as guidance (no reason left), the whole text stays
 * as reason and guidance is empty — the slot must never swallow the cause line.
 */
export function parseErrorGuidance(message: string): ErrorGuidanceParts {
  const raw = (message ?? '').trim()
  if (!raw) return { reason: '', guidance: '' }
  const sentences = raw.split(SENTENCE_SPLIT_RE).map((s) => s.trim()).filter(Boolean)
  const reasons: string[] = []
  const guidance: string[] = []
  for (const sentence of sentences) {
    if (isGuidanceSentence(sentence)) guidance.push(sentence)
    else reasons.push(sentence)
  }
  if (reasons.length === 0) {
    return { reason: raw, guidance: '' }
  }
  return { reason: reasons.join('。'), guidance: guidance.join('；') }
}

/** Escape user/backend text before embedding into an HTML message. */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Build the two-line toast HTML for an error that carries guidance.
 * The next-step line is rendered ONLY when guidance is non-empty (no empty shell).
 * `label` is the localized "Next step" prefix supplied by the caller (i18n stays outside this module).
 */
export function buildGuidanceHtml(reason: string, guidance: string, label: string): string {
  const safeReason = escapeHtml(reason)
  if (!guidance) return safeReason
  return (
    `<div class="tp-error-notice" data-test="error-notice">` +
    `<div data-test="error-notice-reason">${safeReason}</div>` +
    `<div class="tp-error-notice__next" data-test="error-notice-next-step">` +
    `<strong>${escapeHtml(label)}：</strong>${escapeHtml(guidance)}</div>` +
    `</div>`
  )
}
