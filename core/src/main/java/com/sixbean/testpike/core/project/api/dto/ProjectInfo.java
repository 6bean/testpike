package com.sixbean.testpike.core.project.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;


public record ProjectInfo(Long id, String name, String code, String defaultLang, String defaultPriority,
                          int status, BigDecimal budgetMonthly, Long createdBy,
                          LocalDateTime createdAt, LocalDateTime updatedAt, int role,
                          String jiraProjectId, Long testOwnerId, Long devOwnerId,
                          String scriptStoragePath, String recordingStoragePath,
                          int storageMode, int kbEnabled, int notifyFeishu, int classifyVision,
                          Integer poolQuotaPct, int pageInspectEnabled) {
}
