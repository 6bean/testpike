package com.sixbean.testpike.core.perf.domain.service.impl;

import java.util.List;
import java.util.Map;

/**
 * Outcome of the single-point draft trimming pass (B-11): the steps that the
 * scenario save gate would accept, plus one plain-word reason per dropped
 * step. Kept out of the service body (no inner types); consumed only inside
 * the orchestration leg to assemble the wizard-facing draft map.
 */
record PerfDraftTrimResult(List<Map<String, Object>> steps, List<String> reasons) {
}
