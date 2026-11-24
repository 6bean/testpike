/**
 * Wire-contract literals shared with server-emitted process rows.
 *
 * These are NOT display copy: the values are the exact strings the backend
 * writes into ledger summary fields and exchange-trail lines, and the
 * frontend strips/matches them to recognise machine-signalled rows. They
 * must never go through the i18n layer (a locale switch would silently
 * break the parse) and they live here, outside the five display directories
 * swept by the i18nKeys P7e gate (views/components/api/layouts/router = UI
 * copy faces), because a parse key is not UI copy. Keep every change paired
 * with the emitting server code — each block names its Java emitter and the
 * tail wiring contract tests grep those sources for the exact values below,
 * so a writer-side wording drift fails a test instead of silently keeping
 * a tail window open.
 */

/**
 * Prefix of the honest gap-declaration rows : the generation
 * service records one level=step row per declared missing-resource category
 * (name plus optional intent; AiGenServiceImpl is the emitter). The chip
 * face strips this prefix for display; this file owns the literal.
 */
export const RESOURCE_GAP_PREFIX = '缺资源申报：'

/**
 * Terminal-frame prefix of the batch tail — emitter is BatchFinalizeSupport
 * (sealed frame on finalize, cancelled frame on the cancel door, watchdog
 * frame on the timeout sweep).
 */
export const BATCH_TERMINAL_PREFIX = '批次执行结束' as const

/**
 * Terminal-frame prefixes of the explore tail — emitters are the
 * ExploreLoopKernel loop-exit line (business wording since the process-event
 * rewrite: an auto-browsing-ended frame carrying outcome, step and page
 * counters, bar-separated) and the shaped failure line from
 * TaskFailureEventFormatter.
 */
export const EXPLORE_TERMINAL_PREFIXES = ['自动浏览结束｜', '失败：【'] as const

/**
 * Terminal-frame prefixes of the channel-B spec-extraction tail — emitter
 * is SpecDraftExtractServiceImpl, which books the done frame and the
 * failure frame (bar-separated readable reason) on the shared
 * process-event ledger.
 */
export const SPEC_EXTRACT_TERMINAL_PREFIXES = ['接口文档抽取完成|', '接口文档抽取失败|'] as const

/**
 * Terminal-frame prefixes of the web-element survey tail — emitter is core
 * ExploreServiceImpl appendSurveyTerminalFrame / appendSurveyStoppedFrame,
 * single source of the four end states: done / budget pause / no-progress
 * early stop / operator cancel. Step and naming frames never match, so the
 * tail window can never collapse mid-run.
 */
export const SURVEY_TERMINAL_PREFIXES = ['测绘完成', '预算耗尽自动暂停', '同页连续无进展提前结束', '测绘会话已停止'] as const

/**
 * Mask fingerprint inside a trail header line (masked value followed by the
 * total-length bracket, also used in the per-pair cookie form). Emitters
 * are core ProbeExchangeRunner (ExchangeSecretLedger.mask /
 * maskCookiePairs).
 */
export const TRAIL_MASK_MARK = '（总长度 '

/**
 * Section markers that scope a header line to its trail half (request
 * header section, response header section, response body start) — emitter
 * is ProbeExchangeRunner.
 */
export const TRAIL_REQUEST_SECTION_MARK = '请求头（'
export const TRAIL_RESPONSE_SECTION_MARK = '响应头（'
export const TRAIL_BODY_SECTION_MARK = '响应体'

/**
 * Honest pointer an over-budget body ends with, telling the reader the
 * full-text expansion action is available — emitter is
 * ProbeExchangeRunner.renderBody.
 */
export const TRAIL_FULL_POINTER_MARK = '可点「展开全文」'
