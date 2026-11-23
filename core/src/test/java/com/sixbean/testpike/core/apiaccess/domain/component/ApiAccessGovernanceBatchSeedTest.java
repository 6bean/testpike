package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch-registered read governance rows (migration V384) against the live database, read back
 * in the same shape the migration ships. Two directions are asserted so the verdict cannot rot:
 * every key the migration inserts is registered and reachable, and the credential-adjacent reads
 * that were deliberately refused (presigned upload/download grants, task rows carrying the raw
 * claim token) have no row at all — they stay behind the fail-closed branch by design, not by
 * accident.
 *
 * <p>Real dev database, no mocks: registering is a data-side action that takes effect without a
 * rebuild, so the only honest check is the live table matching the migration file.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiAccessGovernanceBatchSeedTest {

    /** The keys that were refused because they hand out bearer-grade material (presigned URLs, raw claim tokens). */
    private static final List<String> REFUSED_KEYS = List.of(
            "GET /internal/executors/tasks/{taskId}",
            "GET /internal/executors/{executorId}/tasks",
            "GET /internal/judge/runs/{runId}/artifact-grant",
            "GET /internal/mock/jobs/{id}/artifact-grant",
            "GET /internal/skills/market/{id}/download");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<String> migrationKeys() throws IOException {
        Path file = Path.of("db/migration/V384__ai_api_access_governance_batch_reads.sql");
        if (!Files.exists(file)) {
            // Surefire runs from the module directory; fall back to the repository root.
            file = Path.of("../db/migration/V384__ai_api_access_governance_batch_reads.sql");
        }
        String sql = Files.readString(file);
        Matcher m = Pattern.compile("\\('([^']+)\\', 1, 1,").matcher(sql);
        List<String> keys = new java.util.ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    @Test
    void 推荐批读条目全部按迁移登记且可达() throws IOException {
        List<String> keys = migrationKeys();
        assertEquals(50, keys.size(), "迁移登记行数与过判账不符（应=50 条推荐批 A 类）");
        int missing = 0;
        for (String key : keys) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_api_access_governance"
                            + " WHERE endpoint_key = ? AND scope_verified = 1 AND reachable = 1",
                    Integer.class, key);
            if (n == null || n != 1) {
                missing++;
            }
        }
        assertTrue(missing == 0, "活库缺登记行 " + missing + " 条（迁移未落库？）");
    }

    @Test
    void 签发类读条目封闭登记维持拒绝() {
        // Effect contract after the full-coverage ledger: issuance-family entries are registered
        // but CLOSED (sv=0 reachable=0) - they must never appear opened, whatever the row count.
        for (String key : REFUSED_KEYS) {
            Integer opened = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_api_access_governance"
                            + " WHERE endpoint_key = ? AND (scope_verified = 1 OR reachable = 1)",
                    Integer.class, key);
            assertEquals(0, opened, key + " 属签发即凭据家族，必须维持拒绝（登记行不得成为放行凭证）");
            Integer closed = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_api_access_governance"
                            + " WHERE endpoint_key = ? AND scope_verified = 0 AND reachable = 0",
                    Integer.class, key);
            assertEquals(1, closed, key + " 覆盖账要求在册封闭态");
        }
    }
}
