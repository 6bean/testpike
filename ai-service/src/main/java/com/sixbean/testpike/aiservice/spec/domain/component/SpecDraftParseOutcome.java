package com.sixbean.testpike.aiservice.spec.domain.component;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing one LLM answer for interface drafts: the entries that
 * satisfy the draft contract plus the count of entries dropped for violating
 * it (a missing path). The count rides along so the orchestrator can name the
 * gap in human words instead of failing the whole document run into retries
 * and the dead-letter queue.
 */
public record SpecDraftParseOutcome(List<Map<String, Object>> items, int rejectedCount) {
}
