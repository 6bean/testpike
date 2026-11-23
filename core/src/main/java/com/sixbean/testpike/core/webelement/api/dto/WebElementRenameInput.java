package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Rename body for one ledger element . The row keeps its
 * page and anchors; only the human-facing name changes, and dispatch
 * references resolve on the new name from the next read onwards.
 *
 * @param name the new element name, unique inside the row's page
 */
public record WebElementRenameInput(String name) {
}
