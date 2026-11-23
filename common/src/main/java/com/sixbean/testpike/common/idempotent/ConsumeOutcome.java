package com.sixbean.testpike.common.idempotent;


public enum ConsumeOutcome {
    
    PROCESSED,
    
    DUPLICATE,
    /**
 * Business failed with an entity-gone ServiceException (target row already
 * deleted — AJ batch noise reduction): retry and dead-letter are both
 * pointless, the consumer must write a skip audit and ack.
 */
    ENTITY_GONE_SKIPPED,
    /**
 * DD batch tri-state: the consume lock is still held but its liveness
 * heartbeat is dead — the previous holder crashed mid-run, so acking this
 * redelivery as a duplicate would hang the task until the long lease
 * expires with nobody working it. The consumer must nack/requeue instead
 * (duplicate_drop audit is wrong here); renewal-disabled callers never
 * produce this value, keeping today's DUPLICATE semantics untouched.
 */
    HEARTBEAT_DEAD_REQUEUE
}
