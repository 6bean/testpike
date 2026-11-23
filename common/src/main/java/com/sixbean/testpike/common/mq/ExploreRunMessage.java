package com.sixbean.testpike.common.mq;


public record ExploreRunMessage(long sessionId, long projectId, long operatorId) {
}
