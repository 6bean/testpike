package com.sixbean.testpike.aiservice.aichat.infrastructure.remote;

/**
 * Narrow screenshot-artifact mirror : one row of core's
 * existing GET /internal/executors/tasks/{id}/screenshots projection. Only the
 * fields the dialog vision seam needs travel — the presigned GET url is what
 * the vision model consumes (15-minute TTL, private bucket never exposed),
 * objectKey is the honest trace anchor named in state frames.
 *
 * @param taskId execution task the artifact belongs to
 * @param objectKey S3 object key (tp_exec_task.screenshots_json entry)
 * @param url presigned GET url the model provider fetches
 */
public record TaskScreenshotView(long taskId, String objectKey, String url) {
}
