package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record ReservationCreateRequest(
        Long projectId,
        Long deviceId,
        Long batchId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String reason) {
}
