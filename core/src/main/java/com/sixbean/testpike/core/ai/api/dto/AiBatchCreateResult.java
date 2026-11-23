package com.sixbean.testpike.core.ai.api.dto;

import java.util.List;

/**
 * AI batch creation result includes batchId and a subtask ID list in the same order as the requirements, and MQ publishing is executed in a loop by the Controller layer.
 * This is consistent with the "persist to database + publish MQ" layering for single generation, and unit tests have zero MQ side effects.
 *
 */
public record AiBatchCreateResult(long batchId, List<Long> taskIds, boolean windowFeed) {

    /** Legacy two-argument call sites keep compiling (regular feed). */
    public AiBatchCreateResult(long batchId, List<Long> taskIds) {
        this(batchId, taskIds, false);
    }
}
