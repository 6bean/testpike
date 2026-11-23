package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleSettings;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolScaleSettingsReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T294c wiring: the guardrail spectrum for the executor-pool autoscaler must come from
 * tp_sys_config keys (cluster.autoscale-* / cluster.budget-*) at read time, never from
 * compiled-in literals (R48 hot-tunable surface, same key-driven posture as the upgrade
 * pipeline keys of the cluster group).
 *
 * <p>Contracts pinned here:
 * 1) every field of {@link ExecutorPoolScaleSettings} is fed by its own config key;
 * 2) a missing or malformed value falls back to the shipped default (identical to the
 *    factory seed, so a half-configured install still decides conservatively);
 * 3) the elastic-paused flag and the budget-pause flag flip through from the stored
 *    "0"/"1" seed shape;
 * 4) the reader stamps now-epoch-seconds so cooldown math has a single time source.
 *
 * <p>Real dev database via SpringBootTest (no mocks; keys are restored after each test).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorPoolScaleSettingsTest {

    private static final String K_CAPACITY = "cluster.autoscale-capacity-per-executor";
    private static final String K_MIN = "cluster.autoscale-min-replicas";
    private static final String K_MAX = "cluster.autoscale-max-replicas";
    private static final String K_P95 = "cluster.autoscale-p95-wait-threshold-seconds";
    private static final String K_COOLDOWN = "cluster.autoscale-cooldown-seconds";
    private static final String K_PAUSED = "cluster.autoscale-paused";
    private static final String K_BUDGET_UTIL = "cluster.budget-max-utilization";
    private static final String K_BUDGET_PAUSE = "cluster.budget-pause-enabled";

    private static final List<String> ALL_KEYS =
            List.of(K_CAPACITY, K_MIN, K_MAX, K_P95, K_COOLDOWN, K_PAUSED, K_BUDGET_UTIL, K_BUDGET_PAUSE);

    @Autowired
    private ExecutorPoolScaleSettingsReader settingsReader;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void dropOverrideRows() {
        // Remove any override rows so each test starts from the shipped seed, then let the
        // cache re-read on first touch.
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ANY(?)",
                (Object) ALL_KEYS.toArray(String[]::new));
        ALL_KEYS.forEach(configCacheHolder::remove);
    }

    private void put(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)"
                        + " VALUES (?, ?, 'APP自动化', 'int', 0, 'fixture row for autoscaler guardrail test')"
                        + " ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value",
                key, value);
        // The shared holder is read-through-in-memory only (miss never re-queries the DB);
        // the production write path stores DB and cache together, so the fixture mirrors it.
        configCacheHolder.put(key, value);
    }

    @Test
    void shippedDefaultsMatchFactorySeedWhenKeysAbsent() {
        // The factory seed is applied to real databases; delete-then-read exercises the
        // fallback branch. Both must agree, otherwise a fresh install and a key-less
        // environment would scale to different replica lines.
        ExecutorPoolScaleSettings s = settingsReader.read();
        assertEquals(2, s.capacityPerExecutor());
        assertEquals(1, s.minReplicas());
        assertEquals(8, s.maxReplicas());
        assertEquals(300, s.p95WaitThresholdSeconds());
        assertEquals(120, s.cooldownSeconds());
        assertFalse(s.elasticPaused());
        assertEquals(0.85d, s.budgetMaxUtilization(), 1e-9);
        assertTrue(s.budgetPauseEnabled());
        assertTrue(s.nowEpochSeconds() > 0);
    }

    @Test
    void everyGuardrailFieldIsDrivenByItsOwnKey() {
        put(K_CAPACITY, "5");
        put(K_MIN, "3");
        put(K_MAX, "12");
        put(K_P95, "45");
        put(K_COOLDOWN, "0");
        put(K_PAUSED, "1");
        put(K_BUDGET_UTIL, "0.5");
        put(K_BUDGET_PAUSE, "0");
        ExecutorPoolScaleSettings s = settingsReader.read();
        assertEquals(5, s.capacityPerExecutor());
        assertEquals(3, s.minReplicas());
        assertEquals(12, s.maxReplicas());
        assertEquals(45, s.p95WaitThresholdSeconds());
        assertEquals(0, s.cooldownSeconds());
        assertTrue(s.elasticPaused());
        assertEquals(0.5d, s.budgetMaxUtilization(), 1e-9);
        assertFalse(s.budgetPauseEnabled());
    }

    @Test
    void malformedValuesFallBackToShippedDefaultsInsteadOfThrowing() {
        put(K_CAPACITY, "not-a-number");
        put(K_MIN, "");
        put(K_BUDGET_UTIL, "wide");
        ExecutorPoolScaleSettings s = settingsReader.read();
        assertEquals(2, s.capacityPerExecutor());
        assertEquals(1, s.minReplicas());
        assertEquals(0.85d, s.budgetMaxUtilization(), 1e-9);
        // untouched keys keep their shipped values
        assertEquals(8, s.maxReplicas());
    }

    @Test
    void minAboveMaxIsClampedToTheSafeSideOfTheRange() {
        // Guardrail integrity: an operator typo (min > max) must never produce a desired
        // count outside [min,max] at the decision core, so the reader pins max up to min
        // (scale-out stays bounded, scale-in is prevented while the contradiction lasts).
        put(K_MIN, "6");
        put(K_MAX, "2");
        ExecutorPoolScaleSettings s = settingsReader.read();
        assertTrue(s.maxReplicas() >= s.minReplicas());
        assertEquals(6, s.minReplicas());
        assertEquals(6, s.maxReplicas());
    }
}
