/**
 * Frontend mirrors of the mock rule spectra enforced by the core save gate
 * (core MockRuleValidator.PATH_MATCH_TYPES). One dictionary per spectrum —
 * the structured create form, the curl parser defaults and any future editor
 * all read these lists; a per-page copy could drift against the backend
 * (red line 6). Labels come from i18n (`mockDocs.pathType.<value>`), never
 * baked here.
 */

/** Path match modes the stub spectrum accepts (backend twin, same order). */
export const PATH_MATCH_TYPES = ['exact', 'prefix', 'regex', 'template'] as const

/** Stub provenance badges the docs card can name (core MockStubService
 *  spectrum; unknown tokens from a newer server stay raw, never blank). */
export const STUB_SOURCES = ['manual', 'openapi', 'record', 'nl-ai', 'case-flip'] as const
