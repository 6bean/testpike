package com.sixbean.testpike.core.config.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.AiCtxKeyRanges;
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
 * Write-side guard segment for the ai.ctx.* long-context key group (AP batch ,
 * knife 1c): the three seeded int rows, the ConfigServiceImpl interval dispatch
 * and the AiCtxKeyRanges table itself, asserted against the real local dev DB
 * with no mocks (strict-testing iron law).
 *
 * <p>Contract: seeded factory defaults match the adjudication sheet
 * (tool-block 8000 / keep-recent 20 / memory 128, replayed by V505); out-of-range or non-numeric
 * int values are rejected with a readable message carrying the allowed
 * interval; the range boundaries themselves are accepted and the hot update
 * lands. Factory rows are restored to their seeded values in every finally
 * path (R37: never truncated, never deleted).
 *
 * <p>The former bool member (ai.ctx.doc-summaries-cache) retired with V455 to
 * ai.doc.summary-cache.enabled; its write-guard and hot-update coverage lives
 * in ConfigServiceTest, not here.
 */
@SpringBootTest(classes = CoreApplication.class)
class AiCtxConfigGuardTest {

    private static final String KEY_TOOL_BLOCK = "ai.ctx.tool-block-max-tokens";
    private static final String KEY_KEEP_RECENT = "ai.ctx.session-keep-recent-turns";
    private static final String KEY_MEMORY_ITEMS = "ai.ctx.memory-max-items";

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededRowsMatchAdjudicatedFactoryDefaults() {
        assertSeededRow(KEY_TOOL_BLOCK, "8000", "int");
        assertSeededRow(KEY_KEEP_RECENT, "20", "int");
        assertSeededRow(KEY_MEMORY_ITEMS, "128", "int");
    }

    @Test
    void writeGuardRejectsOutOfRangeAndNonNumericValues() {
        assertWriteRejected(KEY_TOOL_BLOCK, "int", "199", "200~20000");
        assertWriteRejected(KEY_TOOL_BLOCK, "int", "20001", "200~20000");
        assertWriteRejected(KEY_TOOL_BLOCK, "int", "abc", "整数");
        assertWriteRejected(KEY_KEEP_RECENT, "int", "0", "1~50");
        assertWriteRejected(KEY_KEEP_RECENT, "int", "51", "1~50");
        assertWriteRejected(KEY_MEMORY_ITEMS, "int", "0", "1~200");
        assertWriteRejected(KEY_MEMORY_ITEMS, "int", "201", "1~200");
    }

    @Test
    void writeGuardAcceptsRangeBoundariesAndHotUpdates() {
        try {
            setConfigValue(KEY_TOOL_BLOCK, "int", "200");
            setConfigValue(KEY_TOOL_BLOCK, "int", "20000");
            setConfigValue(KEY_KEEP_RECENT, "int", "1");
            setConfigValue(KEY_KEEP_RECENT, "int", "50");
            setConfigValue(KEY_MEMORY_ITEMS, "int", "1");
            setConfigValue(KEY_MEMORY_ITEMS, "int", "200");
        } finally {
            restoreSeededValues();
        }
        assertEquals("8000", configValue(KEY_TOOL_BLOCK));
        assertEquals("20", configValue(KEY_KEEP_RECENT));
        assertEquals("128", configValue(KEY_MEMORY_ITEMS));
    }

    @Test
    void keyRangesTableMirrorsTheReaderClampIntervals() {
        // Mirror of AiCtxConfigResolver's clamp intervals: writer and reader
        // must never drift (double guard is deliberate, see resolver javadoc).
        assertArrayEquals(new int[]{200, 20000}, AiCtxKeyRanges.rangeOf(KEY_TOOL_BLOCK));
        assertArrayEquals(new int[]{1, 50}, AiCtxKeyRanges.rangeOf(KEY_KEEP_RECENT));
        assertArrayEquals(new int[]{1, 200}, AiCtxKeyRanges.rangeOf(KEY_MEMORY_ITEMS));
    }

    @Test
    void keyTableGroupMembershipAndDefaults() {
        assertTrue(AiCtxKeyRanges.isCtxKey(KEY_TOOL_BLOCK));
        assertFalse(AiCtxKeyRanges.isCtxKey("ai.chat.exec-enabled"), "other groups must not match the prefix");
        assertFalse(AiCtxKeyRanges.isCtxKey("ai.ctxx.tool-block"), "near-miss prefix must not match");
        assertFalse(AiCtxKeyRanges.isCtxKey(null));
        assertEquals(2000, AiCtxKeyRanges.DEFAULT_TOOL_BLOCK_MAX_TOKENS);
        assertEquals(6, AiCtxKeyRanges.DEFAULT_SESSION_KEEP_RECENT_TURNS);
        assertEquals(30, AiCtxKeyRanges.DEFAULT_MEMORY_MAX_ITEMS);
    }

    private void assertSeededRow(String key, String value, String type) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT config_value, value_type FROM tp_sys_config WHERE config_key = ?", key);
        assertEquals(value, row.get("config_value"), key + " factory default");
        assertEquals(type, row.get("value_type"), key + " value type");
    }

    private void assertWriteRejected(String key, String type, String value, String expectedFace) {
        // The seeded rows already exist, so the rejection must come from the
        // update path's write guard (same face the config page drives).
        ServiceException ex = assertThrows(ServiceException.class,
                () -> setConfigValueQuietly(key, type, value),
                key + "=" + value + " must be rejected by the write guard");
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), key + "=" + value + " should be 400");
        assertTrue(ex.getMessage().contains(expectedFace),
                "message must carry the allowed face (" + expectedFace + "): " + ex.getMessage());
    }

    private void setConfigValue(String key, String type, String value) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, group_name, description FROM tp_sys_config WHERE config_key = ?", key);
        configService.update(1L, ((Number) row.get("id")).longValue(), new ConfigInput(
                key, value, (String) row.get("group_name"), type, 0, (String) row.get("description")));
    }

    private void setConfigValueQuietly(String key, String type, String value) {
        setConfigValue(key, type, value);
    }

    private void restoreSeededValues() {
        setConfigValue(KEY_TOOL_BLOCK, "int", "8000");
        setConfigValue(KEY_KEEP_RECENT, "int", "20");
        setConfigValue(KEY_MEMORY_ITEMS, "int", "128");
    }

    private String configValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, key);
    }
}
