package com.sixbean.testpike.core.document.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.RevisionDraftPregen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lazy pre-generation of requirement revision-draft cards behind the pending
 * change analysis : opening the disposition view
 * dispatches exactly ONE anchored dialog run per live, same-project
 * requirement with new chapter content, through the same ChatRunService door
 * the human dialog uses — never a parallel run source, never a requirement
 * write (red line 10c: the version ledger and the confirmation door stay
 * untouched until a human accepts the card).
 * <p>The spectrum pins on the real dev database, zero mocks, same fixture
 * posture as RequirementWriteDraftFlowTest (rows inserted by id, cleared by id):
 * <ul>
 * <li>first open creates the deep-link session (source 3, anchored on the
 * analysis id) and dispatches per requirement with the
 * {@code (id=<requirementId>)} anchor inside the prompt;</li>
 * <li>deleted-chapter items (empty new content) honestly land zero cards;
 * foreign-project requirements are refused by the attribution guard;
 * requirements soft-deleted after the snapshot count as missing;</li>
 * <li>re-opening replays zero runs (double anchor: user echo + pending
 * draft) and finds the same session — no second shell;</li>
 * <li>a pre-landed pending REQUIREMENT_WRITE draft alone blocks the
 * dispatch even while the echo door is still quiet;</li>
 * <li>an analysis without requirement impact creates no session at all;
 * a handled (non-pending) analysis answers all-zero and touches nothing;</li>
 * <li>red line 10c: after pre-generation the requirement version never
 * bumped, the version ledger never grew and no draft reached the
 * applied state.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class DocumentChangeRevisionDraftPregenTest {

    @Autowired
    private DocumentChangeRevisionDraftService pregenService;
    @Autowired
    private DocumentChangeAnalysisService analysisService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ChatSessionService sessionService;
    @Autowired
    private ChatFrameService frameService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tag;
    private long userId;
    private long projectId;
    private long otherProjectId;
    private final List<Long> requirementIds = new ArrayList<>();
    private final List<Long> documentIds = new ArrayList<>();
    private final List<Long> analysisIds = new ArrayList<>();
    private final List<Long> sessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // fixture id entropy stays inside tp_project.code varchar(20)
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = createProject();
        otherProjectId = createProject();
    }

    @AfterEach
    void tearDown() {
        for (long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        }
        for (long analysisId : analysisIds) {
            jdbcTemplate.update("DELETE FROM tp_doc_change_analysis WHERE id = ?", analysisId);
        }
        for (long documentId : documentIds) {
            jdbcTemplate.update("DELETE FROM tp_document_chunk WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM tp_document WHERE id = ?", documentId);
        }
        for (long requirementId : requirementIds) {
            jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", requirementId);
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", requirementId);
        }
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private long createProject() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag + "-" + UUID.randomUUID().toString().substring(0, 6));
    }

    
    private static byte[] md(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String docV1() {
        return "# 订单中心 PRD\n\n## 下单流程\n\n下单正文A1。\n\n"
                + "## 支付流程\n\n支付正文恒定。\n\n## 库存流程\n\n库存正文C1。\n";
    }

    private static String docV2() {
        return "# 订单中心 PRD\n\n## 下单流程\n\n下单正文A2。\n\n"
                + "## 支付流程\n\n支付正文恒定。\n\n## 对账流程\n\n对账正文新增。\n";
    }

    /** F2-confirm-shaped document-extracted requirement (source 2, doc:{old} anchor). */
    private long seedRequirement(long project, long oldDocId, String chapter, String title) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, source_id, title, status, chapter)"
                        + " VALUES (?, 2, ?, ?, 1, ?) RETURNING id", Long.class,
                project, "doc:" + oldDocId, title, chapter);
        requirementIds.add(id);
        return id;
    }

    /** Upload the old revision — requirements anchor on this document id. */
    private DocumentInfo uploadOld(String filename) {
        DocumentInfo v1 = documentService.upload(userId, projectId, filename, md(docV1()), null);
        documentIds.add(v1.id());
        return v1;
    }

    /** Upload the new revision of the same PRD and take the analysis snapshot. */
    private DocChangeAnalysisInfo uploadNewAndAnalyze(String filename) {
        DocumentInfo v2 = documentService.upload(userId, projectId, filename, md(docV2()), null);
        documentIds.add(v2.id());
        DocChangeAnalysisInfo pending = analysisService.analyze(userId, v2.id());
        analysisIds.add(pending.id());
        return pending;
    }

    private int sessionCount(long analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_session WHERE user_id = ? AND project_id = ?"
                        + " AND source = 3 AND context_json->>'analysisId' = ?",
                Integer.class, userId, projectId, String.valueOf(analysisId));
    }

    private int echoCount(long analysisId, long requirementId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_message WHERE role = 'user'"
                        + " AND session_id IN (SELECT id FROM tp_ai_chat_session"
                        + " WHERE user_id = ? AND project_id = ? AND source = 3"
                        + " AND context_json->>'analysisId' = ?)"
                        + " AND content LIKE '%(id=' || ? || ')%'",
                Integer.class, userId, projectId, String.valueOf(analysisId), requirementId);
    }

    @Test
    void firstOpenCreatesOneSessionAndDispatchesExactlyOneAnchoredRunPerRequirement() {
        DocumentInfo oldDoc = uploadOld("预生成PRD.md");
        long reqOrder = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "下单需求");
        long reqGone = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "已软删需求");
        long reqStock = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 库存流程", "库存需求");
        long reqRecon = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 对账流程", "对账需求");
        seedRequirement(otherProjectId, oldDoc.id(), "订单中心 PRD > 下单流程", "他项目需求");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("预生成PRD.md");
        // Soft-delete the second hit after the snapshot: the id still rides the
        // stored change item, so the door must count it as missing, not dispatch it.
        jdbcTemplate.update("UPDATE tp_requirement SET deleted = 1 WHERE id = ?", reqGone);

        RevisionDraftPregen receipt = pregenService.pregenDrafts(userId, analysis.id());

        assertNotNull(receipt.sessionId(), "首开必须落深链会话");
        sessionIds.add(receipt.sessionId());
        assertEquals(1, sessionCount(analysis.id()), "会话锚=(user,project,source3,analysisId) 恰一行");
        assertEquals(2, receipt.dispatched(), "下单+对账各一次运行；软删/他项目/空章节零运行");
        assertEquals(1, receipt.skippedMissing(), "快照后软删的需求按缺失点名，禁静默");
        assertEquals(1, receipt.skippedForeign(), "归属守卫：他项目需求先拒后点");
        assertEquals(1, receipt.skippedNoContent(), "删除章节（新文为空）诚实零卡");
        assertEquals(0, receipt.skippedExisting(), "首开无重放");
        assertEquals(1, echoCount(analysis.id(), reqOrder), "下单需求恰一条带锚用户回显");
        assertEquals(1, echoCount(analysis.id(), reqRecon), "对账需求恰一条带锚用户回显");
        assertEquals(0, echoCount(analysis.id(), reqGone), "软删需求零运行");
        assertEquals(0, echoCount(analysis.id(), reqStock), "删除章节需求零运行");

        // Red line 10c: pre-generation prepares cards, it never writes the requirement.
        assertEquals(1, (int) jdbcTemplate.queryForObject(
                "SELECT version FROM tp_requirement WHERE id = ?", Integer.class, reqOrder),
                "预生成绝不推进需求版本");
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_requirement_version WHERE requirement_id IN (?, ?)",
                Integer.class, reqOrder, reqRecon), "预生成绝不留版本快照");
        assertEquals(0, (int) jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE session_id = ? AND status = 2",
                Integer.class, receipt.sessionId()), "预生成绝不产生已直通草稿（一键确认门保持）");
    }

    @Test
    void reopeningReplaysZeroRunsThroughTheDoubleAnchor() {
        DocumentInfo oldDoc = uploadOld("重放PRD.md");
        seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "下单需求");
        seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 对账流程", "对账需求");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("重放PRD.md");

        RevisionDraftPregen first = pregenService.pregenDrafts(userId, analysis.id());
        assertEquals(2, first.dispatched(), "首开两次运行后才谈重放");
        RevisionDraftPregen again = pregenService.pregenDrafts(userId, analysis.id());

        assertEquals(0, again.dispatched(), "重放零运行（用户回显锚）");
        assertEquals(2, again.skippedExisting(), "两次运行按已存在点名");
        assertEquals(first.sessionId(), again.sessionId(), "重放复用同一会话，禁第二壳");
        assertEquals(1, sessionCount(analysis.id()));
        sessionIds.add(first.sessionId());
    }

    @Test
    void pendingDraftAloneBlocksTheRunEvenWithoutAnEcho() {
        DocumentInfo oldDoc = uploadOld("草稿锚PRD.md");
        long reqOrder = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "下单需求");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("草稿锚PRD.md");
        // The reviewer's anchored session already exists (pregen must find it)
        // and a pending REQUIREMENT_WRITE draft for this requirement is on the
        // ledger — the dispatch must skip even though no user echo was written.
        ChatSessionInfo session = sessionService.create(projectId, userId, "", 3,
                "{\"analysisId\":" + analysis.id() + "}");
        sessionIds.add(session.id());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", "REQUIREMENT_WRITE");
        meta.put("targetId", reqOrder);
        meta.put("baseVersion", 1);
        meta.put("model", "test-model");
        meta.put("actionJson", "{\"title\":\"预置草稿\",\"acceptanceCriteria\":\"x\",\"chapter\":null}");
        long message = frameService.openMessage(session.id(), "assistant", null);
        try {
            frameService.appendFrame(session.id(), message, ChatFrameType.DRAFT,
                    "requirement write proposal", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE session_id = ? AND object_type ="
                        + " 'REQUIREMENT_WRITE' AND target_id = ? AND status = 1",
                Integer.class, session.id(), reqOrder) >= 1, "夹具必须真实落 pending 草稿");

        RevisionDraftPregen receipt = pregenService.pregenDrafts(userId, analysis.id());

        assertEquals(0, receipt.dispatched(), "pending 草稿锚单锚即拦，禁重复运行");
        assertEquals(1, receipt.skippedExisting());
        assertEquals(0, echoCount(analysis.id(), reqOrder), "被拦需求绝不留下用户回显");
        assertEquals(session.id(), receipt.sessionId(), "find-or-create 复用既有锚会话");
    }

    @Test
    void analysisWithoutRequirementImpactCreatesNoSession() {
        uploadOld("无影响PRD.md");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("无影响PRD.md");

        RevisionDraftPregen receipt = pregenService.pregenDrafts(userId, analysis.id());

        assertNull(receipt.sessionId(), "零影响分析诚实零回执，禁建空会话壳");
        assertEquals(0, receipt.dispatched());
        assertEquals(0, sessionCount(analysis.id()));
    }

    @Test
    void nonPendingAnalysisAnswersZeroAndTouchesNothing() {
        DocumentInfo oldDoc = uploadOld("已处置PRD.md");
        long reqOrder = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "下单需求");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("已处置PRD.md");
        analysisService.handle(userId, analysis.id(), "处置完毕");

        RevisionDraftPregen receipt = pregenService.pregenDrafts(userId, analysis.id());

        assertNull(receipt.sessionId(), "非待复核分析零回执不建会话");
        assertEquals(0, receipt.dispatched());
        assertEquals(0, echoCount(analysis.id(), reqOrder));
    }

    /**
 * Prompt reachability contract : the tool catalog
 * is larger than the pre-bound window, so the anchored prompt must NAME
 * the exact write tool and hand over its anchor parameters — live evidence
 * showed a merely descriptive ask ("produce a REQUIREMENT_WRITE proposal")
 * leaves the model answering in plain text with zero draft cards, while an
 * imperative prompt that names {@code requirement.create} lands one.
 */
    @Test
    void anchoredRunPromptNamesTheWriteToolAndCarriesTheAnchorContract() {
        DocumentInfo oldDoc = uploadOld("契约PRD.md");
        long reqOrder = seedRequirement(projectId, oldDoc.id(), "订单中心 PRD > 下单流程", "下单需求");
        DocChangeAnalysisInfo analysis = uploadNewAndAnalyze("契约PRD.md");

        RevisionDraftPregen receipt = pregenService.pregenDrafts(userId, analysis.id());
        assertNotNull(receipt.sessionId());
        sessionIds.add(receipt.sessionId());
        assertEquals(1, receipt.dispatched());

        int liveVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_requirement WHERE id = ?", Integer.class, reqOrder);
        String echo = jdbcTemplate.queryForObject(
                "SELECT content FROM tp_ai_chat_message WHERE session_id = ? AND role = 'user'"
                        + " AND content LIKE ? LIMIT 1",
                String.class, receipt.sessionId(), "%(id=" + reqOrder + ")%");
        assertNotNull(echo, "anchored user echo must exist");
        assertTrue(echo.contains("search_tools"),
                "prompt must tell the model to load the tool from the catalog");
        assertTrue(echo.contains("requirement.update"),
                "prompt must name the anchored write tool by its real name");
        assertTrue(echo.contains("requirementId=" + reqOrder),
                "prompt must hand over the requirementId parameter value");
        assertTrue(echo.contains("baseVersion=" + liveVersion),
                "prompt must hand over the baseVersion parameter value");
        assertTrue(echo.contains("requirement.create"),
                "prompt must name the create branch for the fully-replaced case");
    }
}
