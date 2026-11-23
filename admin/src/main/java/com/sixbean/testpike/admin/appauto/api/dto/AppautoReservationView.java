package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoReservationView(
        Long id,
        Long projectId,
        Long deviceId,
        Long batchId,
        Long holderUserId,
        String holderName,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String state,
        String reason,
        Long approvedBy,
        LocalDateTime approvedAt,
        LocalDateTime releasedAt,
        String releaseReason,
        LocalDateTime createdAt) {
}
