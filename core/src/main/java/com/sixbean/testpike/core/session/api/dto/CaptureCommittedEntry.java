package com.sixbean.testpike.core.session.api.dto;

/**
 * One checked candidate with its freshly read value, sent by the executor only
 * transits memory on its way to the vault — this record is never written to
 * the session payload, any log line or any PostgreSQL column (red lines 3/4).
 *
 * @param expiresAtEpochSecond raw expiry seen at read time, nullable
 */
public record CaptureCommittedEntry(
        String kind,
        String name,
        String origin,
        String value,
        Long expiresAtEpochSecond) {
}
