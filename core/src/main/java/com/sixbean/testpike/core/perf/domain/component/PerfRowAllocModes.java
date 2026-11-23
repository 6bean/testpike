package com.sixbean.testpike.core.perf.domain.component;

import java.util.Set;

/**
 * Row-allocation strategy value domain for perf CSV datasets .
 * <p>Single source for the three spellings: the scenario save guard, the K8
 * default-key reader and the engine templates all consume these constants — a
 * fourth spelling anywhere else would silently never resolve. Semantics:
 * <ul>
 * <li>{@code SEQUENTIAL_EXCLUSIVE} — rows are leased one by one in file order;
 * a row is handed to exactly one virtual user until the pool is drained;</li>
 * <li>{@code VUSER_EXCLUSIVE} — each virtual user owns a private segment of the
 * pool and never shares a row with another user;</li>
 * <li>{@code CYCLE} — rows are reused round-robin once exhausted (safe
 * default: a long run can never stall on "no row left").</li>
 * </ul>
 */
public final class PerfRowAllocModes {

    public static final String SEQUENTIAL_EXCLUSIVE = "SEQUENTIAL_EXCLUSIVE";
    public static final String VUSER_EXCLUSIVE = "VUSER_EXCLUSIVE";
    public static final String CYCLE = "CYCLE";

    /** The only accepted spellings (save-time guard + dispatch resolution share this set). */
    public static final Set<String> VALUES = Set.of(SEQUENTIAL_EXCLUSIVE, VUSER_EXCLUSIVE, CYCLE);

    /**
     * Strategy read-order shared by both adapters: the launch-pinned value wins,
     * then the persisted column, else null (caller falls back to the K8 default
     * key) — pin discipline with one code path (no per-adapter drift).
     */
    public static String firstNonBlank(String pinned, String persisted) {
        if (pinned != null && !pinned.isBlank()) {
            return pinned;
        }
        return persisted != null && !persisted.isBlank() ? persisted : null;
    }

    private PerfRowAllocModes() {
    }
}
