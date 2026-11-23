package com.sixbean.testpike.core.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoUpgradeWindowView(
        LocalDateTime slotStart,
        int busyBatches,
        boolean recommended,
        String canaryHint) {
}
