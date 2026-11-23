package com.sixbean.testpike.core.webelement.domain.component;

/**
 * Columnar bucket key of the anchor stat account (WE , V550): one row
 * per (element, viewport label, anchor type). Keeping the viewport inside the
 * key is the columnar rule from design §8.2 — a desktop hit must never
 * inflate a broken mobile anchor. Standalone record file (no nested types).
 */
record WebElementAnchorStatKey(long elementId, String viewport, String anchorType) {
}
