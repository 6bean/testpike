package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * Creation payload for a ledger row. The page arrives as a full URL; the
 * core service folds it into the canonical page key through the shared
 * path-normalization rule, so concrete paths and templates land on one row.
 * New elements always enter as candidate (state 0).
 */
public record CreateWebElementRequest(Long projectId, String name, String pageUrl,
                                      List<WebElementAnchor> anchors) {
}
