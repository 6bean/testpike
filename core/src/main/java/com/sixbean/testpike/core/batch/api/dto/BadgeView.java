package com.sixbean.testpike.core.batch.api.dto;

import java.math.BigDecimal;


public record BadgeView(long batchId, int status, String statusLabel, BigDecimal passRate) {

    
    public String passRatePhrase() {
        return com.sixbean.testpike.common.report.BadgeRatePhrase.of(passRate);
    }
}
