package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * One ledger row's health reading for the red/yellow/green column of the
 * page element ledger (mirror of the core step-health account; the board
 * maps badge to color and shows the about-to-break mark beside it).
 */
public record WebElementHealthRow(long elementId, boolean reached, int missRatePercent,
                                  String badge, boolean aboutToBreak) {
}
