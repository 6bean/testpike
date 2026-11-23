package com.sixbean.testpike.core.config.domain.component;

import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterAutoReplayScheduler;

import java.util.Map;

/**
 * Write-side value ranges for the mq.dlq.* dead-letter operations config key
 * group .
 *
 * <p>Mirrors the AiGroundKeyRanges / AiChatKeyRanges precedent: the K8
 * convention puts no CHECK constraint in the DB, this table is the single
 * source of intervals for the write guard in ConfigServiceImpl, and the reader
 * ({@link DeadLetterAutoReplayScheduler}) falls back to its shipped defaults on
 * absent or unparsable values (fail-closed). The key-name constants are taken
 * from the scheduler itself so the two sides can never drift (R40 single
 * source): master switch + cadence interval + per-round cap + round budget +
 * backlog alert threshold, all int faces (the switch follows the existing
 * 0/1 convention of every *.enabled key on this system).
 */
public final class DlqAutoReplayKeyRanges {

    /** Int keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            DeadLetterAutoReplayScheduler.ENABLED_KEY, new int[]{0, 1},
            DeadLetterAutoReplayScheduler.INTERVAL_SECONDS_KEY, new int[]{60, 86400},
            DeadLetterAutoReplayScheduler.BATCH_LIMIT_KEY, new int[]{1, 500},
            DeadLetterAutoReplayScheduler.MAX_ROUNDS_KEY, new int[]{1, 10},
            DeadLetterAutoReplayScheduler.BACKLOG_THRESHOLD_KEY, new int[]{1, 100000});

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "mq.dlq.";

    private DlqAutoReplayKeyRanges() {
    }

    /** Interval for the group's int keys, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** True for any mq.dlq.* group key (single write-side dispatch check). */
    public static boolean isDlqKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
