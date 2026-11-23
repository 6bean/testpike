package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoLeaseRunView(
        long id,
        long deviceId,
        long projectId,
        Long batchId,
        String holderExecutorId,
        String state,
        LocalDateTime acquiredAt,
        LocalDateTime expiresAt,
        LocalDateTime releasedAt,
        String releaseReason) {
}
