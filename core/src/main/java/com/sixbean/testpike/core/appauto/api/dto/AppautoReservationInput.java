package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoReservationInput(
        Long projectId,
        long deviceId,
        Long batchId,
        long holderUserId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String reason) {
}
