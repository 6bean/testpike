package com.sixbean.testpike.api.ci.api.dto;

import com.sixbean.testpike.common.report.BadgeRatePhrase;

import java.math.BigDecimal;


public record CiBadgeView(long batchId, int status, String statusLabel, BigDecimal passRate) {

    
    public String passRatePhrase() {
        return BadgeRatePhrase.of(passRate);
    }
}
