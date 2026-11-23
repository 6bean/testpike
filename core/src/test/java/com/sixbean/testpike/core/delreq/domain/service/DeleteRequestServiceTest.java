package com.sixbean.testpike.core.delreq.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRecycleInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestCreateInput;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestRejectInput;
import com.sixbean.testpike.core.delreq.domain.service.DeleteRequestService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DeleteRequestServiceTest {

    @Autowired
    private DeleteRequestService deleteRequestService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long applicantId;
    private Long ownerId;
    private long projectId;

    /**
 * R37 fixture-scoped teardown : every seeded row hangs off
 * the * project-code family - the approval request, requirement points,
 * cases with their steps/links, user-project links, audit rows and the
 * DELETE_* notifications all carry a project id the FK walk or the default
 * project-keyed set reaches. The family is resolved from the database (not
 * an in-memory list) so rows left by an interrupted run are swept too -
 * otherwise the residual tp_delete_request rows FK-block the t165 user
 * cleanup below. The old blanket TRUNCATE wiped shipped projects and
 * concurrent suites' rows on the shared dev database.
 */
    @BeforeEach
    @AfterEach
    void cleanAndSeed() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'T165%'", Long.class));
        // Crash residue guard: a tp_delete_request row whose project row is
        // already gone is invisible to the project-keyed sweep above, yet its
        // applicant/approver FK still blocks the targeted user cleanup below -
        // purge the whole user family's requests by user, not by project.
        jdbcTemplate.update("DELETE FROM tp_delete_request WHERE applicant_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 't165-%')"
                + " OR approver_id IN (SELECT id FROM tp_user WHERE username LIKE 't165-%')");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 't165-%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 't165-%'");
        applicantId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES ('t165-applicant', 'x', 'T165申请人', 1, 1) RETURNING id
                """, Long.class);
        ownerId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES ('t165-owner', 'x', 'T165负责人', 2, 1) RETURNING id
                """, Long.class);
        projectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('T165删除审批项目', 'T165-DR', 'zh-CN', ?) RETURNING id
                """, Long.class, applicantId);
        jdbcTemplate.update("INSERT INTO tp_user_project (user_id, project_id, role) VALUES (?, ?, 2)",
                ownerId, projectId);
    }

    // ---- helpers ----

    private long createCase(String title) {
        CaseInfo created = caseService.create(applicantId, new CaseCreateInput(
                projectId, title, "前置", "预期", 1, 2, "web", null, null, null, null, null));
        return created.id();
    }

    private DeleteRequestInfo submit(long caseId, String reason) {
        return deleteRequestService.create(applicantId,
                new DeleteRequestCreateInput(projectId, 1, caseId, reason));
    }

    private int caseDeletedFlag(long caseId) {
        Integer flag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        return flag == null ? -1 : flag;
    }

    private List<Map<String, Object>> notifications(String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = ? ORDER BY id", eventType);
    }

    private List<Long> expectedAdminIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE deleted = 0 AND (role = 4 OR id IN ("
                        + "SELECT ur.user_id FROM tp_user_role ur JOIN tp_role r ON r.id = ur.role_id "
                        + "WHERE r.code = 'admin')) ORDER BY id", Long.class);
    }

    private int auditCount(String action, long requestId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'delete_request'"
                        + " AND action = ? AND object_id = ?::text",
                Integer.class, action, String.valueOf(requestId));
        return n == null ? 0 : n;
    }


    @Test
    void 提交删除申请_审批单pending_用例完好_通知管理员与项目负责人() {
        long caseId = createCase("重复的登录用例");
        DeleteRequestInfo req = submit(caseId, "与 TC-DEMO-0001 完全重复");

        assertEquals(2, req.status(), "创建即待审批（ApprovalStatus.PENDING_APPROVAL 码复用）");
        assertEquals("待审批", req.statusLabel());
        assertEquals(1, req.targetType());
        assertEquals("用例", req.targetTypeLabel());
        assertEquals(caseId, req.targetId());
        assertEquals(projectId, req.projectId());
        assertEquals(applicantId, req.applicantId());
        assertEquals("与 TC-DEMO-0001 完全重复", req.reason());
        assertNull(req.rejectReason());
        assertNull(req.approverId());
        assertEquals(0, caseDeletedFlag(caseId), "提交即删除＝红线：审批前用例必须完好");

        List<Map<String, Object>> req1 = notifications("DELETE_APPROVAL_REQUEST");
        List<Long> recipients = req1.stream()
                .map(r -> ((Number) r.get("user_id")).longValue()).toList();
        assertTrue(recipients.containsAll(expectedAdminIds()), "全体管理员在收件人: " + recipients);
        assertTrue(recipients.contains(ownerId), "项目负责人在收件人: " + recipients);
        assertEquals(recipients.size(), new java.util.LinkedHashSet<>(recipients).size(), "并集去重");
        assertFalse(req1.isEmpty());
        assertTrue(req1.get(0).get("title").toString().contains("删除"), req1.get(0).toString());
        assertTrue(req1.get(0).get("content").toString().contains("与 TC-DEMO-0001 完全重复"),
                "人话含申请理由: " + req1.get(0).get("content"));
        assertTrue(req1.get(0).get("content").toString().contains("T165申请人"), "人话含申请人");
    }


    @Test
    void 审批通过_用例软删进回收站_通知申请人恰1() {
        long caseId = createCase("待删除用例");
        DeleteRequestInfo req = submit(caseId, "需求作废");

        DeleteRequestInfo approved = deleteRequestService.approve(ownerId, req.id());

        assertEquals(3, approved.status(), "approve→PUBLISHED(3)=已批准（ApprovalStatus 码复用）");
        assertNotNull(approved.statusLabel(), "人话状态标签出参");
        assertEquals(ownerId, approved.approverId());
        assertNotNull(approved.handledAt(), "handled_at 落列");
        assertEquals(1, caseDeletedFlag(caseId), "approve 必须真执行软删（TC-MCP-05）");

        List<CaseRecycleInfo> bin = caseService.recycleBin(projectId, null, 1, 10).list();
        assertTrue(bin.stream().anyMatch(c -> c.id() == caseId),
                "P1 回收站可见（audit action=delete 反查衔接）: " + bin);
        CaseRecycleInfo entry = bin.stream().filter(c -> c.id() == caseId).findFirst().orElseThrow();
        assertEquals(ownerId, entry.deletedBy(), "删除人=审批执行人（审计原点，回收站反查）");

        List<Map<String, Object>> done = notifications("DELETE_APPROVED");
        assertEquals(1, done.size(), "结果通知申请人恰 1");
        assertEquals(applicantId, ((Number) done.get(0).get("user_id")).longValue());
        assertTrue(done.get(0).get("content").toString().contains("回收站"),
                "人话含回收站 30 天语义: " + done.get(0).get("content"));
    }


    @Test
    void 审批拒绝_用例零副作用_原因落列通知申请人() {
        long caseId = createCase("误报删除用例");
        DeleteRequestInfo req = submit(caseId, "看着重复");

        DeleteRequestInfo rejected = deleteRequestService.reject(ownerId, req.id(),
                new DeleteRequestRejectInput("与需求点 R-02 强关联，不允许删"));

        assertEquals(4, rejected.status(), "reject→REJECTED(4)");
        assertEquals("与需求点 R-02 强关联，不允许删", rejected.rejectReason());
        assertEquals(0, caseDeletedFlag(caseId), "TC-MCP-05：rejected 用例不动");

        List<Map<String, Object>> rej = notifications("DELETE_APPROVAL_REJECTED");
        assertEquals(1, rej.size());
        assertEquals(applicantId, ((Number) rej.get(0).get("user_id")).longValue());
        assertTrue(rej.get(0).get("content").toString().contains("与需求点 R-02 强关联，不允许删"),
                "人话含拒绝原因: " + rej.get(0).get("content"));
    }

    @Test
    void 拒绝缺原因_40001人话_状态不动() {
        long caseId = createCase("缺原因拒绝用例");
        DeleteRequestInfo req = submit(caseId, "理由");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                deleteRequestService.reject(ownerId, req.id(), new DeleteRequestRejectInput("  ")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("原因"), ex.getMessage());
        assertEquals(2, deleteRequestService.get(req.id()).status(), "拒绝失败状态保持 pending");
    }


    @Test
    void 同目标重复提交_40904人话含已存在单ID() {
        long caseId = createCase("重复提审用例");
        DeleteRequestInfo first = submit(caseId, "第一次申请");

        ServiceException ex = assertThrows(ServiceException.class, () -> submit(caseId, "第二次申请"));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains(String.valueOf(first.id())),
                "人话须含已存在单 ID 便于定位: " + ex.getMessage());
    }

    @Test
    void 重复审批_幂等短路_审计恰1_用例不双删() {
        long caseId = createCase("双审批用例");
        DeleteRequestInfo req = submit(caseId, "理由");
        deleteRequestService.approve(ownerId, req.id());

        DeleteRequestInfo again = deleteRequestService.approve(ownerId, req.id());
        assertEquals(3, again.status(), "IT-SKL-03 幂等：重复 approve 200 返回当前态");
        assertEquals(1, auditCount("approve", req.id()), "幂等短路不重复落审计（显式 record 基径）");
        assertEquals(1, notifications("DELETE_APPROVED").size(), "幂等短路不重复通知");
        assertEquals(1, caseDeletedFlag(caseId));
    }


    @Test
    void 非管理员非负责人_审批被拒40301() {
        long caseId = createCase("越权审批用例");
        DeleteRequestInfo req = submit(caseId, "理由");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> deleteRequestService.approve(applicantId, req.id()));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("管理员") || ex.getMessage().contains("负责人"),
                "人话指路权限面: " + ex.getMessage());
        assertEquals(0, caseDeletedFlag(caseId), "越权零副作用");
    }

    @Test
    void 系统管理员可审批() {
        long caseId = createCase("管理员审批用例");
        DeleteRequestInfo req = submit(caseId, "理由");
        Long admin = expectedAdminIds().get(0);

        DeleteRequestInfo approved = deleteRequestService.approve(admin, req.id());
        assertEquals(3, approved.status());
        assertEquals(1, caseDeletedFlag(caseId));
    }


    @Test
    void 形状校验族_触库前人话() {
        long caseId = createCase("形状用例");
        long otherCase = createCase("他项目用例");

        assertEquals(BizErrorCode.VALIDATION_ERROR, assertThrows(ServiceException.class,
                () -> deleteRequestService.create(applicantId,
                        new DeleteRequestCreateInput(projectId, 1, caseId, " "))).getCode());
        assertEquals(BizErrorCode.VALIDATION_ERROR, assertThrows(ServiceException.class,
                () -> deleteRequestService.create(applicantId,
                        new DeleteRequestCreateInput(projectId, 1, caseId, "理".repeat(501)))).getCode());
        ServiceException t = assertThrows(ServiceException.class, () -> deleteRequestService.create(
                applicantId, new DeleteRequestCreateInput(projectId, 3, 1L, "理由")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, t.getCode());
        assertTrue(t.getMessage().contains("用例"), "人话须指路当前仅支持用例: " + t.getMessage());
        ServiceException nf = assertThrows(ServiceException.class, () -> submit(999999L, "理由"));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
        long otherProject = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('他项目', 'T165-OTHER', 'zh-CN', ?) RETURNING id
                """, Long.class, applicantId);
        ServiceException pm = assertThrows(ServiceException.class, () -> deleteRequestService.create(
                applicantId, new DeleteRequestCreateInput(otherProject, 1, otherCase, "理由")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, pm.getCode());
        assertTrue(pm.getMessage().contains("项目"), pm.getMessage());
    }

    // ---- page 18: server-side sort over the approval ledger ----

    /**
     * RED anchor: a whitelisted key orders the whole filtered window in SQL.
     * target_summary asc follows the TC-{code}-{seq} sequence the summary
     * materializes; status puts every pending row on a tie and the pinned
     * id DESC tie-break decides, which is exactly what keeps paging stable
     * when a whole window shares one status.
     */
    @Test
    void 排序白名单_目标摘要升序与状态平局id倒序() {
        long c1 = createCase("甲用例");
        long c2 = createCase("乙用例");
        long c3 = createCase("丙用例");
        long r1 = submit(c1, "重复").id();
        long r2 = submit(c2, "作废").id();
        long r3 = submit(c3, "误建").id();

        List<Long> bySummaryAsc = idsOf(deleteRequestService.list(
                projectId, null, 1, 20, "target_summary", "asc"));
        assertEquals(List.of(r1, r2, r3), bySummaryAsc,
                "target_summary ASC follows the TC sequence the summary carries");

        // all three rows share status=2 (pending) -> the id DESC tie-break rules
        List<Long> byStatusAsc = idsOf(deleteRequestService.list(
                projectId, null, 1, 20, "status", "asc"));
        assertEquals(List.of(r3, r2, r1), byStatusAsc,
                "equal-status window stays deterministic through the id DESC tie-break");
    }

    /**
     * RED anchor: handled_at orders handled rows by their approval time and
     * keeps the pending NULL row last under ASC (PostgreSQL NULL-largest),
     * so reviewers can put the freshest decisions on top.
     */
    @Test
    void 排序白名单_处理时间升序_未处理空值殿后() {
        long r1 = submit(createCase("先批"), "理由").id();
        long r2 = submit(createCase("后批"), "理由").id();
        long r3 = submit(createCase("待批"), "理由").id();
        deleteRequestService.approve(ownerId, r1);
        deleteRequestService.approve(ownerId, r2);

        List<Long> byHandledAsc = idsOf(deleteRequestService.list(
                projectId, null, 1, 20, "handled_at", "asc"));
        assertEquals(List.of(r1, r2, r3), byHandledAsc,
                "approved rows order by handled time, pending NULL handled_at last");
    }

    /**
     * RED anchor: the legacy four-argument entry, an explicit null pair and a
     * blank pair all keep the historical id DESC default — zero drift for
     * every existing caller of the ledger (api delete_case gateway, admin bridge).
     */
    @Test
    void 排序键缺省_零漂移沿用历史id倒序() {
        long r1 = submit(createCase("一"), "理由").id();
        long r2 = submit(createCase("二"), "理由").id();
        List<Long> expected = List.of(r2, r1);

        assertEquals(expected, idsOf(deleteRequestService.list(projectId, null, 1, 20)));
        assertEquals(expected, idsOf(deleteRequestService.list(projectId, null, 1, 20, null, null)));
        assertEquals(expected, idsOf(deleteRequestService.list(projectId, null, 1, 20, "", "")));
    }

    /**
     * RED anchor: an off-list key and an injection-shaped key are refused
     * fail-closed with a human 400 naming the whitelist, before any database
     * read, and the ledger stays intact after the refusals.
     */
    @Test
    void 排序越列与注入键_400人话点名白名单_数据完好() {
        long r1 = submit(createCase("守住"), "理由").id();

        ServiceException offList = assertThrows(ServiceException.class,
                () -> deleteRequestService.list(projectId, null, 1, 20, "reason", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, offList.getCode());
        assertTrue(offList.getMessage().contains("删除申请"), offList.getMessage());
        assertTrue(offList.getMessage().contains("target_summary"),
                "报错须列出允许键: " + offList.getMessage());

        ServiceException injected = assertThrows(ServiceException.class,
                () -> deleteRequestService.list(projectId, null, 1, 20, "id; DROP TABLE tp_delete_request", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, injected.getCode());

        assertEquals(List.of(r1), idsOf(deleteRequestService.list(projectId, null, 1, 20)),
                "拒绝后台账行完好无损");
    }

    private static List<Long> idsOf(PageResult<DeleteRequestInfo> page) {
        return page.list().stream().map(DeleteRequestInfo::id).toList();
    }
}
