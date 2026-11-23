package com.sixbean.testpike.core.batch.api.dto;

/**
 * This is a batch view consisting of the tp_exec_batch row, member count, and human-readable status.
 *
 * @param id is the batch ID
 * @param projectId is the owning project
 * @param batchNo is the batch number
 * @param name is the name
 * @param envId is the environment, nullable
 * @param envName is the environment name for redundant display
 * @param status has 8 states: 1 draft, 2 pending execution, 3 environment unavailable, 4 executing, 5 paused, 6 completed, 7 partial failure, and 8 canceled
 * @param statusLabel is the human-readable Chinese status label for UX
 * @param type is 1 official, 2 temporary, or 3 template
 * @param templateId is the source template
 * @param caseCount is the member case count
 * @param queuePos is the queue position, displayed and populated
 * @param createdBy is the creator
 * @param createdAt is the creation time
 * @param startedAt is the start time
 * @param finishedAt is the finish time
 * @param version is the optimistic lock version number incremented on status changes, stale submissions return 40901, and existing rows are backfilled with 0 during migration
 * @param message is the operation message such as a human-readable notice about soft-deleted cases removed during template reuse, and is null for list and detail views
 * @param notifyFeishu is the batch-level group notification switch introduced by migration: 1 forces sending, and 0 falls back to project-level
 * @param poolTagId is the resource pool tag ID; null means no pool is selected and all pools can claim
 * @param browser is the unified execution browser for the batch, chromium/firefox/webkit; null means unspecified and uses the default
 * @param runtimeChangeCount is the number of source-table parameter changes during execution; after snapshot pinning, changes within the batch lifecycle affect only the next batch
 *                           This field counts changes that occurred N times but were not adopted by this batch, serving as a human-readable data source for details; terminal batches do not increase further
 * @param cleanAppData is the automatic device cleanup flag for batch runs introduced by migration: 1 clears data and uninstalls on occupied devices after the batch to restore the initial state
 *                           This is best-effort; 0 means off by default and causes no accidental cleanup of existing rows
 * @param envOngoingEpisode is the ongoing environment monitoring event for reverse linkage, referenced by the detail-page top banner
 *                           This is the data source for "environment X has been red for N minutes"; populated only on detail read paths, with lists null to avoid N+1
 *                           This is null when there is no environment or no ongoing event; red duration in minutes is derived by consumers from startedAt, and no further probing is allowed
 * @param cleanAppData is the automatic device cleanup flag for batch runs introduced by migration: 1 clears data and uninstalls on occupied devices after the batch to restore the initial state
 *                           This is best-effort; 0 means off by default and causes no accidental cleanup of existing rows; bridge and frontend consumption is a batch form switch
 */
public record BatchInfo(long id, long projectId, String batchNo, String name, Long envId,
                        String envName, int status, String statusLabel, int type, Long templateId,
                        long caseCount, Integer queuePos, Long createdBy, String createdAt,
                        String startedAt, String finishedAt, int version, String message,
                        int notifyFeishu, Long poolTagId, String browser, int runtimeChangeCount,
                        com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView envOngoingEpisode,
                        int cleanAppData) {

    /** The legacy 23-parameter compatibility constructor treats the missing cleanup flag as 0/off, and existing callers require no changes */
    public BatchInfo(long id, long projectId, String batchNo, String name, Long envId,
                     String envName, int status, String statusLabel, int type, Long templateId,
                     long caseCount, Integer queuePos, Long createdBy, String createdAt,
                     String startedAt, String finishedAt, int version, String message,
                     int notifyFeishu, Long poolTagId, String browser, int runtimeChangeCount,
                     com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView envOngoingEpisode) {
        this(id, projectId, batchNo, name, envId, envName, status, statusLabel, type, templateId,
                caseCount, queuePos, createdBy, createdAt, startedAt, finishedAt, version, message,
                notifyFeishu, poolTagId, browser, runtimeChangeCount, envOngoingEpisode, 0);
    }

 /** These are Chinese status labels for the 8 batch states, sourced from the same state-machine display */
    public static String statusLabel(int status) {
        return switch (status) {
            case 1 -> "草稿";
            case 2 -> "待执行";
            case 3 -> "环境不可用";
            case 4 -> "执行中";
            case 5 -> "已暂停";
            case 6 -> "已完成";
            case 7 -> "部分失败";
            case 8 -> "已取消";
            default -> "未知";
        };
    }
}
