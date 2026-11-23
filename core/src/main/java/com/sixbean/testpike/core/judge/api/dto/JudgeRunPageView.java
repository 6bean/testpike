package com.sixbean.testpike.core.judge.api.dto;

import java.util.List;

/**
 * Evaluation-run history page view (AW facade ; direct read of
 * tp_judge_run rows — zero judgement recomputation, every gate decision
 * stays single-source in JudgeRunService). Page size is bounded 1..200
 * following the TREND_LIMIT_MAX precedent so the list can never turn into
 * an unbounded scan.
 *
 * @param page 1-based page number (echoed)
 * @param size page size (echoed)
 * @param total total rows matching the filters (for pager controls)
 * @param runs page rows in newest-first order
 */
public record JudgeRunPageView(int page, int size, long total, List<RunView> runs) {
}
