package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution-channel tools of the MCP surface (exec.run / exec.status /
 * exec.cancel / report.fetch) — the gateway assembles the external-agent
 * contract on top of the very same core batch trigger the CI channel rides,
 * so a batch started by an agent is indistinguishable from one started on the
 * batch page or by /open/ci (single trigger source, no second run path).
 * <p>Tooling = StubCoreHttpServer (L2 test double speaking real HTTP — what is
 * under test here is the gateway's own URL/method/body/operator-header assembly,
 * not mocked business logic; the live chain is covered by L3, no level skipping).
 * <p>Assertion faces:
 * 1. exec.run with case_ids → membership guard, then exactly one batch create
 *    plus one start hop, receipt carries batch_id/case_count and names the two
 *    follow-up tools instead of leaving the agent guessing;
 * 2. exec.run with suite_id → the suite row is read through the existing core
 *    suite door and its own case list feeds the same create+start pair; a suite
 *    that belongs to another project is refused before any batch exists;
 * 3. selection and environment pre-checks (GA2) → ambiguous / empty selection and
 *    an environment outside the project refuse with zero batch hops;
 * 4. dry_run → preflight receipt only, zero batch hops;
 * 5. exec.status → owning project is resolved from the batch row and gated
 *    before any field is returned; a foreign project never sees the row;
 * 6. exec.cancel → forwards to the one core cancel door; a terminal batch is an
 *    idempotent zero-change receipt (no second stop path);
 * 7. report.fetch → pass-rate and counts come verbatim from the core badge and
 *    closeout snapshot (red line: the api layer never recomputes), the receipt is
 *    a human summary, not a row dump;
 * 8. core business failures ride through in human words.
 */
class McpExecToolGatewayTest {

    private static final long OPERATOR = 4242L;

    private static final String BATCH_1042_DRAFT =
            "{\"id\":1042,\"projectId\":11,\"batchNo\":\"B-ab12cd34\",\"name\":\"站外触发批次\","
                    + "\"envId\":77,\"envName\":\"测试环境\",\"status\":1,\"statusLabel\":\"草稿\","
                    + "\"type\":1,\"caseCount\":3,\"queuePos\":null,\"version\":0}";
    private static final String BATCH_1042_QUEUED =
            "{\"id\":1042,\"projectId\":11,\"batchNo\":\"B-ab12cd34\",\"name\":\"站外触发批次\","
                    + "\"envId\":77,\"envName\":\"测试环境\",\"status\":2,\"statusLabel\":\"待执行\","
                    + "\"type\":1,\"caseCount\":3,\"queuePos\":1,\"version\":1}";
    private static final String BATCH_1042_DONE =
            "{\"id\":1042,\"projectId\":11,\"batchNo\":\"B-ab12cd34\",\"name\":\"站外触发批次\","
                    + "\"envId\":77,\"envName\":\"测试环境\",\"status\":6,\"statusLabel\":\"已完成\","
                    + "\"type\":1,\"caseCount\":12,\"queuePos\":null,\"version\":5,"
                    + "\"startedAt\":\"2026-11-18 03:00:00\",\"finishedAt\":\"2026-11-18 03:12:00\","
                    + "\"envOngoingEpisode\":{\"episodeId\":9,\"level\":3,\"startedAt\":\"2026-11-18 03:05:00\"}}";
    private static final String START_RESULT_1042 =
            "{\"batchId\":1042,\"status\":2,\"statusKey\":\"queued\",\"statusLabel\":\"待执行\","
                    + "\"queuePos\":1,\"estimatedStartAt\":\"2026-11-18 03:40:00\","
                    + "\"skipCases\":[{\"caseId\":501,\"reason\":\"用例已删除\"}],\"message\":\"已受理 3 条用例，1 条跳过\"}";

    private StubCoreHttpServer core;
    private RestMcpToolGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        core = StubCoreHttpServer.start();
        gateway = new RestMcpToolGateway(core.baseUrl());
    }

    @AfterEach
    void stopStub() {
        core.close();
    }

    // ===== exec.run =====

    @Test
    void execRunWithCaseIdsGatesMembershipThenCreatesAndStartsExactlyOnce() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DRAFT)); // POST /internal/batches
        core.enqueue(StubCoreHttpServer.ok(START_RESULT_1042)); // POST /internal/batches/1042/start

        String receipt = gateway.invoke("exec.run", Map.of(
                "project_id", 11,
                "selection", Map.of("case_ids", List.of(101, 102, 103)),
                "env_id", 77), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "guard + create + start, no second trigger hop: "
                + core.calls());
        StubCoreHttpServer.RecordedCall create = core.call(1);
        assertEquals("POST", create.method());
        assertEquals("/internal/batches", create.path());
        assertEquals(String.valueOf(OPERATOR), create.lastOperatorHeader());
        assertTrue(create.body().contains("\"projectId\":11"), create.body());
        assertTrue(create.body().contains("101") && create.body().contains("102")
                        && create.body().contains("103"),
                "case ids must reach the batch member list: " + create.body());
        assertTrue(create.body().contains("\"envId\":77"), create.body());
        assertTrue(create.body().contains("站外触发批次") || create.body().contains("\"name\""),
                "a batch created by an agent still needs a readable name: " + create.body());

        StubCoreHttpServer.RecordedCall start = core.call(2);
        assertEquals("POST", start.method());
        assertEquals("/internal/batches/1042/start", start.path(),
                "same core start door as /open/ci (single trigger source)");
        assertEquals(String.valueOf(OPERATOR), start.lastOperatorHeader());

        assertTrue(receipt.contains("\"batch_id\":1042"), receipt);
        assertTrue(receipt.contains("\"case_count\":3"), receipt);
        assertTrue(receipt.contains("\"env_id\":77"), receipt);
        assertTrue(receipt.contains("exec.status"), "receipt must name the polling partner: " + receipt);
        assertTrue(receipt.contains("report.fetch"), "receipt must name the result partner: " + receipt);
        assertTrue(receipt.contains("已受理 3 条用例"), "core start wording rides through: " + receipt);
    }

    @Test
    void execRunWithSuiteIdReadsTheSuiteThroughTheExistingCoreDoor() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":66,\"projectId\":11,\"name\":\"登录回归集\",\"caseIds\":[201,202],"
                        + "\"envIds\":[77],\"status\":1}"));
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DRAFT));
        core.enqueue(StubCoreHttpServer.ok(START_RESULT_1042));

        String receipt = gateway.invoke("exec.run", Map.of(
                "project_id", 11,
                "selection", Map.of("suite_id", 66)), McpCaller.unrestricted(OPERATOR));

        assertEquals(4, core.callCount(), core.calls().toString());
        StubCoreHttpServer.RecordedCall suite = core.call(1);
        assertEquals("GET", suite.method());
        assertEquals("/internal/suites/66", suite.path());
        StubCoreHttpServer.RecordedCall create = core.call(2);
        assertEquals("/internal/batches", create.path());
        assertTrue(create.body().contains("201") && create.body().contains("202"),
                "the suite's own case list feeds the batch: " + create.body());
        assertTrue(receipt.contains("\"case_count\":2"), receipt);
    }

    @Test
    void execRunWithSuiteOfAnotherProjectRefusesBeforeAnyBatchExists() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate for the declared project
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":66,\"projectId\":12,\"name\":\"他项目回归集\",\"caseIds\":[201],\"status\":1}"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("suite_id", 66)), McpCaller.unrestricted(OPERATOR)));

        assertTrue(refusal.getMessage().contains("66"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("11"),
                "the wording must point at the declared project: " + refusal.getMessage());
        assertEquals(2, core.callCount(), "no batch may exist after a cross-project refusal: "
                + core.calls());
    }

    @Test
    void execRunRejectsAmbiguousAndEmptySelectionWithoutAnyHop() {
        ServiceException ambiguous = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("suite_id", 66, "case_ids", List.of(101))), McpCaller.unrestricted(OPERATOR)));
        assertTrue(ambiguous.getMessage().contains("suite_id")
                        && ambiguous.getMessage().contains("case_ids"),
                ambiguous.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of("project_id", 11), McpCaller.unrestricted(OPERATOR)));
        assertTrue(missing.getMessage().contains("selection"), missing.getMessage());

        ServiceException empty = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11, "selection", Map.of("case_ids", List.of())), McpCaller.unrestricted(OPERATOR)));
        assertTrue(empty.getMessage().contains("case_ids"), empty.getMessage());

        assertEquals(0, core.callCount(),
                "argument shape checks cost no core hop: " + core.calls());
    }

    @Test
    void execRunDryRunPreflightsAndQueuesNothing() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":66,\"projectId\":11,\"name\":\"登录回归集\",\"caseIds\":[201,202,203],"
                        + "\"status\":1}"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":77,\"projectId\":11,\"name\":\"测试环境\"}"));

        String receipt = gateway.invoke("exec.run", Map.of(
                "project_id", 11,
                "selection", Map.of("suite_id", 66),
                "env_id", 77,
                "dry_run", true), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "preflight must not create a batch: " + core.calls());
        StubCoreHttpServer.RecordedCall env = core.call(2);
        assertEquals("GET", env.method());
        assertEquals("/internal/projects/envs/77", env.path());
        assertTrue(receipt.contains("\"dry_run\":true"), receipt);
        assertTrue(receipt.contains("\"case_count\":3"), receipt);
        assertTrue(receipt.contains("\"env_id\":77"), receipt);
        assertFalse(receipt.contains("batch_id"), "dry run answers without a batch handle: " + receipt);
    }

    @Test
    void execRunRefusesEnvironmentOutsideTheProjectWithZeroBatchHop() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok("{\"id\":77,\"projectId\":12,\"name\":\"他项目环境\"}"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("case_ids", List.of(101)),
                        "env_id", 77,
                        "dry_run", true), McpCaller.unrestricted(OPERATOR)));
        assertTrue(refusal.getMessage().contains("77"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("11"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("12"), refusal.getMessage());
        assertEquals(2, core.callCount(),
                "the mismatch is caught before any batch is written: " + core.calls());
    }

    @Test
    void execRunRelaysCoreStartFailureInHumanWords() {
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DRAFT));
        core.enqueue(StubCoreHttpServer.fail("40901", "环境不可用：未探到可达的执行器"));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("case_ids", List.of(101)),
                        "env_id", 77), McpCaller.unrestricted(OPERATOR)));
        assertTrue(failure.getMessage().contains("执行器"), failure.getMessage());
    }

    @Test
    void execRunRefusesACaseListWithoutAResolvedEnvironmentBeforeWritingAnything() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate

        // The core start door refuses an environment-less batch, so an agent run
        // that cannot name an environment must be refused before the create hop:
        // otherwise every replay of a burned idempotency key leaves one more draft
        // batch row that can never start.
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("case_ids", List.of(101, 102))), McpCaller.unrestricted(OPERATOR)));

        assertTrue(refusal.getMessage().contains("执行环境"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("env_id"),
                "the refusal must name the parameter to fill in: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("未创建批次"),
                "the refusal must state that nothing was written: " + refusal.getMessage());
        assertEquals(1, core.callCount(),
                "an unresolvable environment costs the create hop: " + core.calls());
    }

    @Test
    void execRunInheritsTheEnvironmentCarriedByTheSuite() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":66,\"projectId\":11,\"name\":\"登录回归集\",\"caseIds\":[201,202],"
                        + "\"envIds\":[77],\"status\":1}"));
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DRAFT));
        core.enqueue(StubCoreHttpServer.ok(START_RESULT_1042));

        // A regression suite already declares its own environment; an agent that
        // selects the suite inherits it instead of being refused for a missing one.
        String receipt = gateway.invoke("exec.run", Map.of(
                "project_id", 11,
                "selection", Map.of("suite_id", 66)), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall create = core.call(2);
        assertTrue(create.body().contains("\"envId\":77"),
                "the suite's own environment must reach the batch: " + create.body());
        assertTrue(receipt.contains("\"env_id\":77"), receipt);
        assertTrue(receipt.contains("用例集自带执行环境 77"),
                "the receipt names where the environment came from: " + receipt);
    }

    @Test
    void execRunRefusesAMultiEnvironmentSuiteInsteadOfPickingOneForTheCaller() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":66,\"projectId\":11,\"name\":\"多环境回归集\",\"caseIds\":[201],"
                        + "\"envIds\":[77,78],\"status\":1}"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.run", Map.of(
                        "project_id", 11,
                        "selection", Map.of("suite_id", 66)), McpCaller.unrestricted(OPERATOR)));

        assertTrue(refusal.getMessage().contains("77") && refusal.getMessage().contains("78"),
                "the refusal must name the candidate environments: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("env_id"), refusal.getMessage());
        assertEquals(2, core.callCount(),
                "an ambiguous environment is refused before any batch is written: " + core.calls());
    }

    @Test
    void execRunDryRunReportsAMissingEnvironmentInsteadOfAPass() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate

        // A preflight that answers "passed" for a run the start door would reject
        // is a false green: the honest answer is accepted=false plus the way out.
        String receipt = gateway.invoke("exec.run", Map.of(
                "project_id", 11,
                "selection", Map.of("case_ids", List.of(101, 102, 103)),
                "dry_run", true), McpCaller.unrestricted(OPERATOR));

        assertTrue(receipt.contains("\"dry_run\":true"), receipt);
        assertTrue(receipt.contains("\"accepted\":false"),
                "an environment-less run cannot preflight as accepted: " + receipt);
        assertTrue(receipt.contains("env_id"), receipt);
        assertEquals(1, core.callCount(),
                "preflight answers without any write hop: " + core.calls());
    }

    // ===== exec.status =====

    @Test
    void execStatusGatesOwningProjectResolvedFromTheBatchRow() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_QUEUED));
        core.enqueue(StubCoreHttpServer.ok("null"));

        String receipt = gateway.invoke("exec.status", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR));

        assertEquals(2, core.callCount(), core.calls().toString());
        StubCoreHttpServer.RecordedCall read = core.call(0);
        assertEquals("GET", read.method());
        assertEquals("/internal/batches/1042", read.path());
        StubCoreHttpServer.RecordedCall gate = core.call(1);
        assertEquals("/internal/projects/access-check", gate.path());
        assertTrue(gate.body().contains("\"projectId\":11"),
                "the owning project comes from the row, not from the caller: " + gate.body());
        assertEquals(String.valueOf(OPERATOR), gate.lastOperatorHeader());
        assertTrue(receipt.contains("\"status_label\":\"待执行\""), receipt);
        assertTrue(receipt.contains("\"case_count\":3"), receipt);
        assertTrue(receipt.contains("\"queue_pos\":1"), receipt);
        assertFalse(receipt.contains("envOngoingEpisode"),
                "status is a curated summary, not the row dump: " + receipt);
    }

    @Test
    void execStatusNeverRevealsAForeignProjectBatch() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DONE.replace("\"projectId\":11", "\"projectId\":12")));
        core.enqueue(StubCoreHttpServer.fail("40301", "非项目成员，无权访问该项目"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.status", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR)));
        assertTrue(refusal.getMessage().contains("项目成员"), refusal.getMessage());
        assertEquals(2, core.callCount());
    }

    // ===== exec.cancel =====

    @Test
    void execCancelsThroughTheOneCoreCancelDoor() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_QUEUED));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(
                BATCH_1042_QUEUED.replace("\"status\":2", "\"status\":8")
                        .replace("\"statusLabel\":\"待执行\"", "\"statusLabel\":\"已取消\"")
                        .replace("\"queuePos\":1", "\"queuePos\":null")
                        .replace("\"version\":1", "\"version\":2,\"message\":\"批次已取消（2 个未派发任务出队，已完成的执行结果保留）\"")));

        String receipt = gateway.invoke("exec.cancel", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), core.calls().toString());
        StubCoreHttpServer.RecordedCall cancel = core.call(2);
        assertEquals("POST", cancel.method());
        assertEquals("/internal/batches/1042/cancel", cancel.path());
        assertEquals(String.valueOf(OPERATOR), cancel.lastOperatorHeader());
        assertTrue(receipt.contains("\"cancelled\":true"), receipt);
        assertTrue(receipt.contains("\"state\":8"), receipt);
        assertTrue(receipt.contains("已取消"), receipt);
        assertTrue(receipt.contains("未派发任务出队"), "core wording rides through: " + receipt);
    }

    @Test
    void execCancelOnATerminalBatchIsAnIdempotentZeroChangeReceipt() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DONE));
        core.enqueue(StubCoreHttpServer.ok("null"));

        String receipt = gateway.invoke("exec.cancel", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR));

        assertEquals(2, core.callCount(),
                "a terminal batch must not reach the cancel door: " + core.calls());
        assertTrue(receipt.contains("\"cancelled\":false"), receipt);
        assertTrue(receipt.contains("\"state\":6"), receipt);
        assertTrue(receipt.contains("已完成"), receipt);
        assertTrue(receipt.contains("未产生任何变更"), receipt);
    }

    @Test
    void execCancelRelaysTheCoreStateMachineRefusalForADraftBatch() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DRAFT));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.fail("40901",
                "仅待执行/环境不可用/执行中/已暂停批次可取消（当前状态：草稿）"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> gateway.invoke("exec.cancel", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR)));
        assertTrue(refusal.getMessage().contains("草稿"), refusal.getMessage());
        assertEquals(3, core.callCount(),
                "non-terminal states are decided by the core state machine, not by a second one: "
                        + core.calls());
    }

    // ===== report.fetch =====

    @Test
    void reportFetchReadsCoreBadgeAndCloseoutSnapshotWithoutRecomputing() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_DONE));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"batchId\":1042,\"status\":6,\"statusLabel\":\"已完成\",\"passRate\":0.75}"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"batchId\":1042,\"batchStatus\":6,\"batchStatusLabel\":\"已完成\",\"total\":12,"
                        + "\"passCount\":9,\"failCount\":2,\"skipCount\":1,\"timeoutCount\":0,"
                        + "\"passRate\":0.75,\"failDist\":{\"1\":2},\"durationMs\":720000}"));

        String receipt = gateway.invoke("report.fetch", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR));

        assertEquals(4, core.callCount(), core.calls().toString());
        StubCoreHttpServer.RecordedCall badge = core.call(2);
        assertEquals("/internal/batches/1042/badge", badge.path(),
                "pass rate single source = core badge chain (red line: api never recomputes)");
        StubCoreHttpServer.RecordedCall snapshot = core.call(3);
        assertEquals("/internal/batches/1042/report", snapshot.path());

        assertTrue(receipt.contains("\"pass_rate\":0.75"), receipt);
        assertTrue(receipt.contains("通过率"), receipt);
        assertTrue(receipt.contains("\"total\":12"), receipt);
        assertTrue(receipt.contains("\"pass\":9"), receipt);
        assertTrue(receipt.contains("\"fail\":2"), receipt);
        assertTrue(receipt.contains("\"skip\":1"), receipt);
        assertTrue(receipt.contains("\"timeout\":0"), receipt);
        assertTrue(receipt.contains("\"fail_dist\""), receipt);
        assertTrue(receipt.contains("2026-11-18 03:12:00"), "finished_at belongs to the summary: " + receipt);
        assertFalse(receipt.contains("\"cases\""), "no per-case row dump in the receipt: " + receipt);
    }

    @Test
    void reportFetchBeforeCloseoutReportsProgressInsteadOfASnapshot() {
        core.enqueue(StubCoreHttpServer.ok(BATCH_1042_QUEUED));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"batchId\":1042,\"status\":2,\"statusLabel\":\"待执行\",\"passRate\":null}"));

        String receipt = gateway.invoke("report.fetch", Map.of("batch_id", 1042), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(),
                "an unfinished batch must not be sent to the snapshot door: " + core.calls());
        assertTrue(receipt.contains("\"status_label\":\"待执行\""), receipt);
        assertTrue(receipt.contains("\"total\":3"), receipt);
        assertTrue(receipt.contains("通过率"), "the summary sentence still has to be readable: " + receipt);
        assertTrue(receipt.contains("exec.status"), receipt);
    }

    @Test
    void reportFetchRelaysMissingBatchInHumanWords() {
        core.enqueue(StubCoreHttpServer.fail("40401", "批次不存在或已删除"));

        ServiceException failure = assertThrows(ServiceException.class,
                () -> gateway.invoke("report.fetch", Map.of("batch_id", 999999), McpCaller.unrestricted(OPERATOR)));
        assertTrue(failure.getMessage().contains("批次不存在"), failure.getMessage());
        assertEquals(1, core.callCount(), core.calls().toString());
    }
}
