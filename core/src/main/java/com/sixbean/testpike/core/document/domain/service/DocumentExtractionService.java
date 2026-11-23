package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;

import java.util.List;
import java.util.Map;


public interface DocumentExtractionService {

    
    DocumentInfo extract(long operatorId, long documentId, String modelHint);

    /**
 * Extract with a targeted dimension hint :
 * the hint is whitelisted against the suggester vocabulary before dispatch
 * and reaches the ai-service only through the MQ message. Null/blank hint =
 * identical behaviour to the three-argument entry.
 */
    DocumentInfo extract(long operatorId, long documentId, String modelHint, String dimensionHint);

    /**
 * ③: preempt-flagged variant. When the previous generation is still
 * live and preempt=true, admit and write a fresh generation token (the old
 * run self-aborts at its ai-service generation seams); preempt=false is
 * equivalent to the four-argument call above.
 */
    DocumentInfo extract(long operatorId, long documentId, String modelHint,
                         String dimensionHint, boolean preempt);

    /**
 * segment B: chunk-level directed supplement variant. A non-empty
 * {@code chunkIds} whitelist makes the consumer run exactly one round over
 * those chunks (the preview's "re-extract this chunk" door), merged into
 * the same generation's preview idempotently. Validation runs BEFORE any
 * status drift: a chunk foreign to this document rejects with
 * VALIDATION_ERROR naming the offending id and the row keeps its state.
 * Null/empty whitelist = identical behaviour to the five-argument entry.
 */
    DocumentInfo extract(long operatorId, long documentId, String modelHint,
                         String dimensionHint, boolean preempt, java.util.List<Long> chunkIds);

    /**
 * ③: explicit cancel of a running extraction (02 POST
 * /ai/documents/{id}/extraction-cancel). Rotates the generation token so
 * the in-flight run self-aborts at its seams and any late writeback stays
 * 409-guarded, then settles the row 3 -&gt; 1 with a human-readable cancel
 * reason. Cancelling a document that is not extracting rejects honestly
 * with zero writes (idempotent no-op posture, R28 sibling).
 */
    DocumentInfo cancelExtraction(long operatorId, long documentId);

    /** ④: read the current generation token (sole path behind the self-check endpoint). */
    String currentExtractToken(long documentId);

    
    ExtractionPreview extraction(long documentId, int page, int size);

    /**
 * Cursor window of the live run's process events : rows of
 * tp_ai_task_event landed after {@code cursor}, ascending by row id, so a
 * viewer tails a long AI job like a live log instead of re-reading whole
 * pages. Replaying the same cursor returns exactly the same window. The
 * window size honours the hot key ai.stream.chunk-size (10~1000 clamp);
 * an explicit client limit is clamped into the same range. Read-only echo
 * with the stage-ledger door posture: an unreachable door honestly
 * answers an empty window, never fails the call.
 */
    java.util.List<com.sixbean.testpike.core.document.api.dto.ExtractionEventRow>
            extractionEvents(long documentId, long cursor, Integer limit);

    /**
     * Same window door with an optional historical run token: a non-blank
     * runToken replays that run's frames (the revisit door of the run
     * history), an absent token keeps the latest-run semantics unchanged.
     */
    java.util.List<com.sixbean.testpike.core.document.api.dto.ExtractionEventRow>
            extractionEvents(long documentId, long cursor, Integer limit, String runToken);

    /**
 * Process event tail of the newest channel-B spec-extraction attempt
 * for a document : unlike the requirement
 * extraction there is no task-state row holding the run token, so the
 * window resolves the latest run from the ledger itself (the attempt
 * that landed the newest row wins the replay). Same read-only echo
 * posture as {@link #extractionEvents}: an empty or unreachable face
 * honestly answers an empty window, never fails the caller.
 */
    java.util.List<com.sixbean.testpike.core.document.api.dto.ExtractionEventRow>
            specExtractionEvents(long documentId, long cursor, Integer limit);

    /**
     * Same spec tail window with an optional historical run token (the
     * revisit door of R67): a non-blank runToken replays that attempt's
     * frames, an absent token keeps the standing latest-run semantics
     * byte-identical.
     */
    java.util.List<com.sixbean.testpike.core.document.api.dto.ExtractionEventRow>
            specExtractionEvents(long documentId, long cursor, Integer limit, String runToken);

    /**
     * Per-run revisit door of the spec extraction process ledger (R67):
     * aggregated run rows for this document, newest run first — the same
     * shared shape as the requirement-extraction history door, no second
     * view type. An unknown or never-extracted document honestly answers
     * an empty list.
     */
    java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>
            specExtractionRuns(long documentId, Integer limit);

    /**
 * Run-history revisit door (R67): per-run aggregation of this document's
 * extraction process ledger, newest run first. The preview dialog answers an
 * empty replay window with these rows so aged-out frames stay distinguishable
 * from a run that produced nothing. Unknown documents refuse with the
 * not-found wording before the ledger is touched; a blank limit defers to the
 * door's hot default window.
 */
    java.util.List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>
            extractionRuns(long documentId, Integer limit);

    
    /**
     * Tri-state confirm door: same as the plain confirm plus per-item human
     * dispositions keyed by preview item id. KEEP_AS_IS closes a blocked item
     * without a card; FORCE_NEW lands it with an override audit; null/unknown
     * keeps the default update-proposal-card behaviour.
     */
    ConfirmExtractionResult confirmExtraction(long operatorId, long documentId,
                                              List<ExtractionItem> selectedItems,
                                              Map<Long, String> dispositions);

    ConfirmExtractionResult confirmExtraction(long operatorId, long documentId,
                                              List<ExtractionItem> selectedItems);

    /**
 * Full-store confirm : the server enumerates every item of the
 * current extraction preview itself — the caller sends no id list, so a
 * paged UI can never cap the landing (the paged checkbox door stays
 * untouched and both entries coexist). Items already landed stay skipped,
 * homonyms still mint update-proposal cards: every gate of the plain
 * confirm door applies unchanged because this method delegates to it.
 */
    ConfirmExtractionResult confirmAllExtraction(long operatorId, long documentId);

    
    void writeExtractionResult(long documentId, List<ExtractionItem> items, String extractToken);

    
    void markExtractionFailed(long documentId, String errorMsg, String extractToken);
}
