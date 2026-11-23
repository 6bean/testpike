package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Dispatch receipt (AW4; mirrors core DispatchReceipt, echoed untouched so
 * the page can show exactly what core decided — how many samples entered the
 * queue and which judge model took the run).
 *
 * @param dispatched    number of samples enqueued for judging
 * @param judgeProvider provider that will act as the judge
 */
public record JudgeDispatchReceiptView(int dispatched, String judgeProvider) {
}
