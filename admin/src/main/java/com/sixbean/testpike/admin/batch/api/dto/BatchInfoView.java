package com.sixbean.testpike.admin.batch.api.dto;


public record BatchInfoView(long id, long projectId, String batchNo, String name, Long envId,
                            String envName, int status, String statusLabel, int type, Long templateId,
                            long caseCount, Integer queuePos, Long createdBy, String createdAt,
                            String startedAt, String finishedAt, int version, String message,
                            int notifyFeishu, Long poolTagId, String browser, int runtimeChangeCount,
                            MonitorEpisodeView envOngoingEpisode, int cleanAppData) {

    
    public BatchInfoView(long id, long projectId, String batchNo, String name, Long envId,
                         String envName, int status, String statusLabel, int type, Long templateId,
                         long caseCount, Integer queuePos, Long createdBy, String createdAt,
                         String startedAt, String finishedAt, int version, String message,
                         int notifyFeishu, Long poolTagId, String browser, int runtimeChangeCount,
                         MonitorEpisodeView envOngoingEpisode) {
        this(id, projectId, batchNo, name, envId, envName, status, statusLabel, type, templateId,
                caseCount, queuePos, createdBy, createdAt, startedAt, finishedAt, version, message,
                notifyFeishu, poolTagId, browser, runtimeChangeCount, envOngoingEpisode, 0);
    }
}
