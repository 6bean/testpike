package com.sixbean.testpike.common.mq;

import java.util.List;


public record ExecFailedEventMessage(String eventType, Long caseId, Long projectId,
                                     Long operatorId, String action, String remark,
                                     Long assigneeId, List<Long> caseIds, Integer batchSize,
                                     Long occurredAt, Integer caseVersion,
                                     Long execTaskId, Integer execErrorType,
                                     String execErrorDetail, String execScreenshotKey) {
}
