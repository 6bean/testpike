package com.sixbean.testpike.aiservice.insight.api.dto;

/**
 * Failure clustering read model: one failed/timeout task of the finalized batch
 * projected for grouping (AO-7 stage 2b; status 4/5 read basis shared with
 * the batch report snapshot). aiClass/aiClassLabel carry the C3 AI attribution
 * when it has landed; errorType/errorTypeLabel is the executor's own four-class
 * report used as the honest fallback while attribution is still pending.
 *
 * @param aiClass AI attribution class (1-3, C3 scale), null = not attributed yet
 * @param aiClassLabel plain-word label of the AI class (null when unattributed)
 * @param errorType executor error category (1-4 per the tp_batch_case scale), may be null
 * @param errorTypeLabel plain-word label of the executor category
 * @param caseTitle failing case title (representative sample of its cluster)
 */
public record FailureRow(Integer aiClass, String aiClassLabel,
                         Integer errorType, String errorTypeLabel, String caseTitle) {

    /** Clustering group key: AI attribution wins; otherwise the executor category; missing both = key 0. */
    public String groupKey() {
        return aiClass != null ? "ai:" + aiClass : "exec:" + (errorType == null ? 0 : errorType);
    }

    /** Label shown on the insight card for this row's group. */
    public String displayLabel() {
        return aiClass != null && aiClassLabel != null ? aiClassLabel : errorTypeLabel;
    }

    /** True when this row had no AI attribution and was grouped by executor category. */
    public boolean unattributed() {
        return aiClass == null;
    }
}
