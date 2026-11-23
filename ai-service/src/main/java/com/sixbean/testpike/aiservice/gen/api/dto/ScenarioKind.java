package com.sixbean.testpike.aiservice.gen.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Stage0 scenario kinds of the enumeration decomposition (, ruling
 * section 3 item 3): generation stops being an open-ended writing task and
 * becomes a constrained fill-in over these kinds. The wire codes are the
 * literals the model must emit; parsing is lenient about case and underscore
 * so a cosmetically different spelling of the SAME kind never forks a second
 * vocabulary downstream.
 * <p>JSON binding pins the hyphen code as the ONLY wire form ( segment
 * 3): without it the enum would serialize as the constant name over the
 * core→ai-service preview leg, and the persisted preview rows would carry a
 * vocabulary the enumeration parser itself refuses.
 */
public enum ScenarioKind {

    MAIN_FLOW("main-flow"),
    EQUIVALENCE("equivalence"),
    COUNTER_EXAMPLE("counter-example"),
    BOUNDARY("boundary"),
    STATE_COMBINATION("state-combination");

    private final String code;

    ScenarioKind(String code) {
        this.code = code;
    }

    /** Wire form of this kind (shared verbatim with the model contract and the preview ledger). */
    @JsonValue
    public String code() {
        return code;
    }

    /** Lenient parser entry point for JSON bodies (same normalization as fromCode). */
    @JsonCreator
    public static ScenarioKind from(String raw) {
        return fromCode(raw);
    }

    /**
     * Maps a raw wire token onto a kind, or {@code null} when the token names
     * no kind (the caller reports the refusal pointing at the raw text).
     */
    public static ScenarioKind fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase().replace('_', '-');
        for (ScenarioKind kind : values()) {
            if (kind.code.equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
