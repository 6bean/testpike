package com.sixbean.testpike.core.delreq.api.dto;

import java.time.LocalDateTime;


public record DeleteRequestInfo(
        long id,
        int targetType,
        String targetTypeLabel,
        long targetId,
        long projectId,
        String targetSummary,
        String reason,
        String rejectReason,
        long applicantId,
        int status,
        String statusLabel,
        Long approverId,
        LocalDateTime handledAt,
        LocalDateTime createdAt) {
}
