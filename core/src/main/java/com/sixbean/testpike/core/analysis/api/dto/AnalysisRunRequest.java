package com.sixbean.testpike.core.analysis.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body of the internal analysis door .
 * <p>The project segment and the operator never travel here — the server injects
 * them (identity seam rule of the dialog chain: a model-authored body carries
 * no identity keys). The filters travel as an unparsed {@link JsonNode} on
 * purpose so the dispatcher can refuse a key the named function does not
 * declare instead of silently dropping it and printing whole-project numbers
 * under a narrowed question.
 *
 * @param function registry name of a read-only analysis function
 * @param args optional filter object (absent = whole project)
 */
public record AnalysisRunRequest(String function, JsonNode args) {
}
