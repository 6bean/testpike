package com.sixbean.testpike.admin.ai.api.dto;

/**
 * One process step of a running step-generation task as echoed by the core
 * event tail (admin mirror of core TaskEventRow). {@code cursor} is the
 * strictly increasing ledger row id — replaying the last seen cursor returns
 * exactly the same window. {@code level} is one of the viewer's filter values
 * (thinking / step / error); {@code detail} carries the machine-readable part
 * (chunk counts, token digest, failure codes); {@code landedAt} is the ISO
 * local timestamp string shown in the tail.
 */
public record AiTaskEventView(
        long cursor,
        String level,
        String summary,
        String detail,
        String landedAt) {
}
