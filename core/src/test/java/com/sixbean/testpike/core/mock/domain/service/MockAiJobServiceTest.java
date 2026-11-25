package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI mock-drafting job control plane : the gate matrix
 * and the preview-confirm ledger around tp_mock_ai_job.
 * <p>Contract under test: the master switch {@code ai.mock.enabled} is ANDed
 * with one per-application-point sub-switch; any closed gate refuses in plain
 * words (naming the exact key the operator must open), leaves ZERO job rows
 * and still writes an audit trail — a silent no-op is forbidden. The M10
 * job types (llm-fallback / record-summarize, unlocked by V241) ride the same
 * gate matrix: a closed sub-switch refuses naming its key, both gates open
 * queue the job exactly like the M9 points — the switch, never a code ban,
 * decides what the platform accepts.
 * <p>Accepted creations pin the cost account at creation time
 * (budget_snapshot JSONB — consumers read the pin, never the live keys) and
 * land as 'queued' waiting for the MQ dispatch that 's controller
 * fires from the publish point (service never publishes — same posture as
 * every core create service).
 * <p>confirm is the ONLY ingress for AI drafts into the rule source and only
 * from status 'preview': each ticked MockStubInput lands via MockStubService
 * (save-chain validation single point) with source=nl-ai + sourceRef=job:{id},
 * then the job seals as 'consumed' — a second confirm is refused. ignore
 * cancels live states (queued/running/preview) but never a terminal one.
 * <p>Real dev database, zero mocks (MockRecordingServiceTest posture); gates
 * are hot config rewritten through ConfigCacheHolder with the seeded value
 * restored in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockAiJobServiceTest {

    private static final String MASTER_KEY = "ai.mock.enabled";
    private static final String NL_STUB_KEY = "ai.mock.nl-stub";
    private static final String EDGE_KEY = "ai.mock.edge-scenarios";
    private static final String SPEC_SYNC_KEY = "ai.mock.spec-sync";
    private static final String LLM_FALLBACK_KEY = "ai.mock.llm-fallback";
    private static final String RECORD_SUMMARIZE_KEY = "ai.mock.record-summarize";
    private static final String SPEC_ENRICH_KEY = "ai.mock.spec-enrich";
    private static final String BULK_LIMIT_KEY = "mock.spec-import.bulk-endpoint-limit";
    private static final String ALLOWLIST_KEY = "mock.proxy.allowlist";

    @Autowired
    private MockAiJobService aiJobService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockRecordingService recordingService;

    @Autowired
    private MockRecordingFrameStore frameStore;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t365p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace() {
        String key = "t365-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(),
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
    }

    /** Published space (a cassette box may only open on served traffic). */
    private long newPublishedSpace() {
        long spaceId = newSpace();
        spaceService.transition(spaceId, 1, 0);
        return spaceId;
    }

    /** Open box on a published space inside the host allowlist . */
    private long openBox(long spaceId) {
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            return recordingService.start(spaceId, "http://srv.stub.test/orders");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT space_key FROM tp_mock_space WHERE id = ?", String.class, spaceId);
    }

    private void openGates(String... subKeys) {
        configCacheHolder.put(MASTER_KEY, "1");
        for (String k : subKeys) {
            configCacheHolder.put(k, "1");
        }
    }

    private void closeAllGates() {
        for (String k : new String[]{MASTER_KEY, NL_STUB_KEY, EDGE_KEY, SPEC_SYNC_KEY,
                LLM_FALLBACK_KEY, RECORD_SUMMARIZE_KEY, SPEC_ENRICH_KEY}) {
            configCacheHolder.remove(k); // seeded value is '0' = closed
        }
    }

    private int jobCount(long spaceId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_ai_job WHERE space_id = ?", Integer.class, spaceId);
        return n == null ? 0 : n;
    }

    private int gateRefusalAuditCount(long spaceId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'mock_ai_job' "
                        + "AND action = 'mock_ai_job_gate_refused' AND object_id = ?",
                Integer.class, String.valueOf(spaceId));
        return n == null ? 0 : n;
    }

    private String statusOf(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mock_ai_job WHERE id = ?", String.class, jobId);
    }

    private MockStubInput stubInput(String name) {
        return new MockStubInput(name, 10, "GET", "exact", "/orders/" + name,
                null, "{\"status\":200,\"body\":\"{}\"}",
                null, null, null, "manual", null, true);
    }

    /** Seed a job in an arbitrary status straight onto the real table
 * has no ai-service consumer yet, so 'preview' fixtures are staged by hand). */
    private long seedJob(long spaceId, String jobType, String status, String prompt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_mock_ai_job (space_id, job_type, input_json, status, budget_snapshot) "
                        + "VALUES (?, ?, ?::jsonb, ?, '{\"pinned\":true}'::jsonb) RETURNING id",
                Long.class, spaceId, jobType,
                "{\"prompt\":\"" + prompt + "\"}", status);
    }

    /** Seed a parsed contract version row with N endpoints in the space's
     *  project — the input a spec-enrich task reads. Rows are named mjw-* so
     *  the sweep stays scoped to fixtures this test created. */
    private long seedSpec(long spaceId, int endpointCount) {
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_mock_space WHERE id = ?", Long.class, spaceId);
        StringBuilder endpoints = new StringBuilder("[");
        for (int i = 0; i < endpointCount; i++) {
            if (i > 0) {
                endpoints.append(',');
            }
            String path = "/mjw/enrich/e" + i;
            endpoints.append("{\"method\":\"GET\",\"path\":\"").append(path)
                    .append("\",\"endpointKey\":\"GET ").append(path).append("\"}");
        }
        endpoints.append(']');
        String name = "mjw-enrich-" + UUID.randomUUID().toString().substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_api_spec (project_id, name, spec_format, spec_title,"
                        + " spec_text, content_sha256, spec_version, endpoints)"
                        + " VALUES (?, ?, 'openapi3-json', ?, '{}', ?, 1, ?::jsonb)"
                        + " RETURNING id",
                Long.class, projectId, name, name,
                UUID.randomUUID().toString().replace("-", ""), endpoints.toString());
    }

    // ---- gate matrix ----

    @Test
    void specEnrichRidesTheSameGateMatrixAndPinsItsSpecRow() {
        long spaceId = newSpace();
        long specId = seedSpec(spaceId, 2);
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SPEC_ENRICH_KEY, "0"); // explicit close wins over seeded '1'
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(
                            spaceId, "spec-enrich", "完善目录接口返回体", null, specId)));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(SPEC_ENRICH_KEY),
                    "closed sub-switch refusal must name the key to open, got: " + e.getMessage());
            assertEquals(0, jobCount(spaceId), "a refused job must never reach the table");

            configCacheHolder.put(SPEC_ENRICH_KEY, "1");
            long jobId = aiJobService.createJob(new MockAiJobCreateInput(
                    spaceId, "spec-enrich", "完善目录接口返回体", null, specId)).id();
            assertEquals("queued", statusOf(jobId));
            String storedInput = jdbcTemplate.queryForObject(
                    "SELECT input_json::text FROM tp_mock_ai_job WHERE id = ?",
                    String.class, jobId);
            assertTrue(storedInput.matches(".*\"specId\":\\s*" + specId + "\\b.*"),
                    "the stored input must echo the contract row the run will read, got: "
                            + storedInput);
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_api_spec WHERE id = ?", specId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void specEnrichWithoutAContractRowIsRefusedWithGuidance() {
        long spaceId = newSpace();
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SPEC_ENRICH_KEY, "1");
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(
                            spaceId, "spec-enrich", "补全场景响应", null, null)));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertTrue(e.getMessage().contains("契约版本"),
                    "refusal must name the missing input in plain words, got: " + e.getMessage());
            assertEquals(0, jobCount(spaceId));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void specEnrichOverTheEndpointCapNamesCountLimitAndBatchingPath() {
        long spaceId = newSpace();
        long specId = seedSpec(spaceId, 3);
        try {
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(SPEC_ENRICH_KEY, "1");
            configCacheHolder.put(BULK_LIMIT_KEY, "2");
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(
                            spaceId, "spec-enrich", "批量完善", null, specId)));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            String msg = e.getMessage();
            assertTrue(msg.contains("3") && msg.contains("2"),
                    "refusal must name the real endpoint count and the cap, got: " + msg);
            assertTrue(msg.contains("分批"),
                    "refusal must point at the batching path, got: " + msg);
            assertEquals(0, jobCount(spaceId), "over-cap refusal never queues");
        } finally {
            configCacheHolder.remove(BULK_LIMIT_KEY);
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_api_spec WHERE id = ?", specId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void masterGateClosedRefusesInPlainWordsWithAuditAndZeroRows() {
        long spaceId = newSpace();
        closeAllGates();
        configCacheHolder.put(NL_STUB_KEY, "1"); // sub open does NOT override the master
        try {
            int auditsBefore = gateRefusalAuditCount(spaceId);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub", "订单超时返回5001")));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(MASTER_KEY),
                    "refusal must name the key to open, got: " + e.getMessage());
            assertEquals(0, jobCount(spaceId), "a refused job must never reach the table");
            assertEquals(auditsBefore + 1, gateRefusalAuditCount(spaceId),
                    "every gate refusal must leave one audit row");
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void subSwitchClosedRefusesEvenWithMasterOpenAndNamesItsOwnKey() {
        long spaceId = newSpace();
        configCacheHolder.put(MASTER_KEY, "1");
        configCacheHolder.remove(EDGE_KEY);
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "edge-scenarios", "支付接口故障剧本")));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(EDGE_KEY),
                    "refusal must name the sub-switch, got: " + e.getMessage());
            assertEquals(0, jobCount(spaceId));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void m10JobTypesFollowTheirSubSwitches() {
        long spaceId = newSpace();
        // Cassette legs open boxes on published spaces; declared
        // here so the finally sweep can clean jobs, boxes and spaces alike.
        long pSpace = newPublishedSpace();
        long otherSpace = newPublishedSpace();
        try {
            // segment 1 (M10): llm-fallback / record-summarize moved from a hard
            // refusal to switch-gated acceptance — the gates decide, never a
            // code-level ghost ban. Closed sub-switch = FORBIDDEN naming the key.
            openGates(MASTER_KEY, LLM_FALLBACK_KEY);
            configCacheHolder.remove(RECORD_SUMMARIZE_KEY);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "record-summarize", "归纳录制流量")));
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
            assertTrue(e.getMessage().contains(RECORD_SUMMARIZE_KEY),
                    "refusal must name the sub-switch, got: " + e.getMessage());
            assertEquals(0, jobCount(spaceId));

            // Both switches open: llm-fallback lands 'queued' with a pinned
            // budget, exactly like the M9 types. record-summarize now carries
            // a second contract : it must bind a sealed cassette, so
            // a binding-less creation is refused AFTER the gates, not queued.
            openGates(MASTER_KEY, LLM_FALLBACK_KEY, RECORD_SUMMARIZE_KEY, NL_STUB_KEY);
            MockAiJobInfo job = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "llm-fallback", "x"));
            assertEquals("queued", job.status(), "llm-fallback must queue once both gates stand open");
            assertNotNull(job.budgetSnapshotJson(), "cost account pins at creation for M10 too");
            ServiceException unbound = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(spaceId, "record-summarize", "归纳录制流量")));
            assertEquals(BizErrorCode.VALIDATION_ERROR, unbound.getCode());
            assertEquals(1, jobCount(spaceId), "a refused induction binds no job row either");

            // segment 4a spectra: the induction binds a SEALED box of the SAME
            // space carrying a cassette — an open box, an empty box, a foreign
            // box, or a stray binding on a drafting point is refused in plain
            // words; only the honest binding queues. Boxes only open on
            // published spaces , so these legs run against one.
            long openBox = openBox(pSpace);
            ServiceException open = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(pSpace, "record-summarize", "归纳", openBox)));
            assertTrue(open.getMessage().contains("封"),
                    "refusal must explain the box is still open: " + open.getMessage());

            long sealedEmpty = recordingService.stop(openBox).id();
            ServiceException empty = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(pSpace, "record-summarize", "归纳", sealedEmpty)));
            assertTrue(empty.getMessage().contains("没有录制到"),
                    "an empty cassette must be refused, not summarized into zero-sample lies: " + empty.getMessage());
            assertEquals(0, jobCount(pSpace));

            long foreignBox = openBox(otherSpace);
            long foreign = recordingService.stop(foreignBox).id();
            ServiceException mix = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(pSpace, "record-summarize", "归纳", foreign)));
            assertTrue(mix.getMessage().contains("不属于"),
                    "the cassette box must belong to the job's space: " + mix.getMessage());

            ServiceException ghost = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(pSpace, "record-summarize", "归纳", 999_999_999L)));
            assertTrue(ghost.getMessage().contains("不存在"), "ghost box refused in plain words");

            ServiceException stray = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(
                            new MockAiJobCreateInput(pSpace, "nl-stub", "x", sealedEmpty)));
            assertTrue(stray.getMessage().contains("record-summarize"),
                    "a cassette binding on a drafting point is a malformed request: " + stray.getMessage());
            assertEquals(0, jobCount(pSpace), "every refusal above persists zero rows");

            // Positive leg: a sealed box that actually captured frames
            // (hold staged before the seal → cassette_ref stamped at stop).
            long liveBox = openBox(pSpace);
            frameStore.append(liveBox, List.of(new MockRecordingFrameMessage(
                    "f-4a", "GET", "/orders/1", Map.of(), "{}",
                    200, Map.of(), "{}", 12, 1_700_000_000_000L,
                    spaceKeyOf(pSpace), liveBox)));
            long sealedWithFrames = recordingService.stop(liveBox).id();
            MockAiJobInfo induction = aiJobService.createJob(
                    new MockAiJobCreateInput(pSpace, "record-summarize", "归纳录制流量", sealedWithFrames));
            assertEquals("queued", induction.status());
            assertTrue(induction.inputJson().contains(String.valueOf(sealedWithFrames)),
                    "the bound box travels in input_json for the consumer, got: " + induction.inputJson());
            assertEquals(1, jobCount(pSpace));

            // Master closed still wins over any open sub-switch.
            configCacheHolder.remove(MASTER_KEY);
            ServiceException closed = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "llm-fallback", "x")));
            assertEquals(BizErrorCode.FORBIDDEN, closed.getCode());
            assertTrue(closed.getMessage().contains(MASTER_KEY));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id IN (?, ?, ?)",
                    spaceId, pSpace, otherSpace);
            // Cassette boxes reference their space (FK): boxes before spaces.
            jdbcTemplate.update("DELETE FROM tp_mock_recording WHERE space_id IN (?, ?, ?)",
                    spaceId, pSpace, otherSpace);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id IN (?, ?, ?)",
                    spaceId, pSpace, otherSpace);
        }
    }

    @Test
    void unknownJobTypeStaysRefusedEvenWithEveryGateOpen() {
        long spaceId = newSpace();
        openGates(MASTER_KEY, NL_STUB_KEY, EDGE_KEY, SPEC_SYNC_KEY, LLM_FALLBACK_KEY, RECORD_SUMMARIZE_KEY);
        try {
            // Unlocking M10 must not widen the accepted spectrum beyond the
            // CHECK domain — a made-up type still gets the plain-word refusal.
            assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "no-such-type", "x")));
            assertEquals(0, jobCount(spaceId));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void unknownJobTypeAndGhostSpaceAndBlankPromptAreRefused() {
        long spaceId = newSpace();
        openGates(MASTER_KEY, NL_STUB_KEY);
        try {
            assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "no-such-type", "x")));
            assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(999_999_999L, "nl-stub", "x")));
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub", "   ")));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertEquals(0, jobCount(spaceId));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    // ---- accepted creation ----

    @Test
    void acceptedJobLandsQueuedWithPinnedBudgetSnapshotAndEchoedInput() {
        long spaceId = newSpace();
        openGates(MASTER_KEY, NL_STUB_KEY, SPEC_SYNC_KEY);
        try {
            long jobId = aiJobService.createJob(
                    new MockAiJobCreateInput(spaceId, "nl-stub", "订单接口超时返回5001，查询默认三单已发货")).id();
            assertEquals("queued", statusOf(jobId));
            MockAiJobInfo job = aiJobService.get(jobId);
            assertEquals(spaceId, job.spaceId());
            assertEquals("nl-stub", job.jobType());
            assertNotNull(job.budgetSnapshotJson(),
                    "the cost account must be pinned at creation, not recomputed later");
            String pinned = jdbcTemplate.queryForObject(
                    "SELECT budget_snapshot::text FROM tp_mock_ai_job WHERE id = ?", String.class, jobId);
            assertTrue(pinned.contains("estimatedTotalTokens"),
                    "pin must carry the estimate body, got: " + pinned);
            assertTrue(job.inputJson().contains("订单接口超时返回5001"),
                    "the drafting prompt must ride in input_json for the consumer");
            assertEquals(1, jobCount(spaceId));
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    // ---- pure estimate ----

    @Test
    void estimateIsPureMathematicsAndScalesWithPromptLength() {
        long spaceId = newSpace();
        openGates(MASTER_KEY, NL_STUB_KEY, EDGE_KEY);
        try {
            long shortId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub", "短提示")).id();
            MockAiJobEstimateView shortEst = aiJobService.estimatePreview(shortId);
            assertNotNull(shortEst);
            assertTrue(shortEst.estimatedTotalTokens() > 0);

            String longPrompt = "这是一个明显更长的自然语言草稿描述，".repeat(20);
            long longId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "nl-stub", longPrompt)).id();
            MockAiJobEstimateView longEst = aiJobService.estimatePreview(longId);
            assertTrue(longEst.estimatedInputTokens() > shortEst.estimatedInputTokens(),
                    "input estimate must grow with the prompt");
            assertEquals(shortEst.outputQuotaTokens(), longEst.outputQuotaTokens(),
                    "per-point output quota is a constant, never prompt-scaled");

            // edge-scenarios point carries its own (larger) quota constant.
            long edgeId = aiJobService.createJob(new MockAiJobCreateInput(spaceId, "edge-scenarios", longPrompt)).id();
            MockAiJobEstimateView edgeEst = aiJobService.estimatePreview(edgeId);
            assertTrue(edgeEst.outputQuotaTokens() > shortEst.outputQuotaTokens(),
                    "each application point declares its own output quota");

            // Pure = zero extra rows written by estimating.
            assertEquals(3, jobCount(spaceId), "estimating must not persist anything");
        } finally {
            closeAllGates();
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    // ---- confirm / ignore transitions ----

    @Test
    void confirmOnlyFromPreviewLandsTickedStubsAsNlAiAndSealsConsumed() {
        long spaceId = newSpace();
        try {
            long jobId = seedJob(spaceId, "nl-stub", "preview", "p");
            List<MockStubInfo> landed = aiJobService.confirm(jobId,
                    List.of(stubInput("c-a"), stubInput("c-b")));
            assertEquals(2, landed.size());
            for (MockStubInfo s : landed) {
                assertEquals("nl-ai", s.source());
                assertEquals("job:" + jobId, s.sourceRef());
            }
            assertEquals("consumed", statusOf(jobId));
            // The box is spent — a second confirm must not re-land the drafts.
            assertThrows(ServiceException.class,
                    () -> aiJobService.confirm(jobId, List.of(stubInput("c-c"))));
            assertEquals(2, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tp_mock_stub WHERE space_id = ?", Integer.class, spaceId));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_stub WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void confirmOnQueuedOrWithEmptySelectionIsRefused() {
        long spaceId = newSpace();
        try {
            long queued = seedJob(spaceId, "nl-stub", "queued", "p");
            assertThrows(ServiceException.class, () -> aiJobService.confirm(queued, List.of(stubInput("q"))));
            assertEquals("queued", statusOf(queued), "a refused confirm must not move the job");

            long preview = seedJob(spaceId, "nl-stub", "preview", "p");
            assertThrows(ServiceException.class, () -> aiJobService.confirm(preview, List.of()));
            assertEquals("preview", statusOf(preview));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void ignoreCancelsLiveStatesAndRefusesTerminalOnes() {
        long spaceId = newSpace();
        try {
            for (String live : List.of("queued", "running", "preview")) {
                long id = seedJob(spaceId, "nl-stub", live, "p");
                assertEquals("cancelled", aiJobService.ignore(id).status());
            }
            for (String terminal : List.of("consumed", "failed", "cancelled")) {
                long id = seedJob(spaceId, "nl-stub", terminal, "p");
                assertThrows(ServiceException.class, () -> aiJobService.ignore(id),
                        "terminal state " + terminal + " must be sealed");
            }
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    // ---- consumer status write-back spectrum ----

    @Test
    void markRunningIsAllowedOnlyFromQueued() {
        long spaceId = newSpace();
        try {
            long queued = seedJob(spaceId, "nl-stub", "queued", "p");
            assertEquals("running", aiJobService.markRunning(queued).status());

            // running again, preview, and every terminal state refuse a (re)start.
            assertThrows(ServiceException.class, () -> aiJobService.markRunning(queued));
            long preview = seedJob(spaceId, "nl-stub", "preview", "p");
            assertThrows(ServiceException.class, () -> aiJobService.markRunning(preview));
            for (String terminal : List.of("consumed", "failed", "cancelled")) {
                long id = seedJob(spaceId, "nl-stub", terminal, "p");
                assertThrows(ServiceException.class, () -> aiJobService.markRunning(id),
                        "terminal state " + terminal + " must refuse a status write-back");
            }
            assertEquals("running", statusOf(queued), "refusals must not move other rows");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void queuedJobCannotJumpStraightToPreview() {
        long spaceId = newSpace();
        try {
            long queued = seedJob(spaceId, "nl-stub", "queued", "p");
            // The runner must claim (queued→running) before landing a preview —
            // a jump would let a duplicate delivery silently re-preview a job.
            ServiceException e = assertThrows(ServiceException.class,
                    () -> aiJobService.markPreview(queued, "[{\"name\":\"d1\"}]"));
            assertTrue(e.getMessage().contains("queued"),
                    "refusal must name the current status, got: " + e.getMessage());
            assertEquals("queued", statusOf(queued));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void markPreviewLandsRefFromRunningAndSealsThePreview() {
        long spaceId = newSpace();
        try {
            long running = seedJob(spaceId, "nl-stub", "running", "p");
            MockAiJobInfo previewed = aiJobService.markPreview(running, "[{\"name\":\"draft-1\"}]");
            assertEquals("preview", previewed.status());
            assertEquals("[{\"name\":\"draft-1\"}]", previewed.resultPreviewRef());

            // preview is a sealed hand-off to the human — a second write-back refuses.
            assertThrows(ServiceException.class,
                    () -> aiJobService.markPreview(running, "[{\"name\":\"draft-2\"}]"));
            assertThrows(ServiceException.class,
                    () -> aiJobService.markPreview(running, "  "));

            long over = seedJob(spaceId, "nl-stub", "running", "p");
            assertThrows(ServiceException.class,
                    () -> aiJobService.markPreview(over, "x".repeat(301)));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void markFailedFromRunningWritesPlainWordErrorMessage() {
        long spaceId = newSpace();
        try {
            long running = seedJob(spaceId, "nl-stub", "running", "p");
            MockAiJobInfo failed = aiJobService.markFailed(running, "模型供应商超时，草稿未产出");
            assertEquals("failed", failed.status());
            assertTrue(failed.errorMessage().contains("超时"));

            // failed is terminal; a queued row has no failure path this period.
            assertThrows(ServiceException.class, () -> aiJobService.markFailed(running, "again"));
            long queued = seedJob(spaceId, "nl-stub", "queued", "p");
            assertThrows(ServiceException.class, () -> aiJobService.markFailed(queued, "x"));

            String longMsg = "错".repeat(600);
            long noisy = seedJob(spaceId, "nl-stub", "running", "p");
            assertEquals(500, aiJobService.markFailed(noisy, longMsg).errorMessage().length(),
                    "error_message column is VARCHAR(500) — over-length must be clipped, not rejected");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }

    @Test
    void listIsNewestFirstAndGhostSpaceIsRefusedNotEmptied() {
        long spaceId = newSpace();
        try {
            long first = seedJob(spaceId, "nl-stub", "queued", "a");
            long second = seedJob(spaceId, "edge-scenarios", "preview", "b");
            List<MockAiJobInfo> jobs = aiJobService.listBySpace(spaceId);
            assertEquals(2, jobs.size());
            assertEquals(second, jobs.get(0).id(), "list must come newest job first");
            assertThrows(ServiceException.class, () -> aiJobService.listBySpace(999_999_999L));
            assertThrows(ServiceException.class, () -> aiJobService.get(999_999_999L));
            assertNotNull(aiJobService.get(first));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_mock_ai_job WHERE space_id = ?", spaceId);
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
    }
}
