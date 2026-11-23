package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyResult;
import com.sixbean.testpike.core.token.domain.service.ApiTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-token attribution of MCP invocation ledger rows (GA3 observability
 * triad: calls / failures / p95 latency per API token).
 * <p>The detail table stays the single source (no aggregate table); these
 * spectra pin the two prerequisites of any honest per-token read surface:
 * the authentication point exposes which token was presented, and the ledger
 * write path persists that identity. Legacy call shapes without a token
 * dimension keep writing NULL rows (zero drift for ai/user callers).
 */
@SpringBootTest(classes = CoreApplication.class)
class McpInvocationTokenDimensionTest {

    @Autowired
    private ApiTokenService apiTokenService;

    @Autowired
    private McpConsumptionService mcpConsumptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long fixtureUserId;
    private final List<Long> createdTokenIds = new java.util.ArrayList<>();
    private final List<Long> writtenInvocationIds = new java.util.ArrayList<>();

    @BeforeEach
    void insertFixtureOwner() {
        fixtureUserId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES ('ga3-token-owner', 'x', 'ga3-token-owner', 1, 1) RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanupFixtures() {
        if (!writtenInvocationIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_tool_invocation WHERE id IN ("
                    + String.join(",", writtenInvocationIds.stream().map(String::valueOf).toList()) + ")");
        }
        for (Long tokenId : createdTokenIds) {
            jdbcTemplate.update("DELETE FROM tp_api_token WHERE id = ?", tokenId);
        }
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", fixtureUserId);
    }

    @Test
    void verifyExposesPresentedTokenIdentity() {
        ApiTokenCreated created = apiTokenService.create(fixtureUserId,
                new ApiTokenCreateInput("ga3-attr", "mcp.read", null));
        createdTokenIds.add(created.id());

        ApiTokenVerifyResult ok = apiTokenService.verify(created.plaintext());
        assertTrue(ok.ok());
        assertEquals(created.id(), ok.tokenId(),
                "verify must expose the presented token row id for per-token attribution");
        assertEquals(fixtureUserId, ok.userId());
    }

    @Test
    void recordPersistsTokenDimension() {
        long tokenId = 987654321L;
        int written = mcpConsumptionService.recordInvocations(List.of(
                new McpInvocationInput("mcp", fixtureUserId, null, null, null,
                        "list_cases", 1, 42L, null, "{}", null, tokenId)));
        assertEquals(1, written);
        Long ledgerId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_tool_invocation WHERE api_token_id = ? AND tool_name = 'list_cases'",
                Long.class, tokenId);
        writtenInvocationIds.add(ledgerId);
        Long storedToken = jdbcTemplate.queryForObject(
                "SELECT api_token_id FROM tp_tool_invocation WHERE id = ?", Long.class, ledgerId);
        assertEquals(tokenId, storedToken,
                "the ledger row must carry the persisted api_token_id attribution");
    }

    @Test
    void legacyShapeWithoutTokenStillWritesNull() {
        int written = mcpConsumptionService.recordInvocations(List.of(
                new McpInvocationInput("mcp", fixtureUserId, null, null, null,
                        "gen_helper", 1, 7L, null, null, null)));
        assertEquals(1, written);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_tool_invocation WHERE tool_name = 'gen_helper' "
                        + "AND caller = 'mcp' ORDER BY id DESC LIMIT 1", Long.class);
        writtenInvocationIds.add(id);
        Long tokenCol = jdbcTemplate.queryForObject(
                "SELECT api_token_id FROM tp_tool_invocation WHERE id = ?", Long.class, id);
        assertNull(tokenCol, "legacy 11-field shape keeps compiling and writes NULL token dimension");
    }
}
