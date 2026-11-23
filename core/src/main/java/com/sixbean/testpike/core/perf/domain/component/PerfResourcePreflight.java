package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only resource pre-flight readout (CF-5 + CF-7) shown in front of the
 * launch button: "who could run this, will I wait, and will the data pool
 * survive the plan". Nothing here blocks — rejection stays owned by the launch
 * transaction (A-1 gate, GA-2 lock, duration ceiling); this is the honest
 * advance notice the operator asked for instead of discovering shortages after
 * pressing start.
 * <p>Single-source discipline (red line 6):
 * <ul>
 *   <li>online/capability counts = {@link ExecutorService#listOnline()} +
 *       {@link AppAutoDispatchSupport#covers} — the same GA-4 judgement the
 *       dispatch early-rejection uses, never a second pool notion;</li>
 *   <li>queue depth = the shared dispatch queue itself (pending task_type=5
 *       rows), same counting rule as {@link PerfQueueNote};</li>
 *   <li>concurrency tier = {@link PerfPressureTier} (moved out of the launch
 *       gate verbatim), duration = {@link PerfBudgetPolicy#durationSecondsOf};</li>
 *   <li>row-allocation resolution = the launch pinning order (persisted column,
 *       else the K8 default) so the card describes what the run WILL do;</li>
 *   <li>wording projection = {@link PerfCapacityPreflight} /
 *       {@link PerfDatasetHealthCheck} pure functions (unit-tested).</li>
 * </ul>
 * Engine = the K8 default engine the dispatcher will pick (the pre-flight is
 * about the next launch, so it reads the same key the dispatcher reads).
 * Malformed scenario data degrades to "no claim" fields, never a 500.
 */
@Component
public class PerfResourcePreflight {

    /** tp_exec_task pending status / perf task type (PerfQueueNote same literals). */
    private static final int TASK_PENDING = 1;
    private static final int TASK_TYPE_PERF = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final PerfConfigReader perfConfigReader;
    private final PerfPressureTier perfPressureTier;

    public PerfResourcePreflight(NamedParameterJdbcTemplate jdbc,
                                 ObjectMapper objectMapper,
                                 ExecutorService executorService,
                                 AppAutoDispatchSupport appAutoDispatchSupport,
                                 PerfConfigReader perfConfigReader,
                                 PerfPressureTier perfPressureTier) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.perfConfigReader = perfConfigReader;
        this.perfPressureTier = perfPressureTier;
    }

    /**
     * Pre-flight readout for one scenario's next launch. An unknown scenario
     * yields honest-empty fields (no capacity/dataset claim) — the launch door
     * owns existence checks with its own 404 wording.
     */
    public Map<String, Object> preflight(long projectId, long scenarioId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pressure_model, pressure_config::text AS pressure_config, "
                        + "dataset_id, row_alloc_mode "
                        + "FROM tp_perf_scenario WHERE id = :s AND project_id = :p",
                new MapSqlParameterSource("s", scenarioId).addValue("p", projectId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", perfConfigReader.defaultEngine());
        out.put("capacityNote", null);
        out.put("datasetNote", null);
        out.put("requiredConcurrency", null);
        if (rows.isEmpty()) {
            return out;   // honest emptiness — the launch check owns the 404 wording
        }
        Map<String, Object> scenario = rows.get(0);
        String model = String.valueOf(scenario.get("pressure_model"));
        String configJson = scenario.get("pressure_config") == null
                ? "{}" : String.valueOf(scenario.get("pressure_config"));
        Integer tier = perfPressureTier.of(model, configJson);
        out.put("requiredConcurrency", tier);

        int pendingAhead = queryPendingAhead();
        out.put("capacityNote", capacityNote(tier, String.valueOf(out.get("engine")), pendingAhead));
        out.put("datasetNote", datasetNote(scenario, tier, configJson));
        return out;
    }

    /** Pending perf tasks in the shared dispatch queue (no run id yet = whole queue). */
    private int queryPendingAhead() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE task_type = :tt AND status = :st",
                new MapSqlParameterSource("tt", TASK_TYPE_PERF).addValue("st", TASK_PENDING),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Fleet/capacity wording from the same online+capability judgement as dispatch. */
    private String capacityNote(Integer tier, String engine, int pendingAhead) {
        if (tier == null || tier <= 0) {
            return null;   // unresolvable tier = silent card (launch gate rejects fail-closed)
        }
        List<ExecutorInfo> online = executorService.listOnline();
        long capableCount = online.stream()
                .filter(e -> appAutoDispatchSupport.covers(e.capabilities(), "engines", engine))
                .count();
        int idleCapacity = online.stream()
                .filter(e -> appAutoDispatchSupport.covers(e.capabilities(), "engines", engine))
                .mapToInt(e -> Math.max(0, e.capacity() - e.runningCnt()))
                .sum();
        return PerfCapacityPreflight.noteOf(tier, engine, online.size(),
                (int) capableCount, idleCapacity, pendingAhead);
    }

    /** Pool demand wording; allocation resolution follows the launch pinning order. */
    private String datasetNote(Map<String, Object> scenario, Integer tier, String configJson) {
        Object datasetId = scenario.get("dataset_id");
        if (datasetId == null || tier == null) {
            return null;   // no pool bound = nothing to check (today's shape)
        }
        Object rawAlloc = scenario.get("row_alloc_mode");
        String allocMode = PerfRowAllocModes.firstNonBlank(
                rawAlloc == null ? null : String.valueOf(rawAlloc),
                perfConfigReader.datasetRowAllocMode());
        if (allocMode == null) {
            return null;
        }
        Long rowCount = jdbc.queryForList(
                "SELECT row_count FROM tp_perf_dataset WHERE id = :d",
                new MapSqlParameterSource("d", datasetId), Long.class)
                .stream().findFirst().orElse(null);
        if (rowCount == null) {
            return null;
        }
        double durationSeconds;
        try {
            JsonNode config = objectMapper.readTree(configJson);
            durationSeconds = PerfBudgetPolicy.durationSecondsOf(
                    String.valueOf(scenario.get("pressure_model")), config);
        } catch (Exception unparsable) {
            return null;   // unparsable config = no claim, never a fabricated demand
        }
        return PerfDatasetHealthCheck.noteOf(allocMode, rowCount, tier, durationSeconds);
    }
}
