package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-support fixture for flipping a shipped tp_sys_config switch through
 * the service write path. Raw SQL would leave the in-memory config cache on
 * the stale value (the cache only syncs on the write side), so every test that
 * needs a non-factory posture must replicate the real door — same rule that
 * made {@code DocumentExtractionDimensionSuggestTest} update via ConfigService.
 * <p>Shared by the extraction confirm family (three consumers of the same
 * flip-and-restore dance), so the parse-and-write pair exists once (R40).
 */
public final class SysConfigSwitchFixture {

    private SysConfigSwitchFixture() {
    }

    /** Upsert the key to {@code value} keeping the shipped group/type and the
     *  shipped on-screen description (a fixture reset must never rewrite the
     *  description into a second variant). */
    public static void upsert(JdbcTemplate jdbcTemplate, ConfigService configService,
                              String key, String value, String groupName,
                              String valueType, String description) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        ConfigInput input = new ConfigInput(key, value, groupName, valueType, 0, description);
        if (id != null) {
            configService.update(1L, id, input);
        } else {
            configService.create(1L, input);
        }
    }
}
