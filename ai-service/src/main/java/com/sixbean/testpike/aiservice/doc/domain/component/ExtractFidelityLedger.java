package com.sixbean.testpike.aiservice.doc.domain.component;

/**
 * Extraction fidelity ledger : the mechanical account that
 * answers, per extraction generation, how much of the source document the
 * extraction plane actually read. The live incident it prevents — 121,375
 * source tokens reaching the model as 4,924 (a 24.6x lossy summary path) with
 * zero anchors — was invisible because no plane booked "injected vs source".
 *
 * <p> (A2 single-source) generalizes the ledger beyond document
 * extraction: every row now carries a {@code taskType} plane tag and the
 * identity pair speaks (refId, runId), so the spec-extract and qa-gen doors
 * can book the same account instead of rejecting over-sized inputs.
 *
 * <p>Zero model calls by construction: every number is passed in by the
 * extraction run or summed at the single call seam
 * ({@code DocExtractServiceImpl.extractPlaneCall}, the one tp_ai_call_log
 * writer) into an {@link ExtractFidelityMeter}, so the ledger reconciles
 * against tp_ai_call_log with zero difference. The per-item coefficients
 * (plain JSON and thinking-inclusive) feed the output-budget rule of
 * / — factory values stay derived from measured runs, never guessed.
 *
 * <p>Plane attribution keys ride the existing tp_ai_call_log.function
 * vocabulary through {@link PlaneLabels} (the service seam passes its own
 * constants — single source, no second copy of the labels). This class only
 * owns the shapes and the arithmetic; SQL lives in
 * {@link ExtractFidelityLedgerStore}.
 */
public final class ExtractFidelityLedger {

    /** Plane tag of document requirement extraction — the factory default
     *  of tp_doc_extract_fidelity.task_type (kept in sync with V473). */
    public static final String TASK_DOC_EXTRACT = "doc-extract";

    /** Plane tag of the channel-B spec draft extraction door . */
    public static final String TASK_SPEC_EXTRACT = "spec-extract";

    /** Plane tag of the judge QA-generation door . */
    public static final String TASK_QA_GEN = "qa-gen";

    private ExtractFidelityLedger() {
    }

    /**
 * single-source reject note: when a fidelity booking is refused the
 * log names the plane, the reference row, the run and the root cause, and
 * states the consequence for the reader. It replaces the collapsed
 * (one format for all doors, no second copy).
 */
    public static String rejectNote(String plane, long refId, String runId, Throwable cause) {
        return "忠实账落账被拒 plane=" + plane + " refId=" + refId + " run=" + runId
                + " 根因=" + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                + " 后果=本轮忠实账缺失，保真读数（保留率/锚非空率）看不到这一轮，可在服务日志按本句检索";
    }

    /** One fidelity row of a generation (written once at run completion).
     *  Rates are percentages rounded to 2 decimals; a zero denominator
     *  honestly answers 0 instead of a fabricated green. */
    public record Entry(long refId, String runId, String taskType,
                        int chunkCount, int nodeCount,
                        int injectedTokens, int sourceTokens, double retentionPercent,
                        int distinctChapters, int itemCount, int anchoredItemCount,
                        double anchorRatePercent,
                        int estimateTokens, int usagePromptTokens, double estDriftPercent,
                        int plainJsonTokensPerItem, int billedTokensPerItem,
                        int summaryCalls, int reconcileCalls,
                        // Ticket 1: source tokens covered this generation by
                        // cache reuse (read-by-reuse face, never a booked
                        // call - the injected face stays the call-log truth).
                        int reusedSourceTokens) {

        private static final double EPS = 1e-9;

        /** Equality for the replay check compares the accounted numbers only;
         *  identity columns (reference row, generation token, task plane)
         *  scope the row. */
        @Override
        public boolean equals(Object other) {
            return other instanceof Entry e
                    && refId == e.refId
                    && runId.equals(e.runId)
                    && taskType.equals(e.taskType)
                    && chunkCount == e.chunkCount
                    && nodeCount == e.nodeCount
                    && injectedTokens == e.injectedTokens
                    && sourceTokens == e.sourceTokens
                    && Math.abs(retentionPercent - e.retentionPercent) < EPS
                    && distinctChapters == e.distinctChapters
                    && itemCount == e.itemCount
                    && anchoredItemCount == e.anchoredItemCount
                    && Math.abs(anchorRatePercent - e.anchorRatePercent) < EPS
                    && estimateTokens == e.estimateTokens
                    && usagePromptTokens == e.usagePromptTokens
                    && Math.abs(estDriftPercent - e.estDriftPercent) < EPS
                    && plainJsonTokensPerItem == e.plainJsonTokensPerItem
                    && billedTokensPerItem == e.billedTokensPerItem
                    && summaryCalls == e.summaryCalls
                    && reconcileCalls == e.reconcileCalls
                    && reusedSourceTokens == e.reusedSourceTokens;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(refId, runId, taskType, chunkCount, nodeCount,
                    injectedTokens, sourceTokens, retentionPercent, distinctChapters,
                    itemCount, anchoredItemCount, anchorRatePercent, estimateTokens,
                    usagePromptTokens, estDriftPercent, plainJsonTokensPerItem,
                    billedTokensPerItem, summaryCalls, reconcileCalls, reusedSourceTokens);
        }

        @Override
        public String toString() {
            return "fidelity ref=" + refId + " run=" + runId + " task=" + taskType
                    + " injected=" + injectedTokens + "/" + sourceTokens
                    + " retention=" + retentionPercent + "%"
                    + " anchors=" + anchoredItemCount + "/" + itemCount
                    + " reused=" + reusedSourceTokens;
        }
    }

    /**
     * Account one generation from the seam-summed usage plus the run's
     * structural numbers.
     *
     * @param refId            row the fidelity face belongs to (a document
     *                         today; spec/qa targets join in later pieces)
     * @param runId            generation token minted by core
     * @param taskType         plane attribution of the booked run
     *                         ({@link #TASK_DOC_EXTRACT} for document
     *                         extraction)
     * @param sourceTokens     estimated tokens of the WHOLE document (the truth
     *                         the extraction should read)
     * @param chunkCount       chunks shown to the run (storage face)
     * @param nodeCount        groups or final buckets the package was split into
     * @param distinctChapters distinct chapter paths over the chunks
     * @param meter            usage summed at the single call seam
     * @param itemCount        extracted requirement items of the generation
     * @param anchoredItems    items whose server-side evidence anchor is non-null
     * @param estimateTokens   estimate-side face of the same injected package
     *                         (endpoint tokenizer), for the drift account
     * @param labels           tp_ai_call_log.function vocabulary of the three
     *                         extraction planes, passed by the service seam
     */
    public static Entry entry(long refId, String runId, String taskType,
                              int sourceTokens, int chunkCount, int nodeCount,
                              int distinctChapters, ExtractFidelityMeter meter,
                              int itemCount, int anchoredItems, int estimateTokens,
                              PlaneLabels labels) {
        int injected = meter.promptOf(labels.extractPlane()) + meter.promptOf(labels.gapPlane());
        int reused = meter.reusedSourceTokens();
        // Reuse-scoped retention (ticket 1): a generation answering a bucket
        // from the proven cross-run cache covered that source scope without a
        // fresh call, so the coverage face counts reused tokens AS read while
        // the injected face stays exactly the booked calls. A leg with zero
        // reuse is byte-identical to the legacy arithmetic (reused = 0): the
        // whole-document floor semantics never loosen here.
        return new Entry(refId, runId, taskType,
                chunkCount, nodeCount,
                injected, sourceTokens, percent((long) injected + reused, sourceTokens),
                distinctChapters, itemCount, anchoredItems,
                percent(anchoredItems, itemCount),
                estimateTokens, injected,
                round2(100.0 * (estimateTokens - injected) / Math.max(injected, 1)),
                divide(meter.completionOf(labels.extractPlane())
                                - meter.reasoningOf(labels.extractPlane()),
                        itemCount),
                divide(meter.promptOf(labels.extractPlane())
                        + meter.completionOf(labels.extractPlane()), itemCount),
                meter.callsOf(labels.summaryPlane()),
                meter.callsOf(labels.gapPlane()),
                reused);
    }

    private static double percent(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : round2(100.0 * numerator / denominator);
    }

    private static int divide(long numerator, long denominator) {
        return denominator <= 0 ? 0 : (int) Math.round((double) numerator / denominator);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
