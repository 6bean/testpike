package com.sixbean.testpike.core.config.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ConfigServiceTest {

    @Autowired
    private ConfigService configService;

    @Autowired
    private ConfigCipher configCipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCache;

    /** Everything the table held before this class first wiped it; restored verbatim after each test. */
    private java.util.List<Object[]> capturedConfigs;

    @BeforeEach
    void cleanTables() {
        capturedConfigs = captureConfigs();
        wipeConfigs();
    }

    /**
     * Put back exactly the rows the environment had before this class cleared them.
     *
     * <p>This method used to replay a hand-maintained copy of the migration seeds - a literal list of
     * key/value/description rows inside the test file. A second source of truth for factory data
     * drifts by construction: every key a later migration added was silently deleted here at the end
     * of the next L2 run, and every key a migration retired came back to life, and batches kept
     * reading that as stale-environment weather. Two cases were loudest. The list pinned
     * {@code storage.provider='local'} with an empty {@code storage.local-root}, so every run of this
     * class overwrote the working storage pair and turned the whole presigned-URL family red
     * (artifact storage, visual baselines, reports, tool market). And an entire family of
     * draft-confirmation switch keys vanished the day a chain forgot to sync the list.
     *
     * <p>Capture-and-restore needs no syncing at all: whatever the environment holds is what comes
     * back, ids and audit columns included, so an operational override survives a test run instead of
     * being overwritten by a snapshot nobody maintains any more.
     */
    @AfterEach
    void restoreSeedConfigs() {
        wipeConfigs();
        restoreConfigs(capturedConfigs);
        // Rows are rebuilt with bare JDBC instead of through ConfigService, so the in-memory cache
        // has to be reloaded after the restore: a temporary value an earlier create/update pushed into
        // ConfigCacheHolder must not linger (proven: a boundary case left '1' in the cache and turned
        // CiGatePolicyTest red twice), and that reload cannot sit inside a test right after the wipe,
        // because it would leave the cache dangling for the rest of the JVM (UserServiceTest read null/0).
        configCache.loadAll(jdbcTemplate);
    }

    private java.util.List<Object[]> captureConfigs() {
        return jdbcTemplate.query("""
                SELECT id, config_key, config_value, group_name, value_type, encrypt_flag, description,
                       created_by, created_at, updated_at
                FROM tp_sys_config
                ORDER BY id""",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("config_key"),
                        rs.getString("config_value"), rs.getString("group_name"), rs.getString("value_type"),
                        rs.getShort("encrypt_flag"), rs.getString("description"), rs.getObject("created_by"),
                        rs.getObject("created_at"), rs.getObject("updated_at")});
    }

    /**
     * DELETE rather than TRUNCATE ... CASCADE: the cascade form reaches past the table this class
     * guards and would also wipe any child table added later, which a unit test has no business doing.
     */
    private void wipeConfigs() {
        jdbcTemplate.execute("DELETE FROM tp_sys_config");
    }

    private void restoreConfigs(java.util.List<Object[]> rows) {
        for (Object[] row : rows) {
            jdbcTemplate.update("""
                    INSERT INTO tp_sys_config (id, config_key, config_value, group_name, value_type,
                        encrypt_flag, description, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", row);
        }
        // The ids come back as they were, so the sequence has to continue above them - a number
        // handed out later must never collide with a row that was just restored. And it must never
        // be pulled back below what the sequence already handed out: on the shared dev database a
        // concurrent suite may hold rows numbered above MAX(id) right now, and restarting the
        // sequence under them would make the next insert collide with those live rows.
        jdbcTemplate.execute("SELECT setval('tp_sys_config_id_seq', GREATEST("
                + "(SELECT COALESCE(MAX(id), 1) FROM tp_sys_config),"
                + " (SELECT last_value FROM tp_sys_config_id_seq)), true)");
    }


    @Test
    void createSensitiveConfigMasksAndStoresCipher() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "integration.feishu.app.secret", "sk-feishu-secret-123456", "通知", "string", 1, "飞书 App Secret"));
        configCache.loadAll(jdbcTemplate);
        assertNotNull(created.id());
        assertEquals("integration.feishu.app.secret", created.configKey());
        assertEquals("******", created.configValue());
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE id = ?", String.class, created.id());
        assertNotEquals("sk-feishu-secret-123456", stored);
        assertFalse(stored.contains("feishu-secret"));
        assertEquals("sk-feishu-secret-123456", configCipher.decrypt(stored));
    }

    @Test
    void createPlainConfigStoresPlaintext() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "review.comment.required", "true", "系统", "bool", 0, "审批备注必填"));
        assertEquals("true", created.configValue());
        assertEquals("true", jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE id = ?", String.class, created.id()));
    }


    @Test
    void updateConfigTakesEffectImmediately() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "recording.retention.days", "90", "系统", "int", 0, "录屏保留期"));
        assertEquals("90", configService.getValue("recording.retention.days", "30"));
        configService.update(1L, created.id(), new ConfigInput(
                "recording.retention.days", "120", "系统", "int", 0, "录屏保留期"));
        ConfigInfo updated = configService.list(null).stream()
                .filter(c -> c.configKey().equals("recording.retention.days")).findFirst().orElseThrow();
        assertEquals("120", updated.configValue());
        assertEquals("120", configService.getValue("recording.retention.days", "30"));
    }

    @Test
    void updateSensitiveConfigReEncryptsAndMasks() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "integration.jira.webhook.secret", "old-secret", "通知", "string", 1, "Jira 密钥"));
        ConfigInfo updated = configService.update(1L, created.id(), new ConfigInput(
                "integration.jira.webhook.secret", "new-secret-xyz", "通知", "string", 1, "Jira 密钥"));
        assertEquals("******", updated.configValue());
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE id = ?", String.class, created.id());
        assertNotEquals("new-secret-xyz", stored);
        assertEquals("new-secret-xyz", configCipher.decrypt(stored));
    }


    @Test
    void duplicateKeyRejectedWith409() {
        configService.create(1L, new ConfigInput("backlog.warn.days", "3", "系统", "int", 0, "积压预警"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput("backlog.warn.days", "5", "系统", "int", 0, "积压预警")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
    }


    @Test
    void dbNeverContainsSensitivePlaintext() {
        configService.create(1L, new ConfigInput(
                "integration.feishu.app.secret", "super-secret-plaintext-value", "通知", "string", 1, "飞书密钥"));
        configService.create(1L, new ConfigInput(
                "integration.slack.token", "xoxb-another-plain-token", "通知", "string", 1, "Slack Token"));
        List<String> allValues = jdbcTemplate.queryForList(
                "SELECT config_value FROM tp_sys_config WHERE encrypt_flag = 1", String.class);
        assertFalse(allValues.isEmpty());
        for (String v : allValues) {
            assertFalse(v.contains("super-secret-plaintext-value"));
            assertFalse(v.contains("plain-token"));
            assertFalse(v.contains("feishu-app"));
        }
    }


    @Test
    void invalidValueTypeRejectedWith400() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput("x.key", "1", "系统", "double", 0, "")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void updateMissingConfigRejectedWith404() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.update(1L, 999999L, new ConfigInput("x.key", "1", "系统", "int", 0, "")));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void deleteRemovesConfigAndCacheFallsBackToDefault() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "case.timeout.minutes", "10", "系统", "int", 0, "单条用例超时"));
        configService.delete(1L, created.id());
        assertTrue(configService.list(null).stream().noneMatch(c -> c.id().equals(created.id())));
        assertEquals("15", configService.getValue("case.timeout.minutes", "15"));
    }

    @Test
    void listByGroupFiltersOnlyMatchingGroup() {
        configService.create(1L, new ConfigInput("g1.k1", "1", "系统", "int", 0, ""));
        configService.create(1L, new ConfigInput("g2.k1", "1", "安全", "int", 0, ""));
        List<ConfigInfo> systemGroup = configService.list("系统");
        assertTrue(systemGroup.stream().allMatch(c -> c.groupName().equals("系统")));
        assertTrue(systemGroup.stream().anyMatch(c -> c.configKey().equals("g1.k1")));
        assertTrue(systemGroup.stream().noneMatch(c -> c.configKey().equals("g2.k1")));
    }

    @Test
    void getValueReadsExistingConfig() {
        configService.create(1L, new ConfigInput("flaky.threshold", "3", "系统", "int", 0, ""));
        assertEquals("3", configService.getValue("flaky.threshold", "5"));
    }

 // ---- E7: the LLM timeout budget window is pinned on the save side
 // (out-of-range answers a plain 400 naming the bounds) ----

    @Test
    void budgetSafetyFactor_越界与非数字_400含区间() {
        for (String bad : List.of("0.05", "0.96", "-1")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.budget-safety-factor", bad, "系统", "string", 0, "预算系数")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0.1~0.95"),
                    "报错须点名允许区间: " + ex.getMessage());
        }
        // Non-numeric input rides the group's shared message face ("must be a
        // number") — still a 400, just not the interval wording.
        ServiceException nonNumeric = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.budget-safety-factor", "abc", "系统", "string", 0, "预算系数")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, nonNumeric.getCode());
        assertTrue(nonNumeric.getMessage().contains("必须是数字"), nonNumeric.getMessage());
    }

    @Test
    void budgetSafetyFactor_区间内正常保存() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.budget-safety-factor", "0.6", "系统", "string", 0, "预算系数"));
        assertEquals("0.6", configService.getValue("ai.doc.budget-safety-factor", "0.5"));
        // boundary values 0.1 and 0.95 are accepted (interval is inclusive)
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.budget-safety-factor", "0.95", "系统", "string", 0, "预算系数"));
        assertEquals("0.95", configService.getValue("ai.doc.budget-safety-factor", "0.5"));
    }

    @Test
    void extractReconcileRounds_越界_400含区间() {
        for (String bad : List.of("0", "6", "-1")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.extract-reconcile-rounds", bad, "系统", "int", 0, "补提轮数")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1~5"),
                    "报错须点名允许区间: " + ex.getMessage());
        }
    }

    @Test
    void extractReconcileRounds_非数字_400() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.extract-reconcile-rounds", "abc", "系统", "int", 0, "补提轮数")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void extractReconcileRounds_区间内正常保存() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.extract-reconcile-rounds", "3", "系统", "int", 0, "补提轮数"));
        assertEquals("3", configService.getValue("ai.doc.extract-reconcile-rounds", "1"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.extract-reconcile-rounds", "1", "系统", "int", 0, "补提轮数"));
        assertEquals("1", configService.getValue("ai.doc.extract-reconcile-rounds", "9"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.extract-reconcile-rounds", "5", "系统", "int", 0, "补提轮数"));
        assertEquals("5", configService.getValue("ai.doc.extract-reconcile-rounds", "9"));
    }

    // ---- D2 recovery window key (V531): 0 = resume off, otherwise the
    // adjudicated 300~3600 band; the guard keeps a resume under the
    // delivery-ack headroom invariant, so out-of-band values are refused.
    @Test
    void reconcileResumeSeconds_outOfBandValues_rejected() {
        for (String bad : List.of("299", "3601", "-1")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.extract-reconcile-resume-seconds", bad, "系统", "int", 0, "复核轮续窗")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("300~3600"),
                    "报错须点名允许区间: " + ex.getMessage());
        }
    }

    @Test
    void reconcileResumeSeconds_garbageValue_rejected() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.extract-reconcile-resume-seconds", "abc", "系统", "int", 0, "复核轮续窗")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void reconcileResumeSeconds_offSwitchAndBandBoundaries_saved() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.extract-reconcile-resume-seconds", "0", "系统", "int", 0, "复核轮续窗"));
        assertEquals("0", configService.getValue("ai.doc.extract-reconcile-resume-seconds", "1800"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.extract-reconcile-resume-seconds", "300", "系统", "int", 0, "复核轮续窗"));
        assertEquals("300", configService.getValue("ai.doc.extract-reconcile-resume-seconds", "1800"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.extract-reconcile-resume-seconds", "3600", "系统", "int", 0, "复核轮续窗"));
        assertEquals("3600", configService.getValue("ai.doc.extract-reconcile-resume-seconds", "1800"));
    }

    // ---- Extraction streaming-channel switch: write side accepts the bool face
    // only; garbage values are rejected with a 400 that names true/false ----
    @Test
    void extractStreamEnabled_非布尔值_400点名true_false() {
        for (String bad : List.of("yes", "1", "开", "TRUE")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.extract-stream.enabled", bad, "系统", "bool", 0, "提取流式开关")),
                    "非法布尔值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("true/false"),
                    "报错须点名允许取值: " + ex.getMessage());
        }
    }

    @Test
    void extractStreamEnabled_布尔值正常保存() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.extract-stream.enabled", "true", "系统", "bool", 0, "提取流式开关"));
        assertEquals("true", configService.getValue("ai.doc.extract-stream.enabled", "false"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.extract-stream.enabled", "false", "系统", "bool", 0, "提取流式开关"));
        assertEquals("false", configService.getValue("ai.doc.extract-stream.enabled", "true"));
    }

    // ---- Chapter-aligned grouping switch : write side accepts the
    // bool face only; garbage rejected with a 400 naming true/false. ----
    @Test
    void chapterAlignedGrouping_rejectsNonBoolAndRoundTrips() {
        for (String bad : List.of("yes", "1", "开")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.chapter-aligned-grouping.enabled", bad,
                            "系统", "bool", 0, "章节对齐分组开关")),
                    "非法布尔值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("true/false"),
                    "报错须点名允许取值: " + ex.getMessage());
        }
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.chapter-aligned-grouping.enabled", "true",
                "系统", "bool", 0, "章节对齐分组开关"));
        assertEquals("true", configService.getValue(
                "ai.doc.chapter-aligned-grouping.enabled", "false"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.chapter-aligned-grouping.enabled", "false",
                "系统", "bool", 0, "章节对齐分组开关"));
        assertEquals("false", configService.getValue(
                "ai.doc.chapter-aligned-grouping.enabled", "true"));
    }

    // ---- Group-summary cache switch (moved to the ai.doc.summary-* family,
    // ships ON): garbage values rejected with a 400 naming true/false ----
    @Test
    void summaryCacheEnabled_非布尔值_400点名true_false() {
        for (String bad : List.of("yes", "1", "开", "TRUE")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.summary-cache.enabled", bad, "系统", "bool", 0, "分组摘要缓存开关")),
                    "非法布尔值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("true/false"),
                    "报错须点名允许取值: " + ex.getMessage());
        }
    }

    @Test
    void summaryCacheEnabled_布尔值正常保存() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.summary-cache.enabled", "true", "系统", "bool", 0, "分组摘要缓存开关"));
        assertEquals("true", configService.getValue("ai.doc.summary-cache.enabled", "false"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.summary-cache.enabled", "false", "系统", "bool", 0, "分组摘要缓存开关"));
        assertEquals("false", configService.getValue("ai.doc.summary-cache.enabled", "true"));
    }

    // ---- Group-overlap ratio (extraction grouping window): the write side pins
    // the operator-allowed window 0~0.25 with a plain 400 naming the bounds ----
    @Test
    void groupOverlapRatio_越界_400人话含区间() {
        for (String bad : List.of("-0.1", "0.26", "1.5")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.group-overlap-ratio", bad, "系统", "string", 0, "封组重叠比例")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0.25"),
                    "报错须点名区间上限: " + ex.getMessage());
        }
    }

    @Test
    void groupOverlapRatio_合法值保存并热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.group-overlap-ratio", "0.10", "系统", "string", 0, "封组重叠比例"));
        assertEquals("0.10", configService.getValue("ai.doc.group-overlap-ratio", "0.20"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.group-overlap-ratio", "0", "系统", "string", 0, "封组重叠比例"));
        assertEquals("0", configService.getValue("ai.doc.group-overlap-ratio", "0.20"));
    }

    // ---- Fidelity retention floor : the extraction plane must
    // inject at least this ratio of the source-package tokens; the write side
    // pins the operator window 0~1 with a plain 400, the reader (fidelity gate
    // face) fails closed to the factory 0.95 ----
    @Test
    void fidelityRetentionFloor_越界或非数字_400人话含区间() {
        for (String bad : List.of("-0.1", "1.01", "2")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.fidelity-retention-floor", bad, "系统", "string", 0, "提取原文保留比例下限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0~1"),
                    "报错须点名区间: " + ex.getMessage());
        }
        ServiceException garbage = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.fidelity-retention-floor", "abc", "系统", "string", 0, "提取原文保留比例下限")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, garbage.getCode());
    }

    @Test
    void fidelityRetentionFloor_合法值保存并热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.fidelity-retention-floor", "0.95", "系统", "string", 0, "提取原文保留比例下限"));
        assertEquals("0.95", configService.getValue("ai.doc.fidelity-retention-floor", "0.5"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.fidelity-retention-floor", "0", "系统", "string", 0, "提取原文保留比例下限"));
        assertEquals("0", configService.getValue("ai.doc.fidelity-retention-floor", "0.5"));
    }

    // ---- Recall floor : the golden-set recall spectrum
    // judges each live run against this floor; the write side pins the
    // operator window 0~1 with a plain 400, the spectrum reader fails
    // closed to the factory 0.85 with the same bound ----
    @Test
    void recallFloor_越界或非数字_400人话含区间() {
        for (String bad : List.of("-0.1", "1.01", "2")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.recall-floor", bad, "系统", "string", 0, "需求点召回率下限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0~1"),
                    "报错须点名区间: " + ex.getMessage());
        }
        ServiceException garbage = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.recall-floor", "abc", "系统", "string", 0, "需求点召回率下限")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, garbage.getCode());
    }

    @Test
    void recallFloor_合法值保存并热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.recall-floor", "0.85", "系统", "string", 0, "需求点召回率下限"));
        assertEquals("0.85", configService.getValue("ai.doc.recall-floor", "0.5"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.recall-floor", "1", "系统", "string", 0, "需求点召回率下限"));
        assertEquals("1", configService.getValue("ai.doc.recall-floor", "0.5"));
    }

    // ---- node-cap write faces: same windows the ai-service reader
    // fails closed to (double-ended contract, ContextBudgetGroupGranularityTest
    // pins the read side; these two pin the plain 400 + accepted store). ----

    @Test
    void nodeCapKeys_越界或非数字_400并点名区间() {
        record Bad(String key, String value) {
        }
        for (Bad bad : List.of(
                new Bad("ai.doc.group-max-tokens", "255"),
                new Bad("ai.doc.group-max-tokens", "100001"),
                new Bad("ai.doc.group-max-tokens", "junk"),
                new Bad("ai.doc.group-max-chunks", "0"),
                new Bad("ai.doc.group-max-chunks", "501"),
                new Bad("ai.doc.group-max-chapters", "0"),
                new Bad("ai.doc.group-max-chapters", "1001"),
                new Bad("ai.doc.group-max-chapters", "junk"),
                new Bad("ai.doc.bucket-output-max-tokens", "999"),
                new Bad("ai.doc.bucket-output-max-tokens", "200001"),
                new Bad("ai.doc.output-amplification-ratio", "0.05"),
                new Bad("ai.doc.output-amplification-ratio", "10.5"),
                new Bad("ai.gen.original-block-max-tokens", "255"),
                new Bad("ai.gen.original-block-max-tokens", "100001"),
                new Bad("ai.gen.original-block-max-tokens", "junk"))) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            bad.key(), bad.value(), "系统", "string", 0, "提取节点上限")),
                    "越界值应拒: " + bad.key() + "=" + bad.value());
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        }
    }

    @Test
    void nodeCapKeys_合法值保存并热读() {
        configService.create(1L, new ConfigInput(
                "ai.doc.group-max-tokens", "8000", "系统", "string", 0, "提取节点上限"));
        assertEquals("8000", configService.getValue("ai.doc.group-max-tokens", "5000"));
        // : chapter-span cap travels the same write window as the reader
        configService.create(1L, new ConfigInput(
                "ai.doc.group-max-chapters", "20", "系统", "string", 0, "封组章跨度上限"));
        assertEquals("20", configService.getValue("ai.doc.group-max-chapters", "5"));
        configService.create(1L, new ConfigInput(
                "ai.doc.output-amplification-ratio", "2.94", "系统", "string", 0, "提取节点上限"));
        assertEquals("2.94", configService.getValue("ai.doc.output-amplification-ratio", "1"));
        // : the generation-side injection budget is hot as well
        configService.create(1L, new ConfigInput(
                "ai.gen.original-block-max-tokens", "12000", "系统", "string", 0, "用例原文注入预算"));
        assertEquals("12000", configService.getValue("ai.gen.original-block-max-tokens", "8000"));
    }

    /**
 * write face: the recall-density floor shares the reader's
 * window (double, 0-50). Negative and above-range values are rejected
 * with the plain cause; 0 is legal and means "check off".
 */
    @Test
    void densityFloorKey_越界或非数字_400并点名区间_合法值热读() {
        record Bad(String value) {
        }
        for (Bad bad : List.of(new Bad("-0.5"), new Bad("51"), new Bad("junk"))) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.recall-density-floor-per-ktoken", bad.value(),
                            "系统", "string", 0, "每千token产出条数下限")),
                    "越界值应拒: " + bad.value());
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        }
        ConfigInfo floorRow = configService.create(1L, new ConfigInput(
                "ai.doc.recall-density-floor-per-ktoken", "6.4", "系统", "string", 0,
                "每千token产出条数下限"));
        assertEquals("6.4", configService.getValue(
                "ai.doc.recall-density-floor-per-ktoken", "6.4"));
        configService.update(1L, floorRow.id(), new ConfigInput(
                "ai.doc.recall-density-floor-per-ktoken", "0", "系统", "string", 0,
                "每千token产出条数下限"));
        assertEquals("0", configService.getValue(
                "ai.doc.recall-density-floor-per-ktoken", "6.4"));
    }

    /**
 * write face: the four per-domain density-floor keys share the
 * same 0~50 window through one suffix rule — a calibrated value lands
 * hot, out-of-window or non-numeric values are rejected on every prefix,
 * and 0 stays legal (check off) for the domains still awaiting their
 * measured calibration.
 */
    @Test
    void perDomainDensityFloorKeys_家族共享区间闸_越界拒合法纳() {
        for (String prefix : List.of("ai.spec.", "ai.judge.", "ai.gen.scenario-", "ai.mock.")) {
            String key = prefix + "recall-density-floor-per-ktoken";
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            key, "51", "系统", "string", 0, "每千token产出条数下限")),
                    "超上限应拒: " + key);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            key, "junk", "系统", "string", 0, "每千token产出条数下限")),
                    "非数字应拒: " + key);
            // the calibrated spec face lands hot; the three domains still
            // awaiting their measured calibration ship 0 = check off
            String shipped = "ai.spec.".equals(prefix) ? "11.8" : "0";
            configService.create(1L, new ConfigInput(
                    key, shipped, "系统", "string", 0, "每千token产出条数下限"));
            assertEquals(shipped, configService.getValue(key, "6.4"));
        }
    }

    // ---- Extraction-plane timeout ceiling :
    // the same 10~900 window as the global llm.timeout-seconds, blank keeps
    // the global face, out-of-window/garbage rejected with a plain 400 ----
    @Test
    void docLlmTimeoutKey_越界_400人话含区间() {
        for (String bad : List.of("9", "901")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.llm-timeout-seconds", bad, "系统", "string", 0, "提取链超时上限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("10~900"),
                    "报错须点名区间: " + ex.getMessage());
        }
        ServiceException garbage = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.llm-timeout-seconds", "abc", "系统", "string", 0, "提取链超时上限")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, garbage.getCode());
    }

    @Test
    void docLlmTimeoutKey_合法值与留空保存并热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.llm-timeout-seconds", "120", "系统", "string", 0, "提取链超时上限"));
        assertEquals("120", configService.getValue("ai.doc.llm-timeout-seconds", "300"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.llm-timeout-seconds", "", "系统", "string", 0, "提取链超时上限"));
        assertEquals("", configService.getValue("ai.doc.llm-timeout-seconds", "300"));
    }

    // ---- Question-generation plane provider-call ceiling L-C
    // write face, same 10~900 window as the global key): out-of-window and
    // garbage values answer a plain 400 naming the bounds; a valid value and
    // blank (global face) both save and hot-read ----
    @Test
    void judgeQaGenTimeoutKey_越界或非法_400点名区间() {
        for (String bad : List.of("9", "901", "0", "-5")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.judge.qa-gen.llm-timeout-seconds", bad, "系统", "string", 0, "出题链超时上限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("10~900"),
                    "报错须点名区间: " + ex.getMessage());
        }
        ServiceException garbage = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.judge.qa-gen.llm-timeout-seconds", "abc", "系统", "string", 0, "出题链超时上限")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, garbage.getCode());
    }

    @Test
    void judgeQaGenTimeoutKey_合法值与留空保存并热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.judge.qa-gen.llm-timeout-seconds", "120", "系统", "string", 0, "出题链超时上限"));
        assertEquals("120", configService.getValue("ai.judge.qa-gen.llm-timeout-seconds", "300"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.judge.qa-gen.llm-timeout-seconds", "", "系统", "string", 0, "出题链超时上限"));
        assertEquals("", configService.getValue("ai.judge.qa-gen.llm-timeout-seconds", "300"));
    }

    // ---- Final-extract bucket ceiling : negative
    // answers a plain 400 naming the rule; blank (automatic face), 0 (disabled)
    // and a positive ceiling all save and hot-read ----
    @Test
    void finalBucketMaxTokens_负数_400人话含规则() {
        for (String bad : List.of("-1", "-40000")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.final-bucket-max-tokens", bad, "系统", "string", 0, "分桶上限")),
                    "负值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("正整数"),
                    "报错须点名取值规则: " + ex.getMessage());
        }
    }

    @Test
    void finalBucketMaxTokens_空零与正数均可保存热读() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.final-bucket-max-tokens", "", "系统", "string", 0, "分桶上限"));
        assertEquals("", configService.getValue("ai.doc.final-bucket-max-tokens", "50000"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.final-bucket-max-tokens", "0", "系统", "string", 0, "分桶上限"));
        assertEquals("0", configService.getValue("ai.doc.final-bucket-max-tokens", "50000"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.final-bucket-max-tokens", "40000", "系统", "string", 0, "分桶上限"));
        assertEquals("40000", configService.getValue("ai.doc.final-bucket-max-tokens", "50000"));
    }

    @Test
    void reviewFlagThreshold_越界_400人话含区间() {
        for (String bad : new String[] {"0", "0.05", "1.5", "-0.1"}) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.doc.review-flag-threshold", bad, "系统", "string", 0, "核对阈值")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0.1~1"),
                    "报错须点名允许区间: " + ex.getMessage());
        }
    }

    @Test
    void reviewFlagThreshold_非数字_400() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.doc.review-flag-threshold", "abc", "系统", "string", 0, "核对阈值")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void reviewFlagThreshold_区间内正常保存() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "ai.doc.review-flag-threshold", "0.7", "系统", "string", 0, "核对阈值"));
        assertEquals("0.7", configService.getValue("ai.doc.review-flag-threshold", "0.9"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.review-flag-threshold", "0.1", "系统", "string", 0, "核对阈值"));
        assertEquals("0.1", configService.getValue("ai.doc.review-flag-threshold", "0.9"));
        configService.update(1L, created.id(), new ConfigInput(
                "ai.doc.review-flag-threshold", "1", "系统", "string", 0, "核对阈值"));
        assertEquals("1", configService.getValue("ai.doc.review-flag-threshold", "0.9"));
    }

    @Test
    void llmTimeoutSeconds_低于10_400人话含区间() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "llm.timeout-seconds", "9", "模型", "int", 0, "超时")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("10~900"), "人话含允许区间（IT-LLM-01）: " + ex.getMessage());
    }

    @Test
    void llmTimeoutSeconds_高于900_400_更新路径同校验() {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                "llm.timeout-seconds", "30", "模型", "int", 0, "超时"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.update(1L, created.id(), new ConfigInput(
                        "llm.timeout-seconds", "901", "模型", "int", 0, "超时")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("10~900"));
        configService.update(1L, created.id(), new ConfigInput(
                "llm.timeout-seconds", "120", "模型", "int", 0, "超时"));
        assertEquals("120", configService.getValue("llm.timeout-seconds", "30"));
        configService.update(1L, created.id(), new ConfigInput(
                "llm.timeout-seconds", "300", "模型", "int", 0, "超时"));
        assertEquals("300", configService.getValue("llm.timeout-seconds", "30"));
        // New ceiling boundary (900) saves and hot-reads too.
        configService.update(1L, created.id(), new ConfigInput(
                "llm.timeout-seconds", "900", "模型", "int", 0, "超时"));
        assertEquals("900", configService.getValue("llm.timeout-seconds", "30"));
    }


    @Test
    void aiBatchMaxCount_非数字_400人话() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "ai.batch.max-count", "abc", "系统", "int", 0, "批量上限")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void aiBatchMaxCount_零负数越界_400人话含区间() {
        for (String bad : List.of("0", "-1", "501")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ai.batch.max-count", bad, "系统", "int", 0, "批量上限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1~500"),
                    "人话应含允许区间（IT-V8-01）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "ai.batch.max-count", "500", "系统", "int", 0, "批量上限"));
        configService.update(1L, configService.list("系统").stream()
                        .filter(c -> "ai.batch.max-count".equals(c.configKey()))
                        .findFirst().orElseThrow().id(),
                new ConfigInput("ai.batch.max-count", "1", "系统", "int", 0, "批量上限"));
        assertEquals("1", configService.getValue("ai.batch.max-count", "500"));
    }


    @Test
    void specExtractMaxChunks_越界_400人话含区间() {
        for (String bad : List.of("0", "-1", "201")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "apitest.spec-extract.max-chunks", bad, "系统", "int", 0, "抽取块上限")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1~200"),
                    "人话应含允许区间: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "apitest.spec-extract.max-chunks", "200", "系统", "int", 0, "抽取块上限"));
        configService.update(1L, configService.list("系统").stream()
                        .filter(c -> "apitest.spec-extract.max-chunks".equals(c.configKey()))
                        .findFirst().orElseThrow().id(),
                new ConfigInput("apitest.spec-extract.max-chunks", "1", "系统", "int", 0, "抽取块上限"));
        assertEquals("1", configService.getValue("apitest.spec-extract.max-chunks", "50"));
    }


    @Test
    void ciVerdictPassRate_非数字与越界_400人话含区间() {
        for (String bad : List.of("abc", "-0.1", "1.5")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ci.verdict.pass-rate", bad, "系统", "string", 0, "verdict 阈值")),
                    "非法值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0~1"),
                    "人话应含允许区间（IT-V5-01）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "ci.verdict.pass-rate", "1", "系统", "string", 0, "verdict 阈值"));
    }

    @Test
    void ciVerdictP0Zero_非01_400人话() {
        for (String bad : List.of("2", "-1", "yes")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "ci.verdict.p0-zero", bad, "系统", "int", 0, "P0 零失败开关")),
                    "非法开关值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0 或 1"),
                    "人话应含合法值（IT-V5-01）: " + ex.getMessage());
        }
    }


    @Test
    void jiraSync两键_非法值_400人话() {
        for (String bad : List.of("2", "-1", "yes")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "jira.sync.enabled", bad, "系统", "int", 0, "JIRA 同步开关")),
                    "非法开关值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0 或 1"),
                    "人话应含合法值（IT-V3-03）: " + ex.getMessage());
        }
        for (String bad : List.of("abc", "0", "-1", "61")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "jira.sync.timeout-seconds", bad, "系统", "int", 0, "JIRA 出站超时")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1~60"),
                    "人话应含允许区间（IT-V3-03）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "jira.sync.timeout-seconds", "60", "系统", "int", 0, "JIRA 出站超时"));
    }


    @Test
    void defectAutoCreate两键_非法值_400人话() {
        for (String bad : List.of("2", "-1", "yes")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "defect.auto-create.enabled", bad, "系统", "int", 0, "自动建单开关")),
                    "非法开关值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0 或 1"),
                    "人话应含合法值（IT-V3-01）: " + ex.getMessage());
        }
        for (String bad : List.of("abc", "0", "-1", "721")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "defect.auto-create.dedup-window", bad, "系统", "int", 0, "去重窗口")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1~720"),
                    "人话应含允许区间（IT-V3-01）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "defect.auto-create.dedup-window", "720", "系统", "int", 0, "去重窗口"));
    }


    @Test
    void execSnapshotMaxBytes_越界与非数字_400人话含区间() {
        for (String bad : List.of("abc", "0", "-1", "1023", "10485761")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "exec.snapshot.max-bytes", bad, "系统", "int", 0, "快照预算")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("1024"),
                    "人话应含允许区间下限（IT-V1-01）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "exec.snapshot.max-bytes", "1024", "系统", "int", 0, "快照预算"));
    }

    @Test
    void llmTimeoutSeconds_非数字_400人话() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "llm.timeout-seconds", "abc", "模型", "int", 0, "超时")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void aiScriptKey_越界_400人话含区间() {
        assertAiScriptRejected("ai.script.max-steps", "0", "1~500");
        assertAiScriptRejected("ai.script.max-steps", "-1", "1~500");
        assertAiScriptRejected("ai.script.max-steps", "501", "1~500");
        assertAiScriptRejected("ai.script.max-loop", "101", "1~100");
        assertAiScriptRejected("ai.script.assert-timeout-sec", "301", "1~300");
        assertAiScriptRejected("ai.script.assert-timeout-sec", "0", "1~300");
        assertAiScriptRejected("ai.script.max-nest-depth", "21", "1~20");
        assertAiScriptRejected("ai.script.selfheal-rounds", "31", "0~30");
        assertAiScriptRejected("ai.script.default-wait-sec", "61", "0~60");
        assertAiScriptRejected("ai.script.screenshot-mode", "4", "1~3");
        assertAiScriptRejected("ai.script.fail-policy", "3", "1~2");
    }

    @Test
    void aiScriptKey_合法边界_保存热更生效() {
        assertAiScriptBoundaryAccepted("ai.script.max-steps", "1", "500");
        assertAiScriptBoundaryAccepted("ai.script.max-loop", "1", "100");
        assertAiScriptBoundaryAccepted("ai.script.max-nest-depth", "1", "20");
        assertAiScriptBoundaryAccepted("ai.script.selfheal-rounds", "0", "30"); // 0 = self-healing off is valid; 30 = new upper bound after the widened range
        assertAiScriptBoundaryAccepted("ai.script.assert-timeout-sec", "1", "300");
        assertAiScriptBoundaryAccepted("ai.script.default-wait-sec", "0", "60");
        assertAiScriptBoundaryAccepted("ai.script.screenshot-mode", "1", "3");
        assertAiScriptBoundaryAccepted("ai.script.fail-policy", "1", "2");
        assertAiScriptBoundaryAccepted("ai.script.smart-wait", "bool", "true", "false");
        assertAiScriptBoundaryAccepted("ai.script.auto-scroll", "bool", "false", "true");
        Long stepsId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = 'ai.script.max-steps'", Long.class);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.update(1L, stepsId, new ConfigInput(
                        "ai.script.max-steps", "501", "系统", "int", 0, "边界")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    
    private void assertAiScriptBoundaryAccepted(String key, String lower, String upper) {
        assertAiScriptBoundaryAccepted(key, "int", lower, upper);
    }

    private void assertAiScriptBoundaryAccepted(String key, String valueType, String lower, String upper) {
        ConfigInfo created = configService.create(1L, new ConfigInput(
                key, lower, "系统", valueType, 0, "IT-AIS-01 边界"));
        assertEquals(lower, configService.getValue(key, "<missing>"), key + " 下界应放行");
        configService.update(1L, created.id(), new ConfigInput(
                key, upper, "系统", valueType, 0, "IT-AIS-01 边界"));
        assertEquals(upper, configService.getValue(key, "<missing>"), key + " 上界应放行");
    }

    @Test
    void aiScriptKey_非数字_400人话() {
        assertAiScriptRejected("ai.script.max-steps", "int", "abc", null);
        assertAiScriptRejected("ai.script.assert-timeout-sec", "int", "1.5", null);
        assertAiScriptRejected("ai.script.smart-wait", "bool", "yes", null);
    }

    // ---- AO dialog config keys (V296): ai.chat.* write-side validation ----

    @Test
    void aiChatIntervalKey_越界与非数字_400人话含区间() {
        // ranges quoted from the AO adjudication sheet: first-frame budget 2~30s, heartbeat 2~60s
        assertAiScriptRejected("ai.chat.first-frame-budget-seconds", "1", "2~30");
        assertAiScriptRejected("ai.chat.first-frame-budget-seconds", "31", "2~30");
        assertAiScriptRejected("ai.chat.first-frame-budget-seconds", "abc", null);
        assertAiScriptRejected("ai.chat.heartbeat-seconds", "1", "2~60");
        assertAiScriptRejected("ai.chat.heartbeat-seconds", "61", "2~60");
        assertAiScriptRejected("ai.chat.composer-max-rows", "2", "3~20");
        assertAiScriptRejected("ai.chat.composer-max-rows", "21", "3~20");
    }

    @Test
    void aiChatIntervalKey_合法边界_保存热更生效() {
        assertAiScriptBoundaryAccepted("ai.chat.first-frame-budget-seconds", "2", "30");
        assertAiScriptBoundaryAccepted("ai.chat.heartbeat-seconds", "2", "60");
        assertAiScriptBoundaryAccepted("ai.chat.composer-max-rows", "3", "20");
    }

    @Test
    void aiChatExecEnabled_仅bool放行() {
        assertAiScriptRejected("ai.chat.exec-enabled", "bool", "yes", null);
        assertAiScriptRejected("ai.chat.exec-enabled", "bool", "1", null);
        assertAiScriptBoundaryAccepted("ai.chat.exec-enabled", "bool", "true", "false");
    }

    @Test
    void aiChatPasteTableConvertKey_仅bool放行() {
        // Same bool face as the group master switch: typed 0/1 or prose is
        // refused with the true/false wording; both legal values persist hot.
        assertAiScriptRejected("ai.chat.paste-table-convert.enabled", "bool", "yes", null);
        assertAiScriptRejected("ai.chat.paste-table-convert.enabled", "bool", "0", null);
        assertAiScriptBoundaryAccepted("ai.chat.paste-table-convert.enabled", "bool", "true", "false");
    }

    // ---- AO grounding config keys (V298): ai.chat.grounding.* write side ----

    @Test
    void aiChatGroundingIntervalKeys_越界与非数字_400人话含区间() {
        assertAiScriptRejected("ai.chat.grounding.max-citations", "0", "1~10");
        assertAiScriptRejected("ai.chat.grounding.max-citations", "11", "1~10");
        assertAiScriptRejected("ai.chat.grounding.max-citations", "abc", null);
        assertAiScriptRejected("ai.chat.grounding.excerpt-chars", "39", "40~600");
        assertAiScriptRejected("ai.chat.grounding.excerpt-chars", "601", "40~600");
        assertAiScriptRejected("ai.chat.grounding.min-score", "-0.1", "0.0~1.0");
        assertAiScriptRejected("ai.chat.grounding.min-score", "1.5", "0.0~1.0");
        assertAiScriptRejected("ai.chat.grounding.min-score", "abc", null);
    }

    // ---- knife 4b ref-chip cap key (V299): ai.chat.ref.max-objects write side ----

    @Test
    void aiChatRefCapKey_越界与非数字_400人话含区间() {
        assertAiScriptRejected("ai.chat.ref.max-objects", "0", "1~20");
        assertAiScriptRejected("ai.chat.ref.max-objects", "21", "1~20");
        assertAiScriptRejected("ai.chat.ref.max-objects", "abc", null);
    }

    // ---- multi-step plan card threshold key: ai.chat.plan-threshold write side ----

    @Test
    void aiChatPlanThresholdRejectsOutOfRangeValues() {
        assertAiScriptRejected("ai.chat.plan-threshold", "1", "2~10");
        assertAiScriptRejected("ai.chat.plan-threshold", "11", "2~10");
        assertAiScriptRejected("ai.chat.plan-threshold", "abc", null);
        assertAiScriptBoundaryAccepted("ai.chat.plan-threshold", "2", "10");
    }

    @Test
    void aiChatGroundingKeys_合法边界_保存热更生效() {
        assertAiScriptBoundaryAccepted("ai.chat.grounding.max-citations", "1", "10");
        assertAiScriptBoundaryAccepted("ai.chat.grounding.excerpt-chars", "40", "600");
        assertAiScriptBoundaryAccepted("ai.chat.grounding.min-score", "string", "0.0", "1.0");
    }

    // ---- AX grounding material-role keys: ai.ground.* write side (V400) ----

    @Test
    void aiGroundKeysRejectOutOfRangeAndNonNumeric() {
        // stale window: days an untouched material stays trusted (1~365);
        // strong-score: bar between high and weak material relevance (0.0~1.0)
        assertAiScriptRejected("ai.ground.stale-window-days", "0", "1~365");
        assertAiScriptRejected("ai.ground.stale-window-days", "366", "1~365");
        assertAiScriptRejected("ai.ground.stale-window-days", "abc", null);
        assertAiScriptRejected("ai.ground.strong-score", "string", "-0.1", "0.0~1.0");
        assertAiScriptRejected("ai.ground.strong-score", "string", "1.5", "0.0~1.0");
        assertAiScriptRejected("ai.ground.strong-score", "string", "abc", null);
    }

    @Test
    void aiGroundKeysAcceptLegalBoundaryValues() {
        assertAiScriptBoundaryAccepted("ai.ground.stale-window-days", "1", "365");
        assertAiScriptBoundaryAccepted("ai.ground.strong-score", "string", "0.0", "1.0");
    }

    // ---- AJ dead-letter auto-replay keys: mq.dlq.* write side (V403) ----

    @Test
    void dlqAutoReplayKeysRejectOutOfRangeAndNonNumeric() {
        // cadence floor 60s~1 day, batch cap 1~500, round budget 1~10,
        // alert threshold 1~100000, master switch strictly 0 or 1
        assertAiScriptRejected("mq.dlq.auto-replay.enabled", "2", "0~1");
        assertAiScriptRejected("mq.dlq.auto-replay.interval-seconds", "59", "60~86400");
        assertAiScriptRejected("mq.dlq.auto-replay.batch-limit", "501", "1~500");
        assertAiScriptRejected("mq.dlq.auto-replay.max-rounds", "abc", null);
        assertAiScriptRejected("mq.dlq.backlog-alert-threshold", "0", "1~100000");
    }

    @Test
    void dlqAutoReplayKeysAcceptLegalBoundaryValues() {
        assertAiScriptBoundaryAccepted("mq.dlq.auto-replay.enabled", "0", "1");
        assertAiScriptBoundaryAccepted("mq.dlq.auto-replay.interval-seconds", "60", "86400");
        assertAiScriptBoundaryAccepted("mq.dlq.auto-replay.max-rounds", "1", "10");
        assertAiScriptBoundaryAccepted("mq.dlq.backlog-alert-threshold", "1", "100000");
    }

    
    private void assertAiScriptRejected(String key, String value, String expectedRange) {
        assertAiScriptRejected(key, "int", value, expectedRange);
    }


    @Test
    void 飞书开关_非法值_400人话() {
        for (String bad : List.of("2", "-1", "yes")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "notify.feishu.enabled", bad, "通知", "int", 0, "飞书渠道开关")),
                    "非法开关值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0 或 1"),
                    "人话应含合法值（IT-V6-01）: " + ex.getMessage());
        }
        configService.create(1L, new ConfigInput(
                "notify.feishu.enabled", "1", "通知", "int", 0, "飞书渠道开关"));
    }

    @Test
    void 飞书webhook_内网与非https_400SSRF拒() {
        for (String bad : List.of("http://10.0.0.8/hook", "http://example.com/hook",
                "ftp://open.feishu.cn/hook", "not-a-url")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "notify.feishu.webhook", bad, "通知", "string", 0, "飞书 webhook")),
                    "SSRF/非法 URL 应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "应 400: " + bad);
        }
    }

    @Test
    void 飞书webhook_https与loopback夹具面与空值_放行() {
        for (String ok : List.of("", "https://open.feishu.cn/open-apis/bot/v2/hook/abc",
                "http://127.0.0.1:9999/hook", "http://localhost:9999/hook")) {
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = 'notify.feishu.webhook'");
            configService.create(1L, new ConfigInput(
                    "notify.feishu.webhook", ok, "通知", "string", 0, "飞书 webhook 合法面"));
        }
    }


    @Test
    void promptGenTrafficPct_越界与非数字_400人话含区间() {
        for (String bad : List.of("-1", "101")) {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(
                            "prompt.gen.traffic-pct", bad, "系统", "int", 0, "灰度流量键探针")),
                    "越界值应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("0~100"),
                    "人话应含允许区间（IT-D4-01）: " + ex.getMessage());
        }
        assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(
                        "prompt.gen.traffic-pct", "abc", "系统", "int", 0, "灰度流量键探针")),
                "非数字应拒");
        configService.create(1L, new ConfigInput(
                "prompt.gen.traffic-pct", "100", "系统", "int", 0, "灰度流量键探针"));
        assertEquals("100", configService.getValue("prompt.gen.traffic-pct", "0"));
    }


    @Test
    void 并行度两键_负数与非数字_400人话_0放行() {
        for (String key : List.of("exec.parallel.max", "exec.parallel.per-project")) {
            ServiceException neg = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(key, "-1", "系统", "int", 0, "I5 探针")),
                    "负数应拒: " + key);
            assertEquals(BizErrorCode.VALIDATION_ERROR, neg.getCode());
            assertTrue(neg.getMessage().contains("非负整数"), "人话应含非负整数: " + neg.getMessage());
            ServiceException nan = assertThrows(ServiceException.class, () ->
                    configService.create(1L, new ConfigInput(key, "abc", "系统", "int", 0, "I5 探针")),
                    "非数字应拒: " + key);
            assertTrue(nan.getMessage().contains("整数"), "人话应含整数: " + nan.getMessage());
            configService.create(1L, new ConfigInput(key, "0", "系统", "int", 0, "I5 探针"));
            assertEquals("0", configService.getValue(key, "9"));
        }
    }

    private void assertAiScriptRejected(String key, String valueType, String value, String expectedRange) {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                configService.create(1L, new ConfigInput(key, value, "系统", valueType, 0, "IT-AIS-01 探针")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), key + "=" + value + " 应 400");
        if (expectedRange != null) {
            assertTrue(ex.getMessage().contains(expectedRange),
                    "人话含允许区间: " + ex.getMessage());
        }
    }
}
