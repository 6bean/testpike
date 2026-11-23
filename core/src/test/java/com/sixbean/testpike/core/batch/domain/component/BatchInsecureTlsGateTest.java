package com.sixbean.testpike.core.batch.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BatchInsecureTlsGateTest {

    @Test
    void test_enabled_and_insecure_blocks_launch() {
        assertTrue(BatchPrecheckSupport.rejectInsecureTls("1", "insecure"));
        assertTrue(BatchPrecheckSupport.rejectInsecureTls("true", "insecure"));
        assertTrue(BatchPrecheckSupport.rejectInsecureTls(" TRUE ", "insecure"));
    }

    @Test
    void test_disabled_or_absent_config_never_blocks() {
        assertFalse(BatchPrecheckSupport.rejectInsecureTls(null, "insecure"));
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("0", "insecure"));
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("false", "insecure"));
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("yes", "insecure"));
    }

    @Test
    void test_verified_modes_never_blocked_even_when_enabled() {
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("1", "strict"));
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("1", "custom_ca"));
        assertFalse(BatchPrecheckSupport.rejectInsecureTls("1", "weird"));
    }

    @Test
    void test_block_message_names_env_and_config_key_in_plain_words() {
        String msg = BatchPrecheckSupport.insecureBlockMessage("测试环境A");
        assertTrue(msg.contains("测试环境A"), msg);
        assertTrue(msg.contains("跳过证书校验"), msg);
        assertTrue(msg.contains("tls.exit-plan.reject-insecure"), msg);
    }

    @Test
    void test_config_key_constant_is_contract_literal() {
        assertEquals("tls.exit-plan.reject-insecure", BatchPrecheckSupport.CFG_REJECT_INSECURE_TLS);
    }
}
