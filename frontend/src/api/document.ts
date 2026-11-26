import { td } from '../utils/dictLabel'
import { postMultipart, request, type ProgressFn } from './request'




export interface DocumentView {
  id: number
  projectId: number
  
  name: string
  /** pdf / markdown */
  fileType: string
  
  fileHash: string
  sizeBytes: number
  storageKey: string
  status: number
  chunkCount: number
  
  errorMsg: string | null
  /** category value (doc_category: TEST_SPEC / REQUIREMENT_DOC / DDL_DICT / REFERENCE) */
  category: string | null
  /** per-document switch: feeds the requirement-point extraction pipeline */
  useForExtraction: boolean
  /** per-document switch: injects this document into case-generation prompts */
  useInCasePrompt: boolean
  /** per-document switch: makes this document searchable for AI-chat grounding */
  useInGrounding: boolean
  /** row optimistic-lock token; echo it back on every edit (stale = 40901, readable msg from server) */
  revision: number
  /** lineage group id (same group = versions of one logical document; null = ungrouped) */
  lineageGroupId?: number | null
  /** 1-based in-group version ordinal (server single source; null = ungrouped) */
  lineageSeq?: number | null
  /** total versions in the group (null = ungrouped; 1 = first and only version) */
  lineageSize?: number | null
}


export const DOC_MAX_SIZE_MB = 50


export const DOC_ACCEPT = '.md,.markdown,.pdf'

/** Document category options (doc_category value set; mirrors core DocumentServiceImpl.CATEGORIES — keep both sides in one batch when extending).
 *  Display order follows the user mindset on the document surfaces (the
 *  requirement-extraction page is where uploads happen): the document type the
 *  user starts from comes FIRST and is the upload dialog's default; test specs
 *  share the same extraction door but are the secondary pick, so they sit last.
 *  The 150px table cell hard-truncates long names, so the effect sentence lives
 *  in `description` (dropdown second line + tooltip) and `label` stays a name. */
export const DOC_CATEGORIES: { value: string; label: string; description: string }[] = [
  { value: 'REQUIREMENT_DOC', get label() { return td('dict.doc.category.requirementDoc') }, get description() { return td('dict.doc.category.requirementDocDesc') } },
  { value: 'DDL_DICT', get label() { return td('dict.doc.category.ddlDict') }, get description() { return td('dict.doc.category.ddlDictDesc') } },
  { value: 'REFERENCE', get label() { return td('dict.doc.category.reference') }, get description() { return td('dict.doc.category.referenceDesc') } },
  { value: 'TEST_SPEC', get label() { return td('dict.doc.category.testSpec') }, get description() { return td('dict.doc.category.testSpecDesc') } },
]


export function docCategoryLabel(v: string | null | undefined): string {
  return DOC_CATEGORIES.find((o) => o.value === v)?.label ?? (v || '-')
}

/**
 * Badge colour for a category (single source shared by the read-only tag and any
 * future writer-side affordance — pages must not re-derive it with local ternaries).
 * REQUIREMENT_DOC is accent-coloured because it is the document the user starts
 * downstream work from; DDL_DICT is warning-coloured (parsed content goes to the
 * data dictionary); everything else stays neutral.
 */
export function docCategoryTagType(v: string | null | undefined): 'primary' | 'warning' | 'info' {
  if (v === 'REQUIREMENT_DOC') return 'primary'
  if (v === 'DDL_DICT') return 'warning'
  return 'info'
}

/** Document list (project segment; optional status/category/keyword filters — category is an exact value, empty = all) */
export function listDocuments(params: {
  projectId: number
  status?: number | null
  category?: string | null
  keyword?: string
  page?: number
  size?: number
  sortBy?: string
  sortDir?: string
}): Promise<{ list: DocumentView[]; total: number }> {
  return request({ url: '/ai/documents', method: 'get', params })
}

/**
 * Edit one document's category inline (PATCH /ai/documents/{id}/category).
 * revision = the row token read from the list; a stale token is refused with
 * 40901 whose readable message names the current version (server single
 * source — do not re-implement the check here). On success the server returns
 * the fresh row view (category applied, revision advanced).
 */
export function updateDocumentCategory(
  id: number,
  category: string,
  revision: number,
): Promise<DocumentView> {
  return request({ url: `/ai/documents/${id}/category`, method: 'patch', data: { category, revision } })
}

/**
 * Edit the three per-document usage switches in one call
 * (PATCH /ai/documents/{id}/usage-switches). All three values must be sent
 * together (a missing field is refused with a readable 40001 naming it);
 * revision works the same optimistic lock as the category edit. Success
 * returns the fresh row view.
 */
export function updateDocumentUsageSwitches(
  id: number,
  switches: { useForExtraction: boolean; useInCasePrompt: boolean; useInGrounding: boolean },
  revision: number,
): Promise<DocumentView> {
  return request({
    url: `/ai/documents/${id}/usage-switches`,
    method: 'patch',
    data: { ...switches, revision },
  })
}


export function getDocument(id: number): Promise<DocumentView> {
  return request({ url: `/ai/documents/${id}`, method: 'get' })
}


export function uploadDocument(
  projectId: number,
  file: File,
  category?: string | null,
  onProgress?: ProgressFn,
): Promise<DocumentView> {
  const extra: Record<string, string> = { projectId: String(projectId) }
  if (category) {
    extra.category = category
  }
  return postMultipart<DocumentView>('/ai/documents', file, extra, onProgress, 120000)
}


export function deleteDocument(id: number): Promise<void> {
  return request({ url: `/ai/documents/${id}`, method: 'delete' })
}


/** Extraction preview item (mirror of admin ExtractionItemView; id = re-numbered row id,
 *  requirementId non-null = already stored as a requirement.
 *  evidence/chunkId = AP-4 source anchor: evidence is the verbatim excerpt, chunkId points
 *  to the owning chunk row; chunkId null (legacy preview or unresolvable) degrades the
 *  "view source" action to disabled with a readable hint. */
export interface ExtractionItem {
  id: number
  title: string
  acceptanceCriteria: string
  chapter: string | null
  confidence: number | null
  selected: boolean
  requirementId: number | null
  /** Source anchor: verbatim excerpt from the document chunk (null = model gave none / legacy row) */
  evidence?: string | null
  /** Source anchor: owning chunk row id (null = not attributable, jump disabled) */
  chunkId?: number | null
}

/** One preceding neighbour beside a requested chunk (mirror of admin
 * ChunkNeighborView, WR batch : original text already trimmed to the
 * server-side context budget ai.doc.chunk-context-chars). */
export interface ChunkNeighbor {
  /** tp_document_chunk row id of the neighbour (evidence anchor) */
  id: number
  chapter: string
  seq: number
  content: string
}

/** Context door of one source chunk (mirror of admin ChunkContextView, WR
 * batch ; read via GET /ai/documents/{id}/chunks/{chunkId}): the chunk
 * verbatim plus its closest preceding neighbours and the position marker,
 * so a thin chunk can be explained with its surrounding original text. */
export interface DocumentChunk {
  chunkId: number
  chapter: string
  seq: number
  content: string
  /** length of content — why a sliver reads small */
  contentChars: number
  /** 1-based ordinal among the document's chunks */
  positionIndex: number
  /** closest preceding neighbours, ascending seq (may be empty) */
  preceding: ChunkNeighbor[]
  /** false when the id is not in the document's current chunk set (stale
   *  after a re-parse, or foreign): an expected state answered as 200 with
   *  no content, so the door can open on its recorded-excerpt fallback */
  found?: boolean
}

/** One uncovered source chunk with the coverage ledger's own reason (mirror
 * of admin ExtractionGapView, WR batch : the residual-gap banner reads
 * its "why" straight from these rows — fields must not be dropped). */
export interface ExtractionGap {
  chunkId: number
  /** in-document order; null when the chunk row is gone */
  seq: number | null
  chapter: string | null
  /** residual-account sentence from the ledger ('' when absent) */
  note: string
  /** ledger evidence ('' for pending steps) */
  evidence: string
  /** source size of the chunk row (0 when the row is gone) */
  contentChars: number
}

/** One landed stage step of a running extraction (mirror of admin ExtractionStageView;
 * DD batch : the in-progress face answers "which step, when did it move last".
 * code = start / group-i / bucket-j / reconcile-r; label = server-authored step text
 * landed by ai-service (authoritative wording, shown verbatim); at = last landing time. */
export interface ExtractionStage {
  code: string
  label: string
  at: string
}

/** Fidelity ledger row echoed for the current extraction generation (
 * ; mirror of admin ExtractionFidelityView — all numbers are booked by
 * the server at the single call seam, the client only formats them). */
export interface ExtractionFidelity {
  chunkCount: number
  nodeCount: number
  injectedTokens: number
  sourceTokens: number
  /** injected/source in percent — how much of the source the run actually read. */
  retentionPercent: number
  distinctChapters: number
  itemCount: number
  anchoredItemCount: number
  /** Share of items carrying a server-side evidence anchor, in percent. */
  anchorRatePercent: number
  estimateTokens: number
  usagePromptTokens: number
  /** estimate-vs-provider drift of the injected package, in percent. */
  estDriftPercent: number
  plainJsonTokensPerItem: number
  billedTokensPerItem: number
  summaryCalls: number
  reconcileCalls: number
}


export interface ExtractionPreview {
  documentId: number
  status: number
  extractError: string | null
  /** Low-confidence review-flag threshold echoed by the server (single source =
   *  hot-updatable config key); null when the backend does not echo it yet. */
  reviewFlagThreshold: number | null
  /** Coverage-ledger pending steps of this generation (0 = fully covered). */
  uncoveredChunkCount: number
  /** Source chunk row ids behind the pending steps (ascending). */
  uncoveredChunkIds: number[]
  /** Per-chunk reason rows of the pending coverage steps ( segment A:
 * the residual-gap banner answers "why" straight from these rows). Empty
 * on backends without the ledger half — the banner then keeps its count
 * only, never an invented reason. */
  uncoveredChunks?: ExtractionGap[]
  /** Missing review dimensions of the whole preview (server-computed advice:
   *  exception-flow / boundary / non-functional minus what items already cover).
   *  Absent on backends without the suggestion half — treated as no advice. */
  dimensionSuggestions?: string[]
  /** Stage ledger of the current generation in landing order . Empty on
 * backends without the stage half — the hint degrades to the plain spinner. */
  stageLedger?: ExtractionStage[]
  /** Server-side clock of the most recent landed stage (null = no stage yet). */
  lastActivityAt?: string | null
  /** Fidelity ledger of the current generation : how much of the
 * source the extraction actually read, anchor fill and measured per-item
 * coefficients. Null before the run lands its ledger row (or on backends
 * without the echo) — the view then shows nothing, never a guessed number. */
  fidelity?: ExtractionFidelity | null
  page: number
  size: number
  total: number
  items: ExtractionItem[]
}

/** Confirm-store result (tri-state door): drafted = update-proposal cards issued */
export interface ConfirmExtractionResult {
  createdCount: number
  skipped: number
  drafted: number
  /** Row ids of the requirement points created by this confirm ( handoff
 * anchor for the "generate cases for the N new points" CTA; replay = empty). */
  createdRequirementIds: number[]
}

/** Per-item reviewer disposition names mirrored from the core enum */
export type ExtractionDispositionName = 'KEEP_AS_IS' | 'FORCE_NEW'

/** Submit an extraction run (async dispatch returns status=3 immediately; a
 * run already in progress comes back as a readable 409). modelHint overrides
 * the routed model; dimensionHint asks for a targeted supplemental pass on one
 * suggested dimension (-3b, whitelist names only — others are rejected
 * with a readable message); chunkIds is the directed-supplement
 * whitelist (run exactly one round over the listed chunks, empty = full run;
 * ids outside this document are rejected server-side before dispatch). */
export function extractDocument(
  id: number,
  modelHint?: string,
  dimensionHint?: string,
  chunkIds?: number[],
): Promise<DocumentView> {
  return request({ url: `/ai/documents/${id}/extract`, method: 'post', data: { modelHint: modelHint || null, dimensionHint: dimensionHint || null, chunkIds: chunkIds && chunkIds.length > 0 ? chunkIds : null } })
}


export function getExtraction(id: number, page = 1, size = 20): Promise<ExtractionPreview> {
  return request({ url: `/ai/documents/${id}/extraction`, method: 'get', params: { page, size } })
}

/** Explicitly cancel a running extraction (POST /ai/documents/{id}/extraction-cancel).
 *  The server rotates the document's extraction generation and writes the doc back to
 *  status=1 with a human-readable extractError; the in-flight run notices the rotation
 *  at its next stage checkpoint and stops writing, so nothing lands after the cancel. */
export function cancelExtraction(id: number): Promise<DocumentView> {
  return request({ url: `/ai/documents/${id}/extraction-cancel`, method: 'post' })
}

/**
 * Read one source chunk for the jump-back-to-source surface (AP-4;
 * GET /ai/documents/{id}/chunks/{chunkId}). Read gate = project member (same as
 * document detail). The server resolves the chunk inside the document's own
 * chunk list, so a foreign or stale chunk id comes back as a 200 with
 * found=false and no content (: fallback is an expected state, not an
 * error) — do not re-implement that check on the client.
 */
export function getDocumentChunk(id: number, chunkId: number): Promise<DocumentChunk> {
  return request({ url: `/ai/documents/${id}/chunks/${chunkId}`, method: 'get' })
}

/** Confirm-store through the tri-state door (items carry only id semantics;
 *  explicit dispositions close KEEP_AS_IS / FORCE_NEW rows; rows without a
 *  choice keep the default update-proposal-card path; source=2 anchors doc:{id}). */
export function confirmExtraction(
  id: number,
  items: ExtractionItem[],
  dispositions?: Record<number, ExtractionDispositionName>,
): Promise<ConfirmExtractionResult> {
  return request({ url: `/ai/documents/${id}/confirm-extraction`, method: 'post', data: { items, dispositions: dispositions ?? null } })
}

/** Full-store door : the server lands every preview item in one core
 * transaction, so pagination in this viewer can never cap what lands. Same
 * gates as the checkbox door; replaying it lands nothing new (idempotent). */
export function confirmAllExtraction(id: number): Promise<ConfirmExtractionResult> {
  // Bulk landing is one server-side transaction over the whole preview and may
  // legitimately run past the 30s global axios window; the admin forward leg
  // carries a 360s budget (startup key ai.document.confirm-all-forward-read-timeout-seconds),
  // so this call must outlast it rather than abort first.
  return request({ url: `/ai/documents/${id}/confirm-all-extraction`, method: 'post', timeout: 390000 })
}


export function docStatusTag(status: number): 'info' | 'success' | 'danger' | 'warning' {
  if (status === 1 || status === 4) return 'success'
  if (status === 2) return 'danger'
  if (status === 3) return 'warning'
  return 'info'
}


export function fmtDocSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}
