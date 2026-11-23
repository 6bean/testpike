package com.sixbean.testpike.core.aichat.api.dto;

/** Terminal status of a turn: only done/error are accepted (checked in the service). */
public record CloseMessageRequest(String status) {
}
