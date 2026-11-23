package com.sixbean.testpike.core.cases.api.dto;

/**
 * One actionable transition out of a case status (flow guide, AO-9 E8).
 *
 * @param event        machine event code (pass / need_change / reject / edit_begin ...)
 * @param eventLabel   human name of the action
 * @param toStatus     resulting status code
 * @param toStatusLabel resulting status name, always taken from CaseStatus.label()
 * @param page         front-end route that performs the action (must exist in menuStructure;
 *                     never a client-guessed path — the dialog renders this field as-is)
 * @param queryKey     query parameter the target page consumes for prefill (null = plain open)
 */
public record FlowGuideAction(String event, String eventLabel, int toStatus, String toStatusLabel,
                              String page, String queryKey) {
}
