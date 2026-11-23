package com.sixbean.testpike.common.mq;

import java.util.List;

/**
 * One buffered hit batch on the wire (exchange {@code testpike.events},
 * routing key {@code mock.hit.batch}; . The batch id is
 * the consumer dedup token: a redelivery of the same batch must land exactly
 * once in tp_mock_hit_log, so core consumes it behind the MQ idempotent
 * double guard like every other queue.
 *
 * @param spaceKey routing handle of the space these rows were served under
 * @param ruleVersion the pinned rule version at serve time (ledger rows stay
 * attributable to the exact rule state that answered)
 * @param batchId unique id of this batch (dedup key material)
 * @param rows already-masked observation rows
 * @param generatedAt epoch millis of batch assembly (clock drift display only)
 */
public record MockHitBatchMessage(String spaceKey, String ruleVersion, String batchId,
                                  List<MockHitRowMessage> rows, long generatedAt) {
}
