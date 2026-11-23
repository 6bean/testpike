package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Fidelity ledger echo of the current extraction generation (mirror of the
 * core ExtractionFidelityRow, same JSON shape — EE batch . The
 * user-facing extraction page reads retention / anchor fill / measured
 * per-item coefficients straight from this view, so no client may recompute
 * them from raw counters (single source = the ai-service ledger row).
 *
 * @param chunkCount source chunks the generation was packaged from
 * @param nodeCount groups / final buckets the package was split into
 * @param injectedTokens tokens the extraction planes actually read
 * @param sourceTokens tokens of the whole source document (truth baseline)
 * @param retentionPercent injected/source in percent
 * @param distinctChapters distinct chapter paths seen over the chunks
 * @param itemCount extracted requirement items of the generation
 * @param anchoredItemCount items whose server-side evidence anchor is non-null
 * @param anchorRatePercent anchored/item share in percent
 * @param estimateTokens estimate-side face of the same injected package
 * @param usagePromptTokens provider-reported counterpart of estimateTokens
 * @param estDriftPercent estimate-vs-usage drift of that package, in percent
 * @param plainJsonTokensPerItem measured plain-output tokens per produced item
 * @param billedTokensPerItem measured billed tokens per produced item
 * @param summaryCalls summary-plane calls of this generation
 * @param reconcileCalls gap-reconcile-plane calls of this generation
 */
public record ExtractionFidelityView(int chunkCount, int nodeCount,
                                     int injectedTokens, int sourceTokens,
                                     double retentionPercent, int distinctChapters,
                                     int itemCount, int anchoredItemCount,
                                     double anchorRatePercent,
                                     int estimateTokens, int usagePromptTokens,
                                     double estDriftPercent,
                                     int plainJsonTokensPerItem, int billedTokensPerItem,
                                     int summaryCalls, int reconcileCalls) {
}
