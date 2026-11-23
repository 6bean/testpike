package com.sixbean.testpike.admin.appauto.api.dto;

import java.time.LocalDateTime;


public record AppautoUpgradeFlakyLinkView(
        long executorId,
        String targetVersion,
        LocalDateTime upgradedAt,
        int beforeSamples,
        double beforePassRate,
        int afterSamples,
        double afterPassRate,
        double dropPoints,
        String advice) {
}
