package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoReservationView(
        long id,
        Long projectId,
        long deviceId,
        Long batchId,
        long holderUserId,
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
