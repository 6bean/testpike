package com.sixbean.testpike.admin.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 *
 * @param isolationMode Declared isolation mode (SHADOW_TABLE/NS_ACCOUNT/HEADER_MARK; null = not
 * declared — the launch-time safety gate rejects a missing declaration when
 * the scenario targets production)
 * @param datasetId Bound CSV dataset (same-project enforced at core save; null = no data pool)
 * @param rowAllocMode Row allocation strategy (SEQUENTIAL_EXCLUSIVE/VUSER_EXCLUSIVE/CYCLE;
 * only legal with datasetId; null = K8 default key at dispatch)
 * @param scheduleCron Scheduled-firing cron (Spring 6-segment; core validates fail-closed
 * on save; null/blank = the scenario is not scheduled)
 * @param scheduleEnabled Scheduled-firing switch (null = off; refused without a cron at core)
 * @param scheduleEnvId Pinned launch-time environment for scheduled firing (same-project
 * enforced at core; null = press the scenario default env)
 * @param source Scenario kind (null/blank = DSL default; CUSTOM mounts the
 * script body — mirrored field-for-field to core)
 * @param scriptBody Raw CUSTOM script text (must be absent for DSL scenarios)
 * @param engine Engine binding for CUSTOM scenarios (K6/GATLING)
 */
public record PerfScenarioSaveRequest(long projectId, String name, Long envId,
                                      String pressureModel, Map<String, Integer> pressureConfig,
                                      String loginStrategy, Integer driverLimit,
                                      List<Map<String, Object>> steps,
                                      Map<String, Double> slaJson, String isolationMode,
                                      Long datasetId, String rowAllocMode,
                                      String scheduleCron, Boolean scheduleEnabled,
                                      Long scheduleEnvId,
                                      String source, String scriptBody, String engine) {

    /** Call sites that predate the isolation declaration (kept for bridge test fixtures). */
    public PerfScenarioSaveRequest(long projectId, String name, Long envId,
                                   String pressureModel, Map<String, Integer> pressureConfig,
                                   String loginStrategy, Integer driverLimit,
                                   List<Map<String, Object>> steps, Map<String, Double> slaJson) {
        this(projectId, name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit,
                steps, slaJson, null, null, null, null, null, null, null, null, null);
    }

    /** Call sites that carry the isolation declaration but no data-pool binding. */
    public PerfScenarioSaveRequest(long projectId, String name, Long envId,
                                   String pressureModel, Map<String, Integer> pressureConfig,
                                   String loginStrategy, Integer driverLimit,
                                   List<Map<String, Object>> steps, Map<String, Double> slaJson,
                                   String isolationMode) {
        this(projectId, name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit,
                steps, slaJson, isolationMode, null, null, null, null, null, null, null, null);
    }

    /** Call sites that carry the data-pool binding but no scheduled-firing declaration. */
    public PerfScenarioSaveRequest(long projectId, String name, Long envId,
                                   String pressureModel, Map<String, Integer> pressureConfig,
                                   String loginStrategy, Integer driverLimit,
                                   List<Map<String, Object>> steps, Map<String, Double> slaJson,
                                   String isolationMode, Long datasetId, String rowAllocMode) {
        this(projectId, name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit,
                steps, slaJson, isolationMode, datasetId, rowAllocMode, null, null, null,
                null, null, null);
    }
}
