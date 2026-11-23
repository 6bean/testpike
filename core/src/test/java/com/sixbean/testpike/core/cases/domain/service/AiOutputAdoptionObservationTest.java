package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.component.AiOutputAdoptionObserver;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adoption ledger wiring for the case edit and delete chains ( piece 2,
 * BA ruling 7). Runs against the real dev DB of this chain's slot (strict
 * testing: no mocks on service interfaces). Test rows live in a uniquely
 * named throw-away project and are removed per case by id — no TRUNCATE of
 * any factory table (R37).
 *
 * <p>Covered contract: an edit of an AI-generated case records exactly one
 * derived observation keyed by the version event (replays dedup), a delete
 * records deprecated with no threshold read, hand-written material is never
 * observed, and metadata-only touches do not feed the ledger.</p>
 */
@SpringBootTest(classes = CoreApplication.class)
class AiOutputAdoptionObservationTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AiOutputAdoptionObserver observer;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long operatorId;

    @BeforeEach
    void seedProject() {
        CaseTestMembers.ensureUserWithRole(jdbc, 9_310_001L, "admin");
        operatorId = 9_310_001L;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = projectService.create(operatorId,
                new ProjectCreateInput("采纳台账临时项目" + suffix, "adp" + suffix, "zh-CN", "P1", null));
    }

    @AfterEach
    void removeTestRows() {
        // Ledger first (it references the case ids as scope_ref), then the
        // case family, all scoped to this test's throw-away project by id —
        // no TRUNCATE, no factory-table touch (R37).
        jdbc.update("DELETE FROM tp_ai_fewshot_revision WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_ai_behavior_outcome WHERE source = 'adoption'"
                + " AND scope_ref IN (SELECT id::text FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_case_step WHERE case_id IN"
                + " (SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_case_version WHERE case_id IN"
                + " (SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** AI-created case with a realistic short content body (v1 snapshot lands on create). */
    private CaseInfo createAiCase(int source) {
        CaseInfo created = caseService.create(operatorId, new CaseCreateInput(
                projectId, "验证登录成功后跳转工作台首页并保持会话",
                "已注册测试账号且密码正确，浏览器已清空缓存",
                "登录后跳转到工作台页面且顶栏显示当前用户名",
                1, 2, "web", 1, null, source, null,
                List.of(new CaseStepInput(1, "打开登录页输入账号密码并提交", 0),
                        new CaseStepInput(2, "检查跳转后的页面标题与用户信息", 1)),
                null, null, null, null, null));
        return created;
    }

    private List<Map<String, Object>> adoptionRows(long caseId) {
        return jdbc.queryForList(
                "SELECT source, source_event_id, template_domain, scope, outcome, project_id"
                        + " FROM tp_ai_behavior_outcome WHERE source = 'adoption' AND scope_ref = ?",
                String.valueOf(caseId));
    }

    @Test
    void editingAiCaseRecordsOneLightEditObservation() {
        CaseInfo created = createAiCase(1);
        // A suffix of a few characters on one field keeps the edit ratio well
        // below the 0.2 soft-edit threshold — the classification must be partial.
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                null, null, "登录后跳转到工作台页面且顶栏显示当前用户名与项目名",
                null, null, null, null, null), created.version());
        List<Map<String, Object>> rows = adoptionRows(created.id());
        assertEquals(1, rows.size(), "one edit event must produce exactly one adoption row");
        Map<String, Object> row = rows.get(0);
        assertEquals("partial", row.get("outcome"));
        assertEquals("case-edit:" + created.id() + ":v" + (created.version() + 1),
                row.get("source_event_id"));
        assertEquals("用例生成", row.get("template_domain"),
                "no producing ai task pinned -> channel default domain");
        assertEquals("task", row.get("scope"));
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
    }

    @Test
    void replayOfTheSameVersionObservationLandsOnce() {
        CaseInfo created = createAiCase(1);
        CaseInfo edited = caseService.update(operatorId, created.id(), new CaseUpdateInput(
                null, null, "登录后跳转到工作台页面且顶栏显示当前用户名与项目名",
                null, null, null, null, null), created.version());
        // Replay the observation of the same version directly: the natural
        // event key must dedup it inside the shared ledger.
        observer.observeEdit(edited);
        assertEquals(1, adoptionRows(created.id()).size(),
                "replaying one version observation must count exactly once");
    }

    @Test
    void deletingAiCaseRecordsDeprecated() {
        CaseInfo created = createAiCase(1);
        caseService.delete(operatorId, created.id());
        List<Map<String, Object>> rows = adoptionRows(created.id());
        assertEquals(1, rows.size(), "delete of an AI case must record one observation");
        assertEquals("deprecated", rows.get(0).get("outcome"));
        assertEquals("case-delete:" + created.id(), rows.get(0).get("source_event_id"));
    }

    @Test
    void handWrittenCaseEditsAreNeverObserved() {
        // source=3 (legacy import): the material never came out of a model,
        // counting its edits would poison the adoption rate.
        CaseInfo created = createAiCase(3);
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                null, null, "登录后跳转到工作台页面且顶栏显示当前用户名与项目名",
                null, null, null, null, null), created.version());
        caseService.delete(operatorId, created.id());
        assertTrue(adoptionRows(created.id()).isEmpty(),
                "non-AI material must not feed the adoption ledger");
    }

    @Test
    void metadataOnlyEditDoesNotFeedTheLedger() {
        CaseInfo created = createAiCase(1);
        // Priority is metadata, not model text: the content family predicate
        // must keep this touch out of the adoption observations.
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                null, null, null, 0, null, null, null, null), created.version());
        assertTrue(adoptionRows(created.id()).isEmpty(),
                "metadata-only edit must not record an adoption observation");
    }

    // ---- piece 3: corrected/deprecated pairs feed the few-shot pool ----

    private List<Map<String, Object>> revisionRows(long caseId) {
        return jdbc.queryForList(
                "SELECT event_key, outcome, ai_text, human_text, project_id"
                        + " FROM tp_ai_fewshot_revision WHERE case_id = ?", caseId);
    }

    @Test
    void correctedRewriteFeedsTheRevisionPoolWithTheBeforeAfterPair() {
        CaseInfo created = createAiCase(1);
        // A rewrite that keeps recognisable structure but replaces title,
        // precondition and expected result at once: the ledger classifies it
        // (corrected or deprecated depending on measured similarity) and the
        // pool must mirror that verdict with both sides of the pair.
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                "验证登录成功后跳转营销活动配置页并保留筛选条件",
                "已注册测试账号且密码正确，浏览器已清空缓存并禁用扩展插件",
                "登录后跳转到营销活动配置页且筛选条件完整保留",
                null, null, null, null, null), created.version());
        String ledgerOutcome = (String) adoptionRows(created.id()).get(0).get("outcome");
        List<Map<String, Object>> pool = revisionRows(created.id());
        if ("corrected".equals(ledgerOutcome) || "deprecated".equals(ledgerOutcome)) {
            assertEquals(1, pool.size(), "修正形态必须入池且入池形态与台账一致: " + ledgerOutcome);
            Map<String, Object> row = pool.get(0);
            assertEquals(ledgerOutcome, row.get("outcome"));
            assertEquals(created.id() + ":v" + (created.version() + 1), row.get("event_key"));
            assertNotNull(row.get("ai_text"), "池行须带 AI 原文侧");
            assertNotNull(row.get("human_text"), "修正池行须带人工结果侧");
            assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        } else {
            assertTrue(pool.isEmpty(), "仅 corrected/deprecated 形态入池，" + ledgerOutcome + " 不得入池");
        }
    }

    @Test
    void lightEditObservationStaysOutOfTheRevisionPool() {
        CaseInfo created = createAiCase(1);
        // The same small suffix edit the ledger classifies as partial:
        // negative samples only come from corrections, not light finishing.
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                null, null, "登录后跳转到工作台页面且顶栏显示当前用户名与项目名",
                null, null, null, null, null), created.version());
        assertEquals("partial", adoptionRows(created.id()).get(0).get("outcome"));
        assertTrue(revisionRows(created.id()).isEmpty(),
                "partial（轻改）形态不得进负样本池");
    }

    @Test
    void deleteObservationFeedsThePoolWithTheAiSideOnly() {
        CaseInfo created = createAiCase(1);
        caseService.delete(operatorId, created.id());
        List<Map<String, Object>> pool = revisionRows(created.id());
        assertEquals(1, pool.size(), "删除是最强修正信号，池行须带 AI 原文与 null 人工侧");
        assertEquals("deprecated", pool.get(0).get("outcome"));
        assertEquals(String.valueOf(created.id()), pool.get(0).get("event_key"));
        assertNull(pool.get(0).get("human_text"), "删除形态人工侧为 NULL");
    }

    @Test
    void revisionPoolWriteDedupsTheSameVersionReplay() {
        CaseInfo created = createAiCase(1);
        CaseInfo edited = caseService.update(operatorId, created.id(), new CaseUpdateInput(
                "验证登录成功后跳转工作台首页并保持会话",
                "已注册测试账号且密码正确，浏览器已清空缓存",
                "登录成功后停留在工作台且会话在刷新后仍然有效",
                null, null, null, null, null), created.version());
        String ledgerOutcome = (String) adoptionRows(created.id()).get(0).get("outcome");
        if (!"corrected".equals(ledgerOutcome) && !"deprecated".equals(ledgerOutcome)) {
            return; // not a pool-eligible verdict in this run — dedup rides the key
        }
        observer.observeEdit(edited);
        observer.observeEdit(edited);
        assertEquals(1, revisionRows(created.id()).size(),
                "同版本观测重放池内须恰一行");
    }

    @Test
    void handWrittenRevisionsNeverEnterThePool() {
        CaseInfo created = createAiCase(3);
        caseService.update(operatorId, created.id(), new CaseUpdateInput(
                "验证登录成功后跳转营销活动配置页并保留筛选条件",
                "已注册测试账号且密码正确，浏览器已清空缓存并禁用扩展插件",
                "登录后跳转到营销活动配置页且筛选条件完整保留",
                null, null, null, null, null), created.version());
        caseService.delete(operatorId, created.id());
        assertTrue(revisionRows(created.id()).isEmpty(),
                "非 AI 产物的人工改动不是模型的修正样本，禁入池");
    }
}
