package com.sixbean.testpike.core.cases.api.dto;

/**
 * Use case batch export result (P2; structurally identical to J3 ExportResultView plus a count hint field).
 * <p>Async threshold=2000 (P2 contract, different from J3 report 5000): when exceeded, async=true, taskId is passed.
 * The existing export task queue is tp_export_task, and the download endpoint reuses GET /reports/export-tasks/{id}/download.
 * Reuse the existing export queue and prohibit duplicate implementation.
 *
 * @param async true=asynchronous export for large data volumes (taskId polling + in-site message notification for download upon completion)
 * @param taskId export task id (has value when async=true; 0 when async=false)
 * @param fileName file name (for synchronous download, e.g., use_case_export_20260904.xlsx)
 * @param content Excel bytes (returned synchronously; Jackson serializes it to base64, and the frontend converts it to a Blob for download)
 * @param total actual export count (visible rows after H1 removal)
 * @param removed excluded count (invisible/deleted entities, prompt "N items removed"; 0=no removals)
 */
public record CaseExportResult(boolean async, long taskId, String fileName, byte[] content,
                               int total, int removed) {
}
