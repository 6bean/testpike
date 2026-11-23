package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * One per-viewport-class anchor set of a ledger row (WE batch S4, design §8.2).
 * The row's top-level anchors stay the any/default viewport fallback; this
 * record mirrors the stored jsonb item shape
 * {@code {"viewport":"mobile|tablet|desktop","anchors":[...]}} so a responsive
 * page keeps ONE asset per element instead of one row per breakpoint class.
 *
 * @param viewport viewport class name the anchors were collected under
 * @param anchors  anchor set as stored (ordering is applied at dispatch time
 *                 by WebElementDispatchSnapshot, the single ordering source)
 */
public record WebElementVariantInput(String viewport, List<WebElementAnchorInput> anchors) {
}
