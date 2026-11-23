package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * One node of the case status graph (flow guide, AO-9 E8).
 *
 * @param code     status code (1~9)
 * @param label    status name, single-sourced from CaseStatus.label()
 * @param terminal whether the machine treats this status as terminal
 * @param actions  events that legally fire from this status (empty = nothing to do here)
 */
public record FlowGuideStateNode(int code, String label, boolean terminal,
                                 List<FlowGuideAction> actions) {
}
