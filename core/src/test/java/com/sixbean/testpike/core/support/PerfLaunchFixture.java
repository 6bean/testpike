package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixture shape for perf suites that must drive dispatch explicitly
 * (single source per R40 — this exact "launch then detach the auto row"
 * sequence used to be duplicated per suite): create a CONSTANT_RPS scenario,
 * launch the run through the real service chain, then purge the
 * launch-implies-dispatch auto row scoped to THIS fixture project so the
 * explicit create/claim channel under test owns dispatch timing. Suites that
 * test the auto-dispatch chain itself (PerfRunLaunchDispatchTest) must NOT
 * call this helper — that chain is theirs.
 */
public final class PerfLaunchFixture {

    private PerfLaunchFixture() {
    }

    public static long launchDetachedRun(JdbcTemplate jdbc, PerfScenarioService scenarios,
            PerfRunService runs, long projectId, long envId, String scenarioName,
            Map<String, Integer> config) {
        long scenarioId = scenarios.create(projectId, new PerfScenarioInput(
                scenarioName, envId, "CONSTANT_RPS", config, null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
        long runId = runs.launch(projectId, scenarioId, null);
        jdbc.update("DELETE FROM tp_exec_task WHERE schema_version = 'perf-v1'"
                + " AND project_id = ?", projectId);
        return runId;
    }
}
