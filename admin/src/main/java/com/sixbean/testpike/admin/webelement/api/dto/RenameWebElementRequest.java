package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Rename body for one ledger element . The manage gate
 * and the row's owning project come from the stored row, never from this
 * payload; the revision token carries the caller's read version so a stale
 * dialog refuses with the shared 40901 posture.
 */
public record RenameWebElementRequest(String name, int expectedRevision) {
}
