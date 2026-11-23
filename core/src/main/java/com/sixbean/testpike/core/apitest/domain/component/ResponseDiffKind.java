package com.sixbean.testpike.core.apitest.domain.component;

/**
 * Four-color classification of a single leaf difference between two JSON
 * responses (W-c5). ADDED/REMOVED drive the presence colors, TYPE_CHANGED the
 * structure color, VALUE_CHANGED the value color — the front-end maps these
 * onto the four swatches, the kernel owns the vocabulary.
 */
public enum ResponseDiffKind {
    ADDED,
    REMOVED,
    TYPE_CHANGED,
    VALUE_CHANGED
}
