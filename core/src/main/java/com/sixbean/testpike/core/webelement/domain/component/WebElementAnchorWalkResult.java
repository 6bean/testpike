package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Walk outcome of {@link WebElementInlineAnchorWalk}: inline-anchor steps
 * grouped by their raw resolvable goto url, plus the steps that carry an
 * anchor but sit behind no resolvable page (honest no-guess bucket).
 */
public record WebElementAnchorWalkResult(LinkedHashMap<String, List<JsonNode>> byPage,
                                         List<JsonNode> unattributed) {
}
