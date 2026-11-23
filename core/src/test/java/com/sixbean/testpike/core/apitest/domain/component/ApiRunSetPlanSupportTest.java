package com.sixbean.testpike.core.apitest.domain.component;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collection-run plan support tests .
 * <p>Covers the CHAIN_DEP reference graph built from the pinned definition
 * snapshot: which later steps consume which earlier step's extracted keys, and
 * the three-spectrum interruption decision table (STOP / CONTINUE / CHAIN_DEP).
 * <p>Also locks the single-source discipline: the {{VAR:name}} reference regex
 * here must be identical in syntax to the executor's env_resolver._VAR_PATTERN
 * (red line 6 — no second diverging parser). The assertion reads the real
 * executor source file, not a copy of it.
 */
class ApiRunSetPlanSupportTest {

    /** One pinned step: id + method/path + assertion rows (extract carries saveAs). */
    private static String step(long id, String path, String assertionsJson) {
        return "{\"requestId\":" + id + ",\"method\":\"GET\",\"urlPath\":\"" + path
                + "\",\"headersJson\":[],\"bodyText\":\"\",\"authJson\":{},"
                + "\"assertionsJson\":" + assertionsJson + "}";
    }

    private static String extractRow(String saveAs) {
        return "{\"type\":\"extract\",\"saveAs\":\"" + saveAs + "\",\"path\":\"data.id\"}";
    }

    @Test
    void test_extract_keys_and_references_build_dependency_graph() {
        String pinned = "["
                + step(10, "/api/login", "[" + extractRow("token") + "]")
                + "," + step(11, "/api/order/{{VAR:token}}", "[]")
                + "," + step(12, "/api/static", "[]")
                + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);

        assertEquals(3, plan.steps().size());
        // step 1 is the producer of "token"; step 2 consumes it; step 3 is standalone
        List<Long> consumers = plan.consumersOf("token");
        assertEquals(List.of(11L), consumers, "only the login-token consumer depends on step 11's producer");
        assertTrue(plan.referencesOf(11L).contains("token"),
                "step 11 must record it consumes token");
        assertFalse(plan.referencesOf(12L).hasAny(), "static step has no variable references");
    }

    @Test
    void test_global_prefixed_reference_maps_to_its_producer_when_extracted() {
        // pool keys route through the same grammar; a global.-prefixed reference
        // is still a {{VAR:...}} syntax hit — the graph keys on the whole name
        String pinned = "["
                + step(20, "/a", "[" + extractRow("global.uid") + "]")
                + "," + step(21, "/b?id={{VAR:global.uid}}", "[]")
                + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);
        assertEquals(List.of(21L), plan.consumersOf("global.uid"));
    }

    @Test
    void test_stop_mode_first_failure_has_no_dispatchable_remainder() {
        String pinned = "["
                + step(30, "/a", "[]")
                + "," + step(31, "/b", "[]")
                + "," + step(32, "/c", "[]")
                + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);

        // failed at seq 1 (step 30), mode STOP_ON_ASSERT_FAIL: nothing may continue
        List<Long> next = plan.nextDispatchable("STOP_ON_ASSERT_FAIL",
                List.of(new ApiRunSetPlanSupport.StepOutcome(30L, 2)));
        assertTrue(next.isEmpty(), "STOP mode: any failure freezes the remaining hops");
    }

    @Test
    void test_continue_mode_keeps_all_remaining_hops() {
        String pinned = "[" + step(40, "/a", "[]") + "," + step(41, "/b", "[]") + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);

        List<Long> next = plan.nextDispatchable("CONTINUE_AND_SUMMARY",
                List.of(new ApiRunSetPlanSupport.StepOutcome(40L, 3)));
        assertEquals(List.of(41L), next, "CONTINUE mode: later hops still dispatch after a failure");
    }

    @Test
    void test_chain_dep_mode_only_skips_downstream_of_failed_producer() {
        String pinned = "["
                + step(50, "/login", "[" + extractRow("token") + "]")
                + "," + step(51, "/order/{{VAR:token}}", "[]")
                + "," + step(52, "/health", "[]")
                + "," + step(53, "/report?id={{VAR:token}}", "[]")
                + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);

        // step 50 fails → its consumers 51/53 are skipped, the independent 52 goes on
        List<Long> next = plan.nextDispatchable("CHAIN_DEP_ONLY",
                List.of(new ApiRunSetPlanSupport.StepOutcome(50L, 2)));
        assertEquals(List.of(52L), next,
                "CHAIN_DEP mode: only steps whose variables come from a failed producer are suppressed");
    }

    @Test
    void test_chain_dep_mode_unaffected_when_failure_produces_nothing() {
        String pinned = "["
                + step(60, "/plain", "[]")
                + "," + step(61, "/after", "[]")
                + "]";
        ApiRunSetPlanSupport support = new ApiRunSetPlanSupport();
        ApiRunSetPlanSupport.RunSetPlan plan = support.buildPlan(pinned);

        List<Long> next = plan.nextDispatchable("CHAIN_DEP_ONLY",
                List.of(new ApiRunSetPlanSupport.StepOutcome(60L, 2)));
        assertEquals(List.of(61L), next,
                "a failure with no extracted keys blocks nobody under CHAIN_DEP");
    }

    @Test
    void test_var_reference_regex_is_single_source_with_executor_env_resolver() throws IOException {
        // Red line 6: the Java-side scanner and the executor parser must be the
        // same expression. Read the executor source, lift _VAR_PATTERN verbatim,
        // and demand byte equality with the Java pattern source.
        Path executorFile = findExecutorEnvResolver();
        String py = Files.readString(executorFile);
        Matcher m = Pattern.compile("_VAR_PATTERN\\s*=\\s*re\\.compile\\(r?\"([^\"]+)\"\\)")
                .matcher(py);
        assertTrue(m.find(), "executor env_resolver must still define _VAR_PATTERN "
                + "(if the executor changed, this guard must be re-aligned, not silenced)");
        String pyPattern = m.group(1);
        assertEquals(pyPattern, ApiRunSetPlanSupport.VAR_REF_PATTERN,
                "{{VAR:name}} grammar drifted between executor env_resolver and core run-set planner");
    }

    private static Path findExecutorEnvResolver() throws IOException {
        // tests run with core/ as the working directory; walk up to repo root
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("executor/driver/env_resolver.py");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("executor/driver/env_resolver.py not found from " + Path.of("").toAbsolutePath());
    }
}
