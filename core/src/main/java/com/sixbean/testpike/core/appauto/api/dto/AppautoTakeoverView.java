package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoTakeoverView(
        long id,
        long deviceId,
        long projectId,
        Long taskId,
        long requesterId,
        long occupierId,
        String state,
        String reason,
        Long decidedBy,
        LocalDateTime decidedAt,
        LocalDateTime expiresAt,
        String recordingRef,
        LocalDateTime createdAt) {
}
