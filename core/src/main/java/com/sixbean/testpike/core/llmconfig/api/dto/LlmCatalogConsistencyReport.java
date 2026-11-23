package com.sixbean.testpike.core.llmconfig.api.dto;

import java.util.List;

/**
 * Result value of the model-catalog consistency self-check (, the
 * read-only report of the startup probe).
 *
 * <p>Privacy invariant: fields carry row names and column labels only —
 * never plaintext key or endpoint values (the verdict rule compares
 * equality and must not echo either side). The record-rendered toString is
 * therefore safe to log.
 *
 * @param catalogEmpty no enabled generation row at all
 * @param uncalibratedModels names of enabled generation rows still sitting
 * on the blanket default prompt_limit_tokens
 * @param envMismatches drift findings between the default generation
 * row and the installation env triple, shaped
 * {@code "row-name|column-label"} — names only
 */
public record LlmCatalogConsistencyReport(boolean catalogEmpty,
                                          List<String> uncalibratedModels,
                                          List<String> envMismatches) {

    public LlmCatalogConsistencyReport {
        uncalibratedModels = List.copyOf(uncalibratedModels);
        envMismatches = List.copyOf(envMismatches);
    }

    /** Any finding at all means somebody should look at the catalog page. */
    public boolean hasIssues() {
        return catalogEmpty || !uncalibratedModels.isEmpty() || !envMismatches.isEmpty();
    }
}
