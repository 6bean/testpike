package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoUpgradeWindowView(
        LocalDateTime slotStart,
        int busyBatches,
        boolean recommended,
        String canaryHint) {
}
