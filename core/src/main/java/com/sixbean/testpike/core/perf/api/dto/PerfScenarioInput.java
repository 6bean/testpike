package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 *
 * @param isolationMode Declared isolation mode (SHADOW_TABLE/NS_ACCOUNT/HEADER_MARK; null = not
 * declared, which is rejected by the safety gate when the target env is production)
 * @param datasetId Bound CSV dataset (tp_perf_dataset.id, same-project enforced at save;
 * null = scenario runs without a data pool — today's behaviour)
 * @param rowAllocMode Row allocation strategy (PerfRowAllocModes value domain; only legal
 * together with datasetId; null = resolve the K8 default key at dispatch)
 * @param scheduleCron Scheduled-firing cron (Spring 6-segment, validated fail-closed on
 * save; null/blank = the scenario is not scheduled)
 * @param scheduleEnabled Scheduled-firing switch (null = off; enabling without a cron is
 * refused — a switch that looks on but never acts is a dead control)
 * @param scheduleEnvId Pinned launch-time environment override for scheduled firing
 * (GB-1 semantics; must belong to the same project; null = press the
 * scenario default env)
 * @param source What the scenario runs: DSL step orchestration (null/blank = default)
 * or CUSTOM (a user-authored engine script mounted from object
 * storage, B-9); the two forms are mutually exclusive
 * @param scriptBody Raw CUSTOM script text (storage-bound at save; must be absent for
 * DSL scenarios)
 * @param engine Explicit engine binding (K6/GATLING/JMETER — value domain
 * PerfRunDispatchSupport.ENGINE_VALUES). Required for CUSTOM,
 * optional pin for DSL step orchestration; null/blank = the
 * default routing key decides the engine at dispatch
 */
public record PerfScenarioInput(String name, Long envId, String pressureModel,
                                Map<String, Integer> pressureConfig, String loginStrategy,
                                Integer driverLimit, List<Map<String, Object>> steps,
                                Map<String, Double> slaJson, String isolationMode,
                                Long datasetId, String rowAllocMode,
                                String scheduleCron, Boolean scheduleEnabled,
                                Long scheduleEnvId,
                                String source, String scriptBody, String engine) {

    /** Legacy call-site form: isolationMode absent = not declared (legal only for non-production scenarios). */
    public PerfScenarioInput(String name, Long envId, String pressureModel,
                             Map<String, Integer> pressureConfig, String loginStrategy,
                             Integer driverLimit, List<Map<String, Object>> steps,
                             Map<String, Double> slaJson) {
        this(name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit, steps, slaJson,
                null, null, null, null, null, null, null, null, null);
    }

    /** Call sites that carry the isolation declaration but no data-pool binding . */
    public PerfScenarioInput(String name, Long envId, String pressureModel,
                             Map<String, Integer> pressureConfig, String loginStrategy,
                             Integer driverLimit, List<Map<String, Object>> steps,
                             Map<String, Double> slaJson, String isolationMode) {
        this(name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit, steps, slaJson,
                isolationMode, null, null, null, null, null, null, null, null);
    }

    /** Call sites that carry the data-pool binding but no scheduled-firing declaration. */
    public PerfScenarioInput(String name, Long envId, String pressureModel,
                             Map<String, Integer> pressureConfig, String loginStrategy,
                             Integer driverLimit, List<Map<String, Object>> steps,
                             Map<String, Double> slaJson, String isolationMode,
                             Long datasetId, String rowAllocMode) {
        this(name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit, steps, slaJson,
                isolationMode, datasetId, rowAllocMode, null, null, null, null, null, null);
    }

    /** Call sites that carry the scheduled-firing declaration but no custom-script mount (B-9 era form). */
    public PerfScenarioInput(String name, Long envId, String pressureModel,
                             Map<String, Integer> pressureConfig, String loginStrategy,
                             Integer driverLimit, List<Map<String, Object>> steps,
                             Map<String, Double> slaJson, String isolationMode,
                             Long datasetId, String rowAllocMode,
                             String scheduleCron, Boolean scheduleEnabled, Long scheduleEnvId) {
        this(name, envId, pressureModel, pressureConfig, loginStrategy, driverLimit, steps, slaJson,
                isolationMode, datasetId, rowAllocMode, scheduleCron, scheduleEnabled, scheduleEnvId,
                null, null, null);
    }
}
