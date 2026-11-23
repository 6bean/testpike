package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackResult;

/**
 * Off-platform script write-back (outside coding agent returns a script version).
 * <p>Kept separate from the plain save path because write-back adds case-level
 * effects the save path must not own: approved-asset refusal, evidence grading
 * and the pending-review pull-back. The version write itself stays on the
 * single script save source (no second persistence route).
 */
public interface ScriptWritebackService {

    /**
     * Land the agent-authored action JSON as a new script version and re-grade
     * the owning case.
     * <p>Fails before any write when the case is in approved status, or when a
     * stale {@code expectedVersion} is supplied (both throw with a human-readable
     * message naming the current state and what to do instead).
     */
    ScriptWritebackResult writeback(ScriptWritebackInput input);
}
