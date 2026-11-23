package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpTokenUsageView;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.domain.service.ApiTokenService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GA3 aggregate read surface for per-token MCP usage (segment S2b).
 * <p>The detail table {@code tp_tool_invocation} stays the single source; this
 * service is a read-only roll-up keyed by the token attribution column added in
 * segment S2a. The spectra pin: exact calls/failures/p95 triple (percentile_cont
 * over the duration column, legacy NULL durations excluded from the latency
 * percentile but still counted), rows without a token id never leak into any
 * per-token ledger, ownership = token owner only, admin read-through, and an
 * honest refusal for unknown token ids.
 */
@SpringBootTest(classes = CoreApplication.class)
class McpTokenUsageAggregationTest {

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private McpTokenUsageService mcpTokenUsageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long foreignId;
    private Long adminId;
    private final List<Long> createdTokenIds = new ArrayList<>();
    private final List<Long> writtenInvocationIds = new ArrayList<>();

    @BeforeEach
    void insertFixtureUsers() {
        ownerId = insertUser("ga3-agg-owner", 1);
        foreignId = insertUser("ga3-agg-foreign", 1);
        adminId = insertUser("ga3-agg-admin", 4);
    }

    @AfterEach
    void cleanupFixtures() {
        deleteInvoices();
        for (Long tokenId : createdTokenIds) {
            jdbcTemplate.update("DELETE FROM tp_api_token WHERE id = ?", tokenId);
        }
        createdTokenIds.clear();
        jdbcTemplate.update("DELETE FROM tp_user WHERE id IN (?, ?, ?)", ownerId, foreignId, adminId);
    }

    private Long insertUser(String username, int role) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, ?, 1) RETURNING id",
                Long.class, username, username, role);
    }

    private void deleteInvoices() {
        if (!writtenInvocationIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_tool_invocation WHERE id IN ("
                    + String.join(",", writtenInvocationIds.stream().map(String::valueOf).toList()) + ")");
            writtenInvocationIds.clear();
        }
    }

    private long newToken() {
        ApiTokenCreated created = apiTokenService.create(ownerId,
                new ApiTokenCreateInput("ga3-usage", "mcp.read", null));
        createdTokenIds.add(created.id());
        return created.id();
    }

    /** Insert one ledger row directly (write-path equivalent for fixture shapes). */
    private void insertInvocation(long tokenId, int success, Long durationMs) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tool_invocation (caller, tool_name, success, duration_ms, api_token_id) "
                        + "VALUES ('mcp', 'ga3-probe-tool', ?, ?, ?) RETURNING id",
                Long.class, success, durationMs, tokenId);
        writtenInvocationIds.add(id);
    }

    @Test
    void ownerSeesExactCallFailureAndP95Triple() {
        long tokenId = newToken();
        for (long ms : new long[]{10, 20, 30, 40, 50}) {
            insertInvocation(tokenId, 1, ms);
        }
        insertInvocation(tokenId, 0, null);
        insertInvocation(tokenId, 0, null);

        McpTokenUsageView view = mcpTokenUsageService.usage(tokenId, ownerId);

        assertEquals(tokenId, view.tokenId());
        assertEquals(7L, view.calls(), "all rows attributed to the token are counted");
        assertEquals(2L, view.failures(), "success=0 rows form the failure count");
        // percentile_cont interpolates between the 4th (40ms) and 5th (50ms)
        // rows at fraction 0.95*(5-1)-3 = 0.8 -> 48.0, verified against live PG.
        assertEquals(48.0, view.p95DurationMs(), 1e-6,
                "percentile_cont(0.95) over {10,20,30,40,50} is 48.0 (BIGINT cast to double)");
    }

    @Test
    void unattributedLegacyRowsNeverLeakIntoPerTokenLedger() {
        long tokenId = newToken();
        insertInvocation(tokenId, 1, 20L);
        Long legacyId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tool_invocation (caller, tool_name, success, duration_ms) "
                        + "VALUES ('ai', 'ga3-legacy-tool', 1, 999) RETURNING id",
                Long.class);
        writtenInvocationIds.add(legacyId);

        McpTokenUsageView view = mcpTokenUsageService.usage(tokenId, ownerId);

        assertEquals(1L, view.calls(), "rows with api_token_id IS NULL belong to no token ledger");
        assertEquals(20.0, view.p95DurationMs(), 1e-6);
    }

    @Test
    void emptyLedgerReportsZeroCallsAndNullP95() {
        long tokenId = newToken();

        McpTokenUsageView view = mcpTokenUsageService.usage(tokenId, ownerId);

        assertEquals(0L, view.calls());
        assertEquals(0L, view.failures());
        assertNull(view.p95DurationMs(), "no latency samples means no percentile, not 0 ms");
    }

    @Test
    void foreignOwnerIsRefusedAndUnknownTokenRejectedHonestly() {
        long tokenId = newToken();

        ServiceException foreign = assertThrows(ServiceException.class,
                () -> mcpTokenUsageService.usage(tokenId, foreignId));
        assertTrue(foreign.getMessage().contains("本人"),
                "refusal names the ownership rule, got: " + foreign.getMessage());

        ServiceException unknown = assertThrows(ServiceException.class,
                () -> mcpTokenUsageService.usage(9_999_999_999L, ownerId));
        assertTrue(unknown.getMessage().contains("不存在"),
                "missing token is reported as missing, got: " + unknown.getMessage());
    }

    @Test
    void adminReadsAnyTokenUsage() {
        long tokenId = newToken();
        insertInvocation(tokenId, 0, 5L);

        McpTokenUsageView view = mcpTokenUsageService.usage(tokenId, adminId);

        assertEquals(1L, view.calls());
        assertEquals(1L, view.failures());
        assertEquals(5.0, view.p95DurationMs(), 1e-6);
    }
}
