package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One locator anchor of a ledger element. Anchors are stored in the given
 * order: the runtime tries them top-down, so the strongest candidate should
 * come first. Value domain (type list and strength bounds) is enforced
 * service-side against the tp_web_element column comment contract.
 *
 * @param type     css | xpath | text | placeholder | alt | testid | rel
 * @param value    the locator expression itself
 * @param strength 0..1 quality weight used by ordering and self-healing
 * @param note     optional human note (why this anchor was chosen)
 */
public record WebElementAnchorInput(String type, String value, Double strength, String note) {
}
