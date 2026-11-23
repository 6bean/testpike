package com.sixbean.testpike.aiservice.webelement.api.dto;

/**
 * One survey candidate heading toward semantic naming (WE batch segment P3).
 * <p>{@code key} is the anchor identity string produced by the core surveyor
 * (dialect=value pairs joined by '|') — it doubles as the correlation handle:
 * names only travel back onto keys the request actually carried, so a model
 * inventing an unknown key can never inject a name into the ledger.
 */
public record SurveyCandidateDescriptor(String key, String text, String role, String nearby) {
}
