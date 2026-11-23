package com.sixbean.testpike.core.appauto.domain.service.impl;

import java.time.LocalDateTime;


record UpgradeStepRow(String targetVersion, LocalDateTime upgradedAt) {
}
