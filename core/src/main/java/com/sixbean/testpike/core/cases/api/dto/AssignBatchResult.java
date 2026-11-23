package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Batch assignment result (Interface Design 3.6: assigned list, atomic semantics — roll back the entire operation if any item fails).
 *
 * @param assigned Per-item assignment results (including target member distribution when automatic load balancing is enabled).
 */
public record AssignBatchResult(List<AssignResult> assigned) {
}
