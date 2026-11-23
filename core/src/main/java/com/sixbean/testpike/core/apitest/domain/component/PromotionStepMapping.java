package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Mapped output of the promotion step conversion (04 contract 2.1.2 carriers).
 *
 * @param expect the http.request expect object (status / body / durationMs / headers)
 * @param saveAs variable name captured from the response body (null = no extract
 *               row in the source assertion group; step-level single slot, which
 *               is why multiple extract rows are refused by the mapper)
 */
public record PromotionStepMapping(JsonNode expect, String saveAs) {
}
