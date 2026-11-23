package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Bridge mirror of the core survey acknowledgement (field names follow the
 * core record verbatim — the wire contract, never an invented alias).
 */
public record SurveyBridgeResult(Long sessionId, String startUrl) {
}
