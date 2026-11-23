package com.sixbean.testpike.core.document.api.dto;

/**
 * Fidelity ledger row of the current extraction generation, echoed read-only
 * with the preview . The numbers land in
 * tp_doc_extract_fidelity through the ai-service write door at run completion
 * (single call seam, zero model calls); this face only replays them so the
 * extraction page can show how much of the source the run actually read.
 *
 * @param chunkCount source chunks the generation was packaged from
 * @param nodeCount groups / final buckets the package was split into
 * @param injectedTokens provider-reported prompt tokens the extraction
 * planes actually read (final extract + gap top-ups)
 * @param sourceTokens estimated tokens of the WHOLE source document the
 * extraction should read (the truth baseline)
 * @param retentionPercent injected/source in percent — the fidelity ratio
 * (the live incident: 4,924 of 121,375 = 4.06%)
 * @param distinctChapters distinct chapter paths seen over the chunks
 * @param itemCount extracted requirement items of the generation
 * @param anchoredItemCount items whose server-side evidence anchor is non-null
 * @param anchorRatePercent anchoredItemCount/itemCount in percent (the
 * closure contract expects 100)
 * @param estimateTokens estimate-side face of the same injected package
 * @param usagePromptTokens provider-reported counterpart of estimateTokens
 * @param estDriftPercent (estimate - usage)/usage of that package, in percent
 * — calibration stays visible, thresholds untouched
 * @param plainJsonTokensPerItem measured completion-minus-reasoning tokens per
 * produced item (output-side coefficient)
 * @param billedTokensPerItem measured prompt+completion tokens per produced
 * item (thinking-inclusive coefficient)
 * @param summaryCalls calls the summary plane made this generation
 * @param reconcileCalls calls the gap-reconcile plane made this generation
 */
public record ExtractionFidelityRow(int chunkCount, int nodeCount,
                                    int injectedTokens, int sourceTokens,
                                    double retentionPercent, int distinctChapters,
                                    int itemCount, int anchoredItemCount,
                                    double anchorRatePercent,
                                    int estimateTokens, int usagePromptTokens,
                                    double estDriftPercent,
                                    int plainJsonTokensPerItem, int billedTokensPerItem,
                                    int summaryCalls, int reconcileCalls) {
}
