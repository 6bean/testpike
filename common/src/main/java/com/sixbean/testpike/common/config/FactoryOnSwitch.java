package com.sixbean.testpike.common.config;

/**
 * Tri-state governance switch read (K8 hot-update semantics, factory default
 * on): only an explicit 0/false closes the feature; a missing row, a blank
 * value or an unparsable value keeps it on — a silently-switched-off AI
 * behaviour would be invisible to the operator, so the safe default is on.
 * <p>Single source for the read semantics (R40): the ai-service consumer
 * doors (via ChatBehaviorDirectives.switchOn) and the core dispatch
 * pre-check both delegate here, so a door state can never diverge between
 * the producer face and the consumer face.
 * <p>Pure resolution — no I/O: the caller owns the config read and must map
 * a read failure to the same "row absent" input (null).
 */
public final class FactoryOnSwitch {

    private FactoryOnSwitch() {
    }

    /** True unless the raw value explicitly says off ("0" or "false"). */
    public static boolean on(String rawValue) {
        if (rawValue == null) {
            return true;
        }
        String v = rawValue.trim();
        return !("0".equals(v) || "false".equalsIgnoreCase(v));
    }
}
