package com.sixbean.testpike.core.project.api.dto;

import java.math.BigDecimal;


public record ProjectUpdateInput(String name, String defaultLang,
                                 String defaultPriority, BigDecimal budgetMonthly,
                                 String jiraProjectId, Long testOwnerId, Long devOwnerId,
                                 String scriptStoragePath, String recordingStoragePath,
                                 Integer storageMode, Integer kbEnabled, Integer notifyFeishu,
                                 Integer poolQuotaPct, Integer pageInspectEnabled) {

    
    public ProjectUpdateInput(String name, String defaultLang,
                              String defaultPriority, BigDecimal budgetMonthly,
                              String jiraProjectId, Long testOwnerId, Long devOwnerId,
                              String scriptStoragePath, String recordingStoragePath,
                              Integer storageMode, Integer kbEnabled, Integer notifyFeishu,
                              Integer poolQuotaPct) {
        this(name, defaultLang, defaultPriority, budgetMonthly, jiraProjectId, testOwnerId,
                devOwnerId, scriptStoragePath, recordingStoragePath, storageMode, kbEnabled,
                notifyFeishu, poolQuotaPct, null);
    }

    
    public ProjectUpdateInput(String name, String defaultLang,
                              String defaultPriority, BigDecimal budgetMonthly,
                              String jiraProjectId, Long testOwnerId, Long devOwnerId,
                              String scriptStoragePath, String recordingStoragePath,
                              Integer storageMode, Integer kbEnabled, Integer notifyFeishu) {
        this(name, defaultLang, defaultPriority, budgetMonthly, jiraProjectId, testOwnerId,
                devOwnerId, scriptStoragePath, recordingStoragePath, storageMode, kbEnabled,
                notifyFeishu, null);
    }

    
    public ProjectUpdateInput(String name, String defaultLang,
                              String defaultPriority, BigDecimal budgetMonthly,
                              String jiraProjectId, Long testOwnerId, Long devOwnerId,
                              String scriptStoragePath, String recordingStoragePath,
                              Integer storageMode, Integer kbEnabled) {
        this(name, defaultLang, defaultPriority, budgetMonthly, jiraProjectId, testOwnerId,
                devOwnerId, scriptStoragePath, recordingStoragePath, storageMode, kbEnabled, null,
                null);
    }
}
