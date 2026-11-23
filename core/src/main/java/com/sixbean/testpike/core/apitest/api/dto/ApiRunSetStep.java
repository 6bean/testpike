package com.sixbean.testpike.core.apitest.api.dto;

/**
 * One child run row inside a collection-run detail .
 * <p>Joined from tp_apitest_run to tp_apitest_request so the run-set panel can
 * render name/method/status without a second round-trip per step.
 * <p> protocol + assertCount ride along so the display face can
 * attribute a passed row honestly — the "N assertions passed" line is only
 * rendered when assertions actually existed; a ws row that merely connected
 * gets the connected-no-assertions wording instead.
 *
 * @param seqNo 1-based tree order pinned at launch (detail rows sort by this)
 * @param status 0=pending 1=passed 2=assert-failed 3=execution-error
 * @param durationMs executor-reported wall time; null until the result lands
 * @param errorMsg honest failure text (empty when the row never failed)
 * @param protocol transport face of the source request (http | ws)
 * @param assertCount assertion rows landed in assert_detail (0 = none configured)
 */
public record ApiRunSetStep(long runId, long requestId, int seqNo, String name,
                            String method, int status, Long durationMs, String errorMsg,
                            String protocol, int assertCount) {
}
