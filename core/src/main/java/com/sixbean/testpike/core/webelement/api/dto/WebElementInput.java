package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * Creation payload for an element ledger row. The page is given as a full
 * URL; the service folds it into the canonical page key through the shared
 * path-normalization rule (single source, WE batch W4), so concrete paths
 * like /pets/123 and templates like /pets/{id} land on one row.
 * New elements always enter as candidate (state 0); execution feedback or a
 * human batch promotes them to confirmed.
 */
public record WebElementInput(Long projectId, String name, String pageUrl,
                              List<WebElementAnchorInput> anchors) {
}
