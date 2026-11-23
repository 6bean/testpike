package com.sixbean.testpike.core.document.api.dto;

/**
 * Per-item human disposition on the re-extract confirmation door.
 *
 * <p>When re-extraction meets an existing requirement with the same title,
 * the reviewer resolves the item with one of the three buttons; the first
 * one (accept update) is handled by the draft-confirmation path itself and
 * never appears here. The other two travel with the confirm request as a
 * map from preview item id to disposition name; null or unknown values keep
 * the default update-proposal-card behaviour.
 */
public enum ExtractionDisposition {

    /** Keep the existing row untouched: no new row, no card, item closes as skipped. */
    KEEP_AS_IS,

    /** The reviewer vouches this is genuinely a new requirement: land the row and
     *  record an override audit naming the blocked homonym match. */
    FORCE_NEW;

    /** Lenient parse: null/unknown never blocks the default card path. */
    public static ExtractionDisposition parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (ExtractionDisposition value : values()) {
            if (value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        return null;
    }
}
