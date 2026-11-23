package com.sixbean.testpike.core.batch.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exit-plan mock ban gate (ruling M-f / U-c): a batch whose live members declare
 * a mock space may not launch while sys_config key mock.exit-plan.forbid is on,
 * because simulated traffic must never masquerade as real-integration evidence.
 * <p>Semantics mirror the insecure-TLS gate (same honesty chain D2): enabled
 * values are exactly 1/true; null/0/illegal = disabled so existing batches see
 * zero drift. A batch with no mock-bound member launches under any config value.
 */
class BatchMockExitGateTest {

    @Test
    void test_enabled_gate_and_mock_member_blocks_launch() {
        assertTrue(BatchPrecheckSupport.rejectMockBoundCases("1", 1));
        assertTrue(BatchPrecheckSupport.rejectMockBoundCases("true", 3));
        assertTrue(BatchPrecheckSupport.rejectMockBoundCases(" TRUE ", 2));
    }

    @Test
    void test_disabled_or_absent_config_never_blocks() {
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases(null, 5));
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases("0", 5));
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases("false", 5));
        // illegal value = fail-closed on the ban itself (never silently widen)
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases("yes", 5));
    }

    @Test
    void test_batches_without_mock_members_never_blocked_even_when_enabled() {
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases("1", 0));
        assertFalse(BatchPrecheckSupport.rejectMockBoundCases("1", -1));
    }

    @Test
    void test_block_message_names_count_and_config_key_in_plain_words() {
        String msg = BatchPrecheckSupport.mockBoundBlockMessage(2);
        assertTrue(msg.contains("2"), msg);
        assertTrue(msg.contains("mock.exit-plan.forbid"), msg);
        // U-c wording duty: tell the reader to run against the real integration
        assertTrue(msg.contains("真实"), msg);
    }

    @Test
    void test_config_key_constant_is_contract_literal() {
        assertEquals("mock.exit-plan.forbid", BatchPrecheckSupport.CFG_MOCK_EXIT_PLAN_FORBID);
    }
}
