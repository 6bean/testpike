package com.sixbean.testpike.common.mq;


public record AiKbIngestMessage(long documentId, long projectId, Long operatorId,
                                Long occurredAt, String category) {
}
