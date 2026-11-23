package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * One locator anchor of a ledger element, mirrored from the core value object
 * (type list and strength bounds are enforced server-side). Anchors keep the
 * caller's order: the runtime tries them top-down.
 *
 * @param type     css | xpath | text | placeholder | alt | testid | rel
 * @param value    the locator expression itself
 * @param strength 0..1 quality weight used by ordering and self-healing
 * @param note     optional human note (why this anchor was chosen)
 */
public record WebElementAnchor(String type, String value, Double strength, String note) {
}
