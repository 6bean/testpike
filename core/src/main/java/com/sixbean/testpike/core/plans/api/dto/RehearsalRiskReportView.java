package com.sixbean.testpike.core.plans.api.dto;

import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;

import java.util.List;

/**
 * Rehearsal risk report card projected back into the AI conversation
 * .
 * <p>Single-source posture is the contract: the verdict is the pass-through
 * {@link CiVerdictView} computed by CiGatePolicy (the same source as the CI
 * badge and the plan-threshold override) and the coverage is the pass-through
 * {@link CoverageSummaryView} of the sufficiency service — this view never
 * recomputes a second denominator. The ledger counts and the failed-case
 * lines come from tp_batch_case rows as recorded.
 * <p>Rendered by the conversation with existing frame types; the card writes
 * nothing and flips no state.
 *
 * @param batchId rehearsal batch id read from the ledger
 * @param batchName batch name as stored
 * @param batchStatus batch status code (01 §3.13, raw value kept for machines)
 * @param batchStatusLabel batch status in the single-source enum wording, so the
 * answer never invents a colloquial label for a bare code
 * @param verdict gate verdict view passed through from CiGatePolicy
 * @param verdictDisplay one plain sentence over the pass-through verdict (rates
 * as percents, threshold source named in the operator's
 * own terms); a pending verdict passes the human phrase
 * through with no invented numbers
 * @param totalCases rows in the result ledger for this batch
 * @param passed result=1 count
 * @param failed result=2 count
 * @param skipped result=3 count (dependency-failed or manual skip)
 * @param notRun result=4 count
 * @param failedCases failed-case lines, capped at 30 (worst-scale display rule)
 * @param failedCasesTruncated true when more failed rows exist than shown
 * @param coverage requirement-coverage view passed through from the sufficiency service
 * @param coverageDisplay coverage in one on-screen sentence, single caliber: the
 * requirement side reads covered/total, the contract side
 * reads a percent or the honest "gate off / no contract
 * endpoints yet" wording — never the -1 sentinel number
 * @param caliberNote plain-language note on where each number comes from
 */
public record RehearsalRiskReportView(long batchId, String batchName, int batchStatus,
                                      String batchStatusLabel,
                                      CiVerdictView verdict, String verdictDisplay,
                                      long totalCases, long passed, long failed,
                                      long skipped, long notRun,
                                      List<CaseRiskLine> failedCases,
                                      boolean failedCasesTruncated,
                                      CoverageSummaryView coverage,
                                      String coverageDisplay,
                                      String caliberNote) {
}
