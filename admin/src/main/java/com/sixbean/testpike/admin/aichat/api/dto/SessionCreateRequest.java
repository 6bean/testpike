package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * User-facing request of the dialog-session door .
 * <p>Deliberately carries <em>no</em> user id: the operator is pinned from the
 * login context in the controller, so the browser can never speak for a
 * different user (horizontal-access red line). Validation of title bounds and
 * the context whitelist stays in core (single point) — admin forwards
 * unchanged.
 *
 * @param projectId project segment the conversation belongs to (H1-guarded)
 * @param title session title (core refuses blank)
 * @param source entry surface: 1 dock / 2 standalone page / 3 badge
 * deep-link; null defaults to the dock surface
 * @param contextJson whitelist context object as JSON (route/object ids);
 * anything but a JSON object is refused by core
 */
public record SessionCreateRequest(long projectId, String title, Integer source, String contextJson) {
}
