package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.ExtractDedupKeys;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.document.domain.service.impl.DocumentExtractionServiceImpl;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.SysConfigSwitchFixture;
import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractionGapRow;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class DocumentExtractionServiceTest {

    @Autowired
    private DocumentExtractionService extractionService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RequirementService requirementService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private MessageConverter caseEventMessageConverter;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ConfigService configService;

    private String code;

    /** Projects created by this suite; swept by id tree, never by table wipe. */
    private final List<Long> fixtureProjects = new ArrayList<>();

    /** Same literal as the V466 factory seed - a fixture reset must never
     *  rewrite the shipped description into a second on-screen variant. */
    private static final String ANCHOR_SWITCH_DESCRIPTION =
            "证据锚齐全开关（默认开）。开启时，证据摘录回指不到原文块的需求点不会入库，"
                    + "拦截条数会在提取过程详情中点名；关闭后所有需求点照常入库。保存即生效，无需重启";

    /** recalibration: every preview fixture in this class
 * predates the anchor policy (7-arity anchorless items) and the tests
 * exercise confirm bookkeeping - dedup cards, idempotent replay, source
 * 2 landing, generation guards - not the anchor gate itself, which has
 * its own face in DocumentExtractionConfirmAnchorGateTest. The switch is
 * explicitly off here (the posture these fixtures predate) and restored
 * to factory on after each test. */
    @BeforeEach
    void anchorGateOffForLegacyFixtures() {
        SysConfigSwitchFixture.upsert(jdbcTemplate, configService,
                DocumentExtractionServiceImpl.KEY_ANCHOR_REQUIRED, "false",
                "系统", "string", ANCHOR_SWITCH_DESCRIPTION);
    }

    @AfterEach
    void anchorGateRestoreFactoryOn() {
        SysConfigSwitchFixture.upsert(jdbcTemplate, configService,
                DocumentExtractionServiceImpl.KEY_ANCHOR_REQUIRED, "true",
                "系统", "string", ANCHOR_SWITCH_DESCRIPTION);
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37 teardown: sweep only the projects this suite created. A blanket
        // TRUNCATE on tp_project CASCADE deletes every business row of the
        // shared dev database (documents, requirement points, cases), so a
        // fixture must never wipe a table it does not own.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjects);
        fixtureProjects.clear();
    }

    private long createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        long projectId = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
        return projectId;
    }

    
    private long uploadDoc(long projectId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String md = "# 订单中心 PRD（" + tag + "）\n\n## 下单流程\n\n"
                + "用户提交订单时库存不足应提示下单失败。\n\n## 支付流程\n\n"
                + "支付超时订单自动取消并释放库存。\n";
        DocumentInfo info = documentService.upload(1L, projectId,
                "PRD-" + tag + ".md", md.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, info.status(), "前置文档必须解析成功");
        return info.id();
    }

    private static List<ExtractionItem> fiveItems() {
        return List.of(
                new ExtractionItem(0, "库存不足下单失败提示", "提交订单返回库存不足人话", "下单流程", 0.9, false, null),
                new ExtractionItem(0, "重复提交防重", "双击提交仅生成一单", "下单流程", 0.8, false, null),
                new ExtractionItem(0, "支付超时自动取消", "超时后订单状态=已取消", "支付流程", 0.95, false, null),
                new ExtractionItem(0, "超时释放库存", "取消后库存回补", "支付流程", 0.7, false, null),
                new ExtractionItem(0, "订单详情金额一致", "明细合计=实付", "下单流程", 0.6, false, null));
    }

    
    private void simulateAiWriteSuccess(long docId) {
        extractionService.writeExtractionResult(docId, fiveItems(), currentExtractToken(docId));
    }

    
    private String currentExtractToken(long docId) {
        return jdbcTemplate.query(
                "SELECT extract_token FROM tp_document WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, docId);
    }

    private Long reqCount(long docId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE source=2 AND source_id=?",
                Long.class, "doc:" + docId);
    }

    // ==================== routing criterion: extraction queue respects use_for_extraction ====================

    /**
     * The extraction usage switch (use_for_extraction) is off on the document
     * row → extract() rejects with
     * the human message naming the switch, and the row never enters the
     * extracting status (no dispatch side effect).
     */
    @Test
    void extractRejectedWhenUsageSwitchOff() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        jdbcTemplate.update("UPDATE tp_document SET use_for_extraction=false WHERE id=?", docId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> extractionService.extract(1L, docId, "deepseek-v4-flash"));
        assertTrue(e.getMessage().contains("参与需求提取"), "人话点名开关: " + e.getMessage());

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_document WHERE id=?", Integer.class, docId);
        assertEquals(1, status, "拒后不留提取中态（状态零漂移）");
    }


    
    @Test
    void test_f2_writeExtractionSuccess_publishes_kb_ingest() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, "deepseek-v4-flash");

        String queueName = "test.f2-kb." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_KB_INGEST, null));
        try {
            simulateAiWriteSuccess(docId);
            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "提取成功必须发布知识库入库事件 ai.kb.ingest（T176a）");
            com.sixbean.testpike.common.mq.AiKbIngestMessage kb =
                    (com.sixbean.testpike.common.mq.AiKbIngestMessage)
                            caseEventMessageConverter.fromMessage(received);
            assertEquals(docId, kb.documentId());
            assertEquals(projectId, kb.projectId());
            assertEquals(1L, kb.operatorId(), "operatorId=tp_document.created_by（上传发起人）");
            assertNotNull(kb.occurredAt(), "occurredAt 时间戳（消费端幂等键组成）");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }


    @Test
    void test_f2_extract_sets_status3_and_publishes_mq() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);

        DocumentInfo after = extractionService.extract(1L, docId, "deepseek-v4-flash");
        assertEquals(3, after.status(), "提交提取后任务态=3 提取中（01 v2.11 扩展位）");

        String queueName = "test.f2-extract." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_DOC_EXTRACT, null));
        try {
            simulateAiWriteSuccess(docId);
            extractionService.extract(1L, docId, null);
            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "F2 提取任务必须经 ai.doc.extract 路由键入队");
            AiDocExtractMessage msg =
                    (AiDocExtractMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(docId, msg.documentId());
            assertEquals(projectId, msg.projectId());
            assertEquals(1L, msg.operatorId());
            assertNotNull(msg.extractToken(), "F2 提取消息必须携带 extract_token（轮次守卫）");
            assertEquals(currentExtractToken(docId), msg.extractToken(),
                    "消息 token 必须与 DB extract_token 同轮次");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    /**
 * (IT-XQ-04): the preview carries the extraction stage
 * ledger — no stage rows means an empty array and a null activity clock
 * (legacy consumers byte-compatible), landed rows replay in insert order
 * and move the last-activity stamp.
 */
    @Test
    void test_f2_preview_carries_stage_ledger_empty_when_absent_and_ordered_when_landed() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token = currentExtractToken(docId);

        ExtractionPreview empty = extractionService.extraction(docId, 1, 10);
        assertEquals(0, empty.stageLedger().size(), "无台账行=空数组不破坏旧消费");
        org.junit.jupiter.api.Assertions.assertNull(empty.lastActivityAt(),
                "无任何活动时间=诚实 null，禁伪造时刻");

        jdbcTemplate.update("INSERT INTO tp_doc_extract_stage (document_id, extract_token,"
                        + " stage_code, stage_label) VALUES (?,?,?,?)",
                docId, token, "start", "提取启动");
        jdbcTemplate.update("INSERT INTO tp_doc_extract_stage (document_id, extract_token,"
                        + " stage_code, stage_label) VALUES (?,?,?,?)",
                docId, token, "group-2", "封组 2/3");
        ExtractionPreview p = extractionService.extraction(docId, 1, 10);
        assertEquals(2, p.stageLedger().size(), "阶段行按落账顺序整表回读");
        assertEquals("提取启动", p.stageLedger().get(0).label());
        assertEquals("group-2", p.stageLedger().get(1).code());
        org.junit.jupiter.api.Assertions.assertNotNull(p.lastActivityAt(),
                "阶段落账必须推进最后活动时间");
    }

    /**
 * EE batch the preview echoes the fidelity ledger row of the
 * CURRENT generation (injected/source tokens, retention, anchor fill,
 * measured per-item coefficients). An absent row honestly answers null —
 * the preview never fabricates a retention number — and a row of a
 * rotated generation is never echoed (same token-key posture as the
 * stage/coverage ledgers, read-only echo, no second store).
 */
    @Test
    void test_f2_preview_echoes_fidelity_ledger_of_current_generation() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token = currentExtractToken(docId);

        ExtractionPreview absent = extractionService.extraction(docId, 1, 10);
        org.junit.jupiter.api.Assertions.assertNull(absent.fidelity(),
                "无忠实账行=预览诚实 null，禁伪造保留率");

        try {
            insertFidelity(docId, "stale-generation-token", 642, 2, 4924, 121375, 4.06,
                    460, 50, 0, 0.0, 4890, 4924, -0.69, 183, 783, 2, 1);
            ExtractionPreview stale = extractionService.extraction(docId, 1, 10);
            org.junit.jupiter.api.Assertions.assertNull(stale.fidelity(),
                    "旧轮次忠实账禁回显（按当前轮次 token 键读）");

            insertFidelity(docId, token, 8, 3, 4000, 5200, 76.92,
                    6, 5, 3, 60.0, 3990, 4000, 0.25, 220, 900, 0, 2);
            ExtractionPreview p = extractionService.extraction(docId, 1, 10);
            var f = p.fidelity();
            org.junit.jupiter.api.Assertions.assertNotNull(f,
                    "落账后预览必须回显本轮次忠实账");
            assertEquals(8, f.chunkCount());
            assertEquals(3, f.nodeCount());
            assertEquals(4000, f.injectedTokens());
            assertEquals(5200, f.sourceTokens());
            assertEquals(76.92, f.retentionPercent(), 1e-9);
            assertEquals(6, f.distinctChapters());
            assertEquals(5, f.itemCount());
            assertEquals(3, f.anchoredItemCount());
            assertEquals(60.0, f.anchorRatePercent(), 1e-9);
            assertEquals(3990, f.estimateTokens());
            assertEquals(4000, f.usagePromptTokens());
            assertEquals(0.25, f.estDriftPercent(), 1e-9);
            assertEquals(220, f.plainJsonTokensPerItem());
            assertEquals(900, f.billedTokensPerItem());
            assertEquals(0, f.summaryCalls());
            assertEquals(2, f.reconcileCalls());
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM tp_doc_extract_fidelity WHERE ref_id=?", docId);
        }
    }

    /** Land one fidelity row through the DB face (mirrors the ai-service door). */
    private void insertFidelity(long documentId, String extractToken, int chunkCount,
                                int nodeCount, int injectedTokens, int sourceTokens,
                                double retentionPercent, int distinctChapters,
                                int itemCount, int anchoredItemCount,
                                double anchorRatePercent, int estimateTokens,
                                int usagePromptTokens, double estDriftPercent,
                                int plainJsonTokensPerItem, int billedTokensPerItem,
                                int summaryCalls, int reconcileCalls) {
        jdbcTemplate.update("INSERT INTO tp_doc_extract_fidelity (ref_id,"
                        + " run_id, chunk_count, node_count, injected_tokens,"
                        + " source_tokens, retention_percent, distinct_chapters,"
                        + " item_count, anchored_item_count, anchor_rate_percent,"
                        + " estimate_tokens, usage_prompt_tokens, est_drift_percent,"
                        + " plain_json_tokens_per_item, billed_tokens_per_item,"
                        + " summary_calls, reconcile_calls) VALUES (?,?,?,?,?,?,?,?,?,?"
                        + ",?,?,?,?,?,?,?,?)",
                documentId, extractToken, chunkCount, nodeCount, injectedTokens,
                sourceTokens, retentionPercent, distinctChapters, itemCount,
                anchoredItemCount, anchorRatePercent, estimateTokens, usagePromptTokens,
                estDriftPercent, plainJsonTokensPerItem, billedTokensPerItem,
                summaryCalls, reconcileCalls);
    }

    @Test
    void test_f2_extraction_preview_paged_and_confirm_creates_source2() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);

        ExtractionPreview p1 = extractionService.extraction(docId, 1, 2);
        assertEquals(4, p1.status(), "回写后任务态=4 已提取");
        assertEquals(5L, p1.total());
        assertEquals(2, p1.items().size(), "page=1 size=2 → 2 条");
        ExtractionPreview p2 = extractionService.extraction(docId, 2, 2);
        assertEquals(2, p2.items().size(), "page=2 size=2 → 2 条");
        assertEquals(1, extractionService.extraction(docId, 3, 2).items().size(),
                "page=3 → 末页 1 条（5 条不重不漏）");

        List<ExtractionItem> selected = List.of(
                new ExtractionItem(p1.items().get(0).id(), null, null, null, null, true, null),
                new ExtractionItem(p2.items().get(0).id(), null, null, null, null, true, null));
        ConfirmExtractionResult result =
                extractionService.confirmExtraction(1L, docId, selected);
        assertEquals(2, result.createdCount(), "勾选 2 条 → 建 2 需求点");
        assertEquals(0, result.skipped());
        assertEquals(2L, reqCount(docId), "DB 实钉 source=2 + source_id=doc:{id} 两条");

        ExtractionPreview after = extractionService.extraction(docId, 1, 10);
        long persistedSelected = after.items().stream()
                .filter(i -> i.selected() && i.requirementId() != null).count();
        assertEquals(2, persistedSelected, "勾选+requirement_id 必须回写预览持久化");

        // Chip confirm is a human adoption of each extracted requirement point
        // : every landed row carries exactly one
        // chip-channel verdict in the behavior outcome ledger.
        List<Long> landedIds = after.items().stream()
                .filter(i -> i.selected() && i.requirementId() != null)
                .map(ExtractionItem::requirementId).toList();
        assertEquals(2, landedIds.size());
        for (Long reqId : landedIds) {
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_behavior_outcome"
                            + " WHERE source = 'chip' AND source_event_id = ?",
                    Integer.class, "chip-confirm:" + reqId),
                    "chip confirm of one extracted point counts once in the ledger");
        }
        landedIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM tp_ai_behavior_outcome WHERE source = 'chip' AND source_event_id = ?",
                "chip-confirm:" + id));
    }

    /**
 * backend contract: the confirm answer carries the ids of the
 * requirement points it just created, so the frontend can pre-fill the
 * next "generate cases for these points" step with a structured id list
 * instead of re-deriving them from the paged extraction preview
 * (cross-page collection is unreliable at size=20).
 */
    @Test
    void test_f2_confirm_returns_created_requirement_ids_for_structured_prefill() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);

        ExtractionPreview preview = extractionService.extraction(docId, 1, 10);
        List<ExtractionItem> selected = List.of(
                new ExtractionItem(preview.items().get(0).id(), null, null, null, null, true, null),
                new ExtractionItem(preview.items().get(2).id(), null, null, null, null, true, null));
        ConfirmExtractionResult result =
                extractionService.confirmExtraction(1L, docId, selected);

        assertEquals(2, result.createdCount());
        List<Long> createdIds = result.createdRequirementIds();
        assertEquals(result.createdCount(), createdIds.size(),
                "新增需求点 ID 清单条数必须等于 createdCount（弹 CTA 与预填的数据锚）");
        assertEquals(2L, new HashSet<>(createdIds).size(), "同批不重复回传");
        for (Long id : createdIds) {
            assertEquals(2, jdbcTemplate.queryForObject(
                    "SELECT source FROM tp_requirement WHERE id = ?", Integer.class, id),
                    "回传的每个 ID 必须真实落库（source=2 挂本档）");
        }

        // The returned set is exactly the set the preview persists on the
        // landed rows - the frontend can trust either channel, they agree.
        Set<Long> persisted = extractionService.extraction(docId, 1, 10).items().stream()
                .filter(i -> i.selected() && i.requirementId() != null)
                .map(ExtractionItem::requirementId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(persisted, new HashSet<>(createdIds),
                "返回 ID 集=预览 requirement_id 回写集（单源一致，禁第二套定位口径）");

        // Idempotent replay creates nothing new: empty id list, never stale ids.
        ConfirmExtractionResult replay =
                extractionService.confirmExtraction(1L, docId, selected);
        assertEquals(0, replay.createdCount());
        assertTrue(replay.createdRequirementIds().isEmpty(),
                "重放零新建时 ID 清单必须为空（禁把上批 ID 冒充本批）");

        createdIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM tp_ai_behavior_outcome WHERE source = 'chip' AND source_event_id = ?",
                "chip-confirm:" + id));
    }

    /**
 * sub-item ①+③: the preview answer carries the review-flag
 * threshold (single source = tp_sys_config, never hard-coded in the client)
 * and honestly surfaces the pending steps of this generation's coverage
 * ledger, so the reviewer sees "which source chunks produced nothing"
 * instead of guessing from a full-looking list.
 */
    @Test
    void test_f2_preview_echoes_review_threshold_and_pending_coverage_gaps() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        String token = currentExtractToken(docId);
        try {
            // No ledger row for this generation = nothing tracked, zero gap
            // (pure summary path must not fabricate gaps in the UI).
            ExtractionPreview clean = extractionService.extraction(docId, 1, 10);
            assertEquals(0.7, clean.reviewFlagThreshold(), 1e-9,
                    "预览必须回显热更核对阈值（活键/缺省均 0.7，禁前端硬编码）");
            assertEquals(0, clean.uncoveredChunkCount());
            assertTrue(clean.uncoveredChunkIds().isEmpty());

            jdbcTemplate.update(
                    "INSERT INTO tp_doc_extract_ledger (document_id, extract_token, steps_json)"
                            + " VALUES (?,?,?)",
                    docId, token,
                    "[{\"title\":\"块#12\",\"state\":\"pending\",\"evidence\":\"\","
                            + "\"done\":false},{\"title\":\"块#34\",\"state\":\"pending\",\"evidence\":\"\"},"
                            + "{\"title\":\"块#7\",\"state\":\"done\",\"evidence\":\"1 条需求点引用：登录\"}]");
            ExtractionPreview gapped = extractionService.extraction(docId, 1, 10);
            assertEquals(2, gapped.uncoveredChunkCount(),
                    "pending 步骤数=残缺口数（done 不计，缺省 fail-soft）");
            assertEquals(List.of(12L, 34L), gapped.uncoveredChunkIds(),
                    "残缺口块行 id 经标题锚解析升序透出（回跳原文复用 AP-4 件）");

            // Generation isolation: a ledger keyed by a stale token never leaks
            // into the current preview (same posture as the write-back guard).
            ExtractionPreview afterReplay = extractionService.extraction(docId, 1, 10);
            assertEquals(2, afterReplay.uncoveredChunkCount(), "同代账本重读稳定");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_doc_extract_ledger WHERE document_id=?", docId);
        }
    }

    /**
 * segment A: every pending coverage step must answer "why did this
 * chunk produce nothing" — the ledger's own note/evidence now travel to
 * the preview as per-chunk reason rows, enriched with seq/chapter/
 * contentChars from tp_document_chunk (single source, batched by id).
 * A chunk id whose row no longer exists keeps the note and answers
 * null/0 honestly instead of fabricating a label.
 */
    @Test
    void test_f2_preview_carries_per_chunk_gap_reason_rows() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        String token = currentExtractToken(docId);
        Long realChunk = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_document_chunk WHERE document_id=? ORDER BY seq LIMIT 1",
                Long.class, docId);
        try {
            jdbcTemplate.update(
                    "INSERT INTO tp_doc_extract_ledger (document_id, extract_token, steps_json)"
                            + " VALUES (?,?,?)",
                    docId, token,
                    "[{\"title\":\"块#" + realChunk + "\",\"state\":\"pending\","
                            + "\"note\":\"残账章节：登录 / 未提取任何条目\",\"evidence\":\"\"},"
                            + "{\"title\":\"块#999999999\",\"state\":\"pending\","
                            + "\"note\":\"残账章节：支付 / 未提取任何条目\",\"evidence\":\"缺组证据\"},"
                            + "{\"title\":\"块#8\",\"state\":\"done\",\"evidence\":\"1 条需求点引用：登录\"}]");
            ExtractionPreview gapped = extractionService.extraction(docId, 1, 10);
            assertEquals(2, gapped.uncoveredChunkCount(),
                    "pending 步数不随原因通道改造漂移");
            assertEquals(2, gapped.uncoveredChunks().size(),
                    "原因行数=pending 行数（done 步不入账）");
            ExtractionGapRow real = gapped.uncoveredChunks().stream()
                    .filter(r -> r.chunkId() == realChunk).findFirst().orElseThrow();
            assertTrue(real.note().contains("未提取任何条目"),
                    "账本 note 必须原样透出（残账横幅「因啥失败」的数据通道）");
            assertNotNull(real.seq(), "在册块行必须带 tp_document_chunk 的 seq 元数据");
            assertTrue(real.contentChars() > 0,
                    "在册块行 contentChars 来自源块表实量（禁硬编码 0）");
            ExtractionGapRow ghost = gapped.uncoveredChunks().stream()
                    .filter(r -> r.chunkId() == 999999999L).findFirst().orElseThrow();
            assertEquals("残账章节：支付 / 未提取任何条目", ghost.note(),
                    "块行已失踪仍保留账本自身原因");
            assertNull(ghost.seq(), "失踪块行元数据诚实为空，禁伪造章节名");
            assertEquals(0, ghost.contentChars(), "失踪块行源尺寸如实为 0");
            assertEquals("缺组证据", ghost.evidence(), "evidence 原样透出");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_doc_extract_ledger WHERE document_id=?", docId);
        }
    }

    @Test
    void test_f2_confirm_idempotent_retry_no_duplicate() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        List<ExtractionItem> selected = List.of(
                new ExtractionItem(1, null, null, null, null, true, null),
                new ExtractionItem(2, null, null, null, null, true, null));
        extractionService.confirmExtraction(1L, docId, selected);

        ConfirmExtractionResult again =
                extractionService.confirmExtraction(1L, docId, selected);
        assertEquals(0, again.createdCount(), "重复 confirm 已入库条目不重建");
        assertEquals(2, again.skipped());
        assertEquals(2L, reqCount(docId), "需求点数不漂移");
    }

    @Test
    void test_f2_retry_extract_keeps_confirmed_items() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        extractionService.confirmExtraction(1L, docId, List.of(
                new ExtractionItem(3, null, null, null, null, true, null)));
        assertEquals(1L, reqCount(docId));

        extractionService.extract(1L, docId, null);
        assertEquals(3, extractionService.extraction(docId, 1, 10).status());
        simulateAiWriteSuccess(docId);
        ExtractionPreview merged = extractionService.extraction(docId, 1, 10);
        assertEquals(5L, merged.total(), "重试合并后仍 5 条（不重复生成）");
        ExtractionItem confirmed = merged.items().stream()
                .filter(i -> "支付超时自动取消".equals(i.title())).findFirst().orElseThrow();
        assertTrue(confirmed.selected(), "重试后勾选状态持久");
        assertNotNull(confirmed.requirementId(), "重试后 requirement_id 幂等锚点保留");
        assertEquals(1L, reqCount(docId), "重试不重复入库");
    }


    @Test
    void test_f2_extract_in_progress_409_human() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        // Fixture precondition ( design: a fresh submit is preempt-allowed
        // when NO previous-generation consumer heartbeat is warm). In production
        // the ai-service MQ consumer stamps that heartbeat; an L2 lane runs no
        // consumer, so stamp the key here through the single-source grammar
        // (real Redis, real probe code path — business chain stays unmocked)
        // and sweep it in finally.
        String heartbeatKey = ExtractDedupKeys.consumeHeartbeatKey(docId, currentExtractToken(docId));
        redisTemplate.opsForValue().set(heartbeatKey, "l2-fixture-consumer", Duration.ofSeconds(60));
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> extractionService.extract(1L, docId, null));
            assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
            assertTrue(ex.getMessage().contains("抢占"),
                    "并发两提取=后者 409 人话点名抢占后果(IT-DOC-03/T561)，实际=" + ex.getMessage());
            assertEquals(3, extractionService.extraction(docId, 1, 10).status(),
                    "被拒后任务态不变仍 3");
        } finally {
            redisTemplate.delete(heartbeatKey);
        }
    }

    @Test
    void test_f2_failure_marks_back_to_1_with_error_and_retry_allowed() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);

        extractionService.markExtractionFailed(docId, "LLM 供应商超时", currentExtractToken(docId));
        ExtractionPreview preview = extractionService.extraction(docId, 1, 10);
        assertEquals(1, preview.status(), "失败回退 status=1（可重试）");
        assertNotNull(preview.extractError());
        assertTrue(preview.extractError().contains("超时"), "失败人话暴露");

        DocumentInfo retry = extractionService.extract(1L, docId, null);
        assertEquals(3, retry.status(), "失败回退 1 后允许再次提交");
    }

    @Test
    void test_f2_parse_failed_doc_cannot_extract() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        jdbcTemplate.update("UPDATE tp_document SET status=2 WHERE id=?", docId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> extractionService.extract(1L, docId, null));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertTrue(ex.getMessage().contains("解析"),
                "未解析成功文档不可提取=人话点名，实际=" + ex.getMessage());
    }


    @Test
    void test_f2_delete_doc_unlinks_requirements_not_cascade() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        extractionService.confirmExtraction(1L, docId, List.of(
                new ExtractionItem(1, null, null, null, null, true, null)));
        assertEquals(1L, reqCount(docId));

        documentService.delete(1L, docId);

        Long kept = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id=?", Long.class, projectId);
        assertEquals(1L, kept, "02 §2.6：已入库需求点不级联删除");
        String sourceId = jdbcTemplate.queryForObject(
                "SELECT source_id FROM tp_requirement WHERE project_id=?",
                String.class, projectId);
        assertNull(sourceId, "已入库需求点 doc_id 置空（source_id 解链）");
    }


    
    @Test
    void test_f2_stale_generation_failed_writeback_rejected_on_new_generation() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token1 = currentExtractToken(docId);
        assertNotNull(token1, "markExtracting 必须生成 extract_token（V28 轮次列）");
        extractionService.markExtractionFailed(docId, "LLM 供应商超时", token1);
        assertEquals(1, extractionService.extraction(docId, 1, 10).status());

        extractionService.extract(1L, docId, null);
        String token2 = currentExtractToken(docId);
        assertNotEquals(token1, token2, "重新发起提取必须轮换新一代 token");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> extractionService.markExtractionFailed(docId, "LLM 供应商超时（旧轮次）", token1));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        ExtractionPreview after = extractionService.extraction(docId, 1, 10);
        assertEquals(3, after.status(), "旧轮次失败回写不得把新轮次任务打回 1（tick 70 缺陷链）");
        assertNull(after.extractError(), "旧错误文案不得覆写新轮次任务");
    }

    
    @Test
    void test_f2_stale_generation_success_writeback_rejected_on_new_generation() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token1 = currentExtractToken(docId);
        extractionService.markExtractionFailed(docId, "LLM 供应商超时", token1);
        extractionService.extract(1L, docId, null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> extractionService.writeExtractionResult(docId, fiveItems(), token1));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        ExtractionPreview after = extractionService.extraction(docId, 1, 10);
        assertEquals(3, after.status(), "旧轮次成功回写不得把新轮次任务置 4");
        assertEquals(0L, after.total(), "旧轮次预览条目不得混入新轮次");
    }

    
    @Test
    void test_f2_generation_race_retry_chain_converges() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token1 = currentExtractToken(docId);
        extractionService.markExtractionFailed(docId, "LLM 供应商超时", token1);
        extractionService.extract(1L, docId, null);
        String token2 = currentExtractToken(docId);

        assertThrows(ServiceException.class,
                () -> extractionService.markExtractionFailed(docId, "旧轮次超时", token1));
        assertThrows(ServiceException.class,
                () -> extractionService.writeExtractionResult(docId, fiveItems(), token1));

        extractionService.writeExtractionResult(docId, fiveItems(), token2);
        ExtractionPreview done = extractionService.extraction(docId, 1, 10);
        assertEquals(4, done.status(), "新轮次成功回写正常 3→4");
        assertEquals(5L, done.total());
        ConfirmExtractionResult confirmed = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(1, null, null, null, null, true, null)));
        assertEquals(1, confirmed.createdCount());
    }

    
    @Test
    void test_f2_same_generation_success_writeback_self_heals_after_attempt1_failure() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        String token = currentExtractToken(docId);
        extractionService.markExtractionFailed(docId, "LLM 供应商瞬时错误", token);
        extractionService.writeExtractionResult(docId, fiveItems(), token);
        ExtractionPreview after = extractionService.extraction(docId, 1, 10);
        assertEquals(4, after.status(), "同轮次成功回写自愈 1→4（tick 70 伴生缺陷：消费者重试永不自愈）");
        assertEquals(5L, after.total());
        assertNull(after.extractError(), "成功回写必须清 extract_error");
    }

    // ===== Re-extract tri-state: homonym mints an update-proposal card, no second row =====

    /** Deep-linked session anchor projection (session source=3, context documentId, card on the existing row). */
    private java.util.List<java.util.Map<String, Object>> proposalCards(long projectId, long requirementId) {
        return jdbcTemplate.queryForList(
                "SELECT d.id, d.status, d.base_version, d.payload_json::jsonb->>'title' AS proposed_title,"
                        + " s.source, s.context_json->>'documentId' AS anchored_doc, s.user_id"
                        + " FROM tp_ai_chat_draft d JOIN tp_ai_chat_session s ON s.id = d.session_id"
                        + " WHERE d.object_type = 'REQUIREMENT_WRITE' AND d.target_id = ?"
                        + " AND s.project_id = ?",
                requirementId, projectId);
    }

    /** Spec-1: homonym + default disposition -> drafted=1, zero new rows, anchored pending card. */
    @Test
    void confirmHomonymMintsAnchoredUpdateProposalWithoutLanding() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        RequirementInfo existing = requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, null, "库存不足下单失败提示", "旧的验收标准", "旧章节"));

        ConfirmExtractionResult result = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(1, null, null, null, null, true, null)));

        assertEquals(0, result.createdCount(), "homonym hit must never mint a second row");
        assertEquals(1, result.drafted(), "homonym hit must mint an update-proposal card (no silent skip)");
        assertEquals(1L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id=?", Long.class, projectId),
                "tp_requirement row count untouched");
        java.util.List<java.util.Map<String, Object>> cards = proposalCards(projectId, existing.id());
        assertEquals(1, cards.size(), "exactly one pending card");
        java.util.Map<String, Object> card = cards.get(0);
        assertEquals(1, ((Number) card.get("status")).intValue(), "card stays pending (1) for the human one-click door");
        assertEquals(existing.version(), ((Number) card.get("base_version")).intValue(),
                "auto re-anchor: card carries the existing row current version as optimistic-lock base");
        assertEquals("库存不足下单失败提示", card.get("proposed_title"), "payload = extracted item content");
        assertEquals(3, ((Number) card.get("source")).intValue(), "deep-linked session source = 3");
        assertEquals(String.valueOf(docId), String.valueOf(card.get("anchored_doc")),
                "session context_json.documentId points back to this document (re-extract deep link)");
    }

    /** Spec-2: repeated confirm never resends the card already in place (double-anchor idempotence). */
    @Test
    void confirmHomonymTwiceDoesNotResendTheSameProposalCard() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        RequirementInfo existing = requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, null, "支付超时自动取消", "超时后订单状态=已取消", "支付流程"));

        extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(3, null, null, null, null, true, null)));
        ConfirmExtractionResult second = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(3, null, null, null, null, true, null)));

        assertEquals(0, second.drafted(), "card already in place must never be resent (idempotent double anchor)");
        assertEquals(1, second.skipped(), "already-in-place item counts as skipped");
        assertEquals(1, proposalCards(projectId, existing.id()).size(), "pending card count stays one");
        assertEquals(1L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id=?", Long.class, projectId),
                "repeated confirm lands nothing");
    }

    /** Spec-3: KEEP_AS_IS closes a homonym item without minting a card and without any write. */
    @Test
    void confirmKeepAsIsClosesHomonymItemWithoutCardOrLanding() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        RequirementInfo existing = requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, null, "库存不足下单失败提示", "旧的验收标准", "旧章节"));

        ConfirmExtractionResult result = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(1, null, null, null, null, true, null)),
                Map.of(1L, "KEEP_AS_IS"));

        assertEquals(0, result.createdCount(), "keep-as-is never creates a second row");
        assertEquals(0, result.drafted(), "keep-as-is never mints an update-proposal card");
        assertEquals(1, result.skipped(), "keep-as-is item counts as skipped");
        assertEquals(1L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id=?", Long.class, projectId),
                "existing row count untouched");
        assertEquals(0, proposalCards(projectId, existing.id()).size(), "no card in place");
        assertEquals(0L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'DEDUP_FORCE_NEW' AND project_id = ?",
                Long.class, projectId),
                "keep-as-is records no force-new audit for this project");
    }

    /** Spec-4: FORCE_NEW lands a new row despite the homonym block and records the override audit. */
    @Test
    void confirmForceNewLandsRowWithOverrideAudit() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        simulateAiWriteSuccess(docId);
        requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, null, "支付超时自动取消", "超时后订单状态=已取消", "支付流程"));

        ConfirmExtractionResult result = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(3, null, null, null, null, true, null)),
                Map.of(3L, "FORCE_NEW"));

        assertEquals(1, result.createdCount(), "force-new lands the row although the gate blocks");
        assertEquals(0, result.drafted(), "force-new resolves as a row, not as a card");
        assertEquals(0, result.skipped());
        assertEquals(2L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id=?", Long.class, projectId),
                "force-new adds exactly one row next to the existing homonym");
        assertEquals(1L, (long) jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'DEDUP_FORCE_NEW' AND project_id = ?",
                Long.class, projectId),
                "override audit recorded once (same posture as the near-miss audit)");
        ExtractionPreview after = extractionService.extraction(docId, 1, 10);
        assertNotNull(after.items().get(2).requirementId(), "landed item keeps its idempotent anchor");
    }

    /** Spec-5: rewrite merge keys on the normalized title (full-width/spacing variants keep the confirmed anchor). */
    @Test
    void rewriteMergeKeepsConfirmedAnchorAcrossFullWidthAndSpacingVariants() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        extractionService.extract(1L, docId, null);
        extractionService.writeExtractionResult(docId, List.of(new ExtractionItem(
                0, "SKU 100 校验", "数量必须为正整数", "下单流程", 0.9, false, null)),
                currentExtractToken(docId));
        ConfirmExtractionResult landed = extractionService.confirmExtraction(1L, docId,
                List.of(new ExtractionItem(1, null, null, null, null, true, null)));
        assertEquals(1, landed.createdCount());
        Long requirementId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_requirement WHERE project_id=?", Long.class, projectId);

        extractionService.extract(1L, docId, null);
        extractionService.writeExtractionResult(docId, List.of(new ExtractionItem(
                0, "ＳＫＵ　１００　校验", "数量必须为正整数", "下单流程", 0.9, false, null)),
                currentExtractToken(docId));

        ExtractionPreview merged = extractionService.extraction(docId, 1, 10);
        assertEquals(1L, merged.total(), "归一命中=同一条目合并，不得铺成两行");
        assertNotNull(merged.items().get(0).requirementId(), "已入库锚点必须保留（幂等锚不丢）");
        assertEquals(requirementId, merged.items().get(0).requirementId());
    }
}
