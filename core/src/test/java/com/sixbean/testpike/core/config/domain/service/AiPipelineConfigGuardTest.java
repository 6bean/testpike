package com.sixbean.testpike.core.config.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.AiPipelineKeyRanges;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write-side guard segment for the ai.pipeline.* extraction-pipeline key group
 * : the three seeded rows (fan-out cap from V529,
 * inline-retry bound and deferred-round bound from V530), the ConfigServiceImpl
 * interval dispatch and the key-range table, asserted against the real local
 * dev DB with no mocks (strict-testing iron law).
 *
 * <p>Contract: seeded factory defaults match the adjudication sheet
 * (group-parallel 3 / inline-retry 1 / deferred-rounds 1); out-of-range or
 * non-numeric values are rejected with a readable message carrying the allowed
 * interval; the interval boundaries themselves are accepted and the hot update
 * lands, so the operator can retune the long-tail strategy without a restart.
 * Factory rows are restored to their seeded values in every finally path
 * (R37: never truncated, never deleted).
 *
 * <p>Hard caps are deliberate: raising a cap is an adjudicated change (the
 * bounds come from the worst-case ledger), never a configuration edit,
 * so the write side rejects above-cap instead of silently clamping.
 */
@SpringBootTest(classes = CoreApplication.class)
class AiPipelineConfigGuardTest {

    private static final String KEY_GROUP_PARALLEL = "ai.pipeline.group-parallel";
    private static final String KEY_INLINE_RETRY = "ai.pipeline.group-inline-retry-limit";
    private static final String KEY_DEFERRED_ROUNDS = "ai.pipeline.group-deferred-rounds";

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededRowsMatchAdjudicatedFactoryDefaults() {
        assertSeededRow(KEY_GROUP_PARALLEL, "3", "string");
        assertSeededRow(KEY_INLINE_RETRY, "1", "string");
        assertSeededRow(KEY_DEFERRED_ROUNDS, "1", "string");
    }

    @Test
    void writeGuardRejectsOutOfRangeAndNonNumericValues() {
        assertWriteRejected(KEY_GROUP_PARALLEL, "-1", "1~3");
        assertWriteRejected(KEY_GROUP_PARALLEL, "4", "1~3");
        assertWriteRejected(KEY_GROUP_PARALLEL, "abc", "整数");
        assertWriteRejected(KEY_INLINE_RETRY, "-1", "0~2");
        assertWriteRejected(KEY_INLINE_RETRY, "3", "0~2");
        assertWriteRejected(KEY_INLINE_RETRY, "abc", "整数");
        assertWriteRejected(KEY_DEFERRED_ROUNDS, "-1", "0~2");
        assertWriteRejected(KEY_DEFERRED_ROUNDS, "3", "0~2");
    }

    @Test
    void writeGuardAcceptsRangeBoundariesAndHotUpdates() {
        try {
            setConfigValue(KEY_GROUP_PARALLEL, "1");
            setConfigValue(KEY_GROUP_PARALLEL, "3");
            setConfigValue(KEY_INLINE_RETRY, "0");
            setConfigValue(KEY_INLINE_RETRY, "2");
            setConfigValue(KEY_DEFERRED_ROUNDS, "0");
            setConfigValue(KEY_DEFERRED_ROUNDS, "2");
        } finally {
            restoreSeededValues();
        }
        assertEquals("3", configValue(KEY_GROUP_PARALLEL));
        assertEquals("1", configValue(KEY_INLINE_RETRY));
        assertEquals("1", configValue(KEY_DEFERRED_ROUNDS));
    }

    @Test
    void keyRangesTableMirrorsTheReaderClampIntervals() {
        // Mirror of GroupParallelFanout.HARD_CAP and DocExtractServiceImpl's
        // retry bounds: writer and reader must never drift (double guard is
        // deliberate — the reader clamps, the writer refuses).
        assertArrayEquals(new int[]{1, 3}, AiPipelineKeyRanges.rangeOf(KEY_GROUP_PARALLEL));
        assertArrayEquals(new int[]{0, 2}, AiPipelineKeyRanges.rangeOf(KEY_INLINE_RETRY));
        assertArrayEquals(new int[]{0, 2}, AiPipelineKeyRanges.rangeOf(KEY_DEFERRED_ROUNDS));
    }

    @Test
    void keyTableGroupMembershipAndDefaults() {
        assertTrue(AiPipelineKeyRanges.isPipelineKey(KEY_GROUP_PARALLEL));
        assertFalse(AiPipelineKeyRanges.isPipelineKey("ai.ctx.tool-block-max-tokens"),
                "other groups must not match the prefix");
        assertFalse(AiPipelineKeyRanges.isPipelineKey("ai.pipelineX.group-parallel"),
                "near-miss prefix must not match");
        assertFalse(AiPipelineKeyRanges.isPipelineKey(null));
        assertEquals(3, AiPipelineKeyRanges.DEFAULT_GROUP_PARALLEL);
        assertEquals(1, AiPipelineKeyRanges.DEFAULT_GROUP_INLINE_RETRY_LIMIT);
        assertEquals(1, AiPipelineKeyRanges.DEFAULT_GROUP_DEFERRED_ROUNDS);
    }

    private void assertSeededRow(String key, String value, String type) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT config_value, value_type FROM tp_sys_config WHERE config_key = ?", key);
        assertEquals(value, row.get("config_value"), key + " factory default");
        assertEquals(type, row.get("value_type"), key + " value type");
    }

    private void assertWriteRejected(String key, String value, String expectedFace) {
        // The seeded rows already exist, so the rejection must come from the
        // update path's write guard (same face the config page drives).
        ServiceException ex = assertThrows(ServiceException.class,
                () -> setConfigValueQuietly(key, value),
                key + "=" + value + " must be rejected by the write guard");
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), key + "=" + value + " should be 400");
        assertTrue(ex.getMessage().contains(expectedFace),
                "message must carry the allowed face (" + expectedFace + "): " + ex.getMessage());
    }

    private void setConfigValue(String key, String value) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, group_name, value_type, description FROM tp_sys_config WHERE config_key = ?", key);
        configService.update(1L, ((Number) row.get("id")).longValue(), new ConfigInput(
                key, value, (String) row.get("group_name"), (String) row.get("value_type"), 0,
                (String) row.get("description")));
    }

    private void setConfigValueQuietly(String key, String value) {
        setConfigValue(key, value);
    }

    private void restoreSeededValues() {
        setConfigValue(KEY_GROUP_PARALLEL, "3");
        setConfigValue(KEY_INLINE_RETRY, "1");
        setConfigValue(KEY_DEFERRED_ROUNDS, "1");
    }

    private String configValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, key);
    }
}
