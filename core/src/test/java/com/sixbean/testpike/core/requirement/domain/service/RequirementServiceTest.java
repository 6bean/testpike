package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.events.api.dto.RequirementEventMessage;
import com.sixbean.testpike.core.events.api.dto.RequirementEventType;
import com.sixbean.testpike.core.support.RequirementEventQueueProbe;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementLinkResult;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class RequirementServiceTest {

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project
        // subtrees — blanket TRUNCATE wiped concurrent chains' rows (r13).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private long createCase(long projectId, String title) {
        CaseCreateInput input = new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页",
                1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "页面出现登录成功提示", 1)));
        return caseService.create(1L, input).id();
    }

    private RequirementCreateInput reqInput(long projectId, String title) {
        return new RequirementCreateInput(projectId, 3, "R-" + title, title, "验收标准：" + title, "3.1");
    }


    @Test
    void create_手工创建_source3_状态活跃() {
        long pid = createProject("req1");
        RequirementInfo info = requirementService.create(1L, reqInput(pid, "登录功能"));

        assertNotNull(info.id());
        assertEquals(pid, info.projectId());
        assertEquals(3, info.source(), "手工创建 source=3");
        assertEquals(1, info.status(), "默认活跃");
        assertEquals("登录功能", info.title());
        assertEquals("验收标准：登录功能", info.acceptanceCriteria());
        assertEquals("3.1", info.chapter());
        assertEquals(1L, info.createdBy());
    }


    @Test
    void create_三来源创建_非法source400() {
        long pid = createProject("req2");
        RequirementInfo jira = requirementService.create(1L,
                new RequirementCreateInput(pid, 1, "JIRA-123", "Jira 需求", "c", null));
        RequirementInfo doc = requirementService.create(1L,
                new RequirementCreateInput(pid, 2, "3.2 节", "文档需求", null, "3.2"));
        RequirementInfo manual = requirementService.create(1L, reqInput(pid, "手工需求"));

        assertEquals(1, jira.source());
        assertEquals("JIRA-123", jira.sourceId());
        assertEquals(2, doc.source());
        assertEquals("3.2 节", doc.sourceId());
        assertEquals(3, manual.source());

        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.create(1L, new RequirementCreateInput(pid, 9, null, "非法来源", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void create_空验收标准不阻断() {
        long pid = createProject("req3");
        RequirementInfo info = requirementService.create(1L,
                new RequirementCreateInput(pid, 3, null, "无验收标准需求", null, null));
        assertNotNull(info.id());
        assertEquals("无验收标准需求", info.title());
        assertEquals(null, info.acceptanceCriteria(), "acceptance_criteria 可空");
    }


    @Test
    void create_标题为空400() {
        long pid = createProject("req4");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.create(1L, new RequirementCreateInput(pid, 3, null, "  ", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void list_状态关键词分页筛选() {
        long pid = createProject("req5");
        for (int i = 1; i <= 6; i++) {
            requirementService.create(1L, reqInput(pid, "登录功能" + i));
        }
        requirementService.create(1L, new RequirementCreateInput(pid, 3, null, "支付流程", null, "4.1"));
        requirementService.create(1L, reqInput(pid, "登录功能停用"));
        PageResult<RequirementInfo> all = requirementService.list(new RequirementQuery(pid, null, null, 1, 10));
        assertEquals(8, all.total(), "共 8 条");
        RequirementInfo toDisable = all.list().stream()
                .filter(r -> r.title().equals("登录功能停用")).findFirst().orElseThrow();
        requirementService.update(1L, toDisable.id(), new RequirementUpdateInput(null, null, null, null, null, 3, null));

        PageResult<RequirementInfo> active = requirementService.list(new RequirementQuery(pid, 1, null, 1, 10));
        assertEquals(7, active.total(), "活跃 7 条");
        PageResult<RequirementInfo> down = requirementService.list(new RequirementQuery(pid, 3, null, 1, 10));
        assertEquals(1, down.total(), "已下线 1 条");

        PageResult<RequirementInfo> kw = requirementService.list(new RequirementQuery(pid, null, "支付", 1, 10));
        assertEquals(1, kw.total());
        assertEquals("支付流程", kw.list().get(0).title());

        PageResult<RequirementInfo> p1 = requirementService.list(new RequirementQuery(pid, null, null, 1, 3));
        PageResult<RequirementInfo> p2 = requirementService.list(new RequirementQuery(pid, null, null, 2, 3));
        PageResult<RequirementInfo> p3 = requirementService.list(new RequirementQuery(pid, null, null, 3, 3));
        assertEquals(3, p1.list().size());
        assertEquals(3, p2.list().size());
        assertEquals(2, p3.list().size(), "8 条 3 页：3/3/2");

        // id allow-list : only listed rows come back and the
        // project isolation still applies — ids from another project hit nothing.
        java.util.List<Long> twoIds = java.util.List.of(kw.list().get(0).id(), toDisable.id());
        PageResult<RequirementInfo> byIds = requirementService.list(
                new RequirementQuery(pid, null, null, 1, 10, null, null, twoIds));
        assertEquals(2, byIds.total(), "id 清单 2 条");
        PageResult<RequirementInfo> foreignIds = requirementService.list(
                new RequirementQuery(createProject("req5f"), null, null, 1, 10, null, null, twoIds));
        assertEquals(0, foreignIds.total(), "跨项目 id 必须零命中（H1 隔离）");
        List<Long> ids = new ArrayList<>(p1.list().stream().map(RequirementInfo::id).toList());
        ids.addAll(p2.list().stream().map(RequirementInfo::id).toList());
        ids.addAll(p3.list().stream().map(RequirementInfo::id).toList());
        assertEquals(8, ids.size());
        assertEquals(8, ids.stream().distinct().count(), "无重复无遗漏");
    }

 // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

 @Test
 void test_list_sorts_by_whitelisted_columns_both_directions() {
     long pid = createProject("reqsort");
     // Insertion order deliberately differs from BOTH title orders, so the
     // id-DESC default cannot accidentally satisfy either direction assertion.
     requirementService.create(1L, reqInput(pid, "Zeta"));
     requirementService.create(1L, reqInput(pid, "Alpha"));
     requirementService.create(1L, reqInput(pid, "Mike"));

     PageResult<RequirementInfo> asc = requirementService.list(
             new RequirementQuery(pid, null, null, 1, 20, "title", "asc"));
     assertEquals(3, asc.total());
     assertEquals(List.of("Alpha", "Mike", "Zeta"),
             asc.list().stream().map(RequirementInfo::title).toList(), "asc must reorder server-side");

     PageResult<RequirementInfo> desc = requirementService.list(
             new RequirementQuery(pid, null, null, 1, 20, "title", "desc"));
     assertEquals(List.of("Zeta", "Mike", "Alpha"),
             desc.list().stream().map(RequirementInfo::title).toList(), "desc mirrors asc");

     // Default order (no sort requested) keeps the historical id-DESC behaviour.
     PageResult<RequirementInfo> dflt = requirementService.list(
             new RequirementQuery(pid, null, null, 1, 20));
     assertEquals(List.of("Mike", "Alpha", "Zeta"),
             dflt.list().stream().map(RequirementInfo::title).toList(), "default stays id DESC");
 }

 @Test
 void test_list_sort_by_injection_shaped_key_rejected_400() {
     long pid = createProject("reqsortinj");
     requirementService.create(1L, reqInput(pid, "注入对照"));
     ServiceException ex = assertThrows(ServiceException.class, () ->
             requirementService.list(new RequirementQuery(pid, null, null, 1, 20, "id OR 1=1", "asc")));
     assertTrue(ex.getMessage().contains("不在白名单"), ex.getMessage());
 }


    @Test
    void update_字段级更新_停用联动() {
        long pid = createProject("req6");
        RequirementInfo created = requirementService.create(1L, reqInput(pid, "登录功能"));

        RequirementInfo updated = requirementService.update(1L, created.id(),
                new RequirementUpdateInput("登录功能 v2", null, null, null, "5.2", 3, null));
        assertEquals("登录功能 v2", updated.title());
        assertEquals("5.2", updated.chapter());
        assertEquals(3, updated.status(), "停用联动 status=3");
        assertEquals(created.source(), updated.source(), "未更新字段不变");
        assertEquals(created.acceptanceCriteria(), updated.acceptanceCriteria(), "未更新字段不变");
    }


    @Test
    void linkCases_多对多关联_互查() {
        long pid = createProject("req7");
        RequirementInfo r1 = requirementService.create(1L, reqInput(pid, "登录功能"));
        RequirementInfo r2 = requirementService.create(1L, reqInput(pid, "注册功能"));
        long c1 = createCase(pid, "登录成功用例");
        long c2 = createCase(pid, "注册成功用例");

        RequirementLinkResult link1 = requirementService.linkCases(1L, r1.id(), List.of(c1, c2));
        assertEquals(2, link1.linkedCount());
        requirementService.linkCases(1L, r2.id(), List.of(c2));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?", Integer.class, r1.id());
        assertEquals(2, rows);

        List<RequirementInfo> ofC2 = requirementService.listByCaseId(c2);
        assertEquals(2, ofC2.size(), "c2 关联 2 个需求点");
        List<RequirementInfo> ofC1 = requirementService.listByCaseId(c1);
        assertEquals(1, ofC1.size());
        assertEquals(r1.id(), ofC1.get(0).id());
    }


    @Test
    void listCasesByRequirementId_需求点查关联用例() {
        long pid = createProject("req7b");
        RequirementInfo r1 = requirementService.create(1L, reqInput(pid, "登录功能"));
        RequirementInfo r2 = requirementService.create(1L, reqInput(pid, "注册功能"));
        long c1 = createCase(pid, "登录成功用例");
        long c2 = createCase(pid, "注册成功用例");
        requirementService.linkCases(1L, r1.id(), List.of(c1, c2));
        requirementService.linkCases(1L, r2.id(), List.of(c2));

        List<CaseInfo> casesOfR1 = requirementService.listCasesByRequirementId(r1.id());
        assertEquals(2, casesOfR1.size(), "r1 关联 2 条用例");
        assertTrue(casesOfR1.stream().anyMatch(c -> c.id() == c1 && c.title().equals("登录成功用例")));
        assertTrue(casesOfR1.stream().anyMatch(c -> c.id() == c2 && c.title().equals("注册成功用例")));

        List<CaseInfo> casesOfR2 = requirementService.listCasesByRequirementId(r2.id());
        assertEquals(1, casesOfR2.size());
        assertEquals(c2, casesOfR2.get(0).id());

        RequirementInfo r3 = requirementService.create(1L, reqInput(pid, "未关联需求"));
        assertEquals(0, requirementService.listCasesByRequirementId(r3.id()).size());
    }


    @Test
    void linkCases_重复关联幂等() {
        long pid = createProject("req8");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c = createCase(pid, "登录成功用例");

        requirementService.linkCases(1L, r.id(), List.of(c));
        RequirementLinkResult again = requirementService.linkCases(1L, r.id(), List.of(c));

        assertEquals(0, again.linkedCount(), "重复关联不重复插入");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?", Integer.class, r.id());
        assertEquals(1, rows, "UNIQUE(case_id, requirement_id) 幂等兜底");
    }


    @Test
    void linkCases_跨项目用例400() {
        long pidA = createProject("req9a");
        long pidB = createProject("req9b");
        RequirementInfo r = requirementService.create(1L, reqInput(pidA, "A 项目需求"));
        long caseB = createCase(pidB, "B 项目用例");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.linkCases(1L, r.id(), List.of(caseB)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不属于"), "人话提示: " + ex.getMessage());
    }


    @Test
    void delete_被关联需求点409() {
        long pid = createProject("req10");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c = createCase(pid, "登录成功用例");
        requirementService.linkCases(1L, r.id(), List.of(c));

        ServiceException ex = assertThrows(ServiceException.class, () -> requirementService.delete(1L, r.id()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        // Three-element guidance (error-state contract): cause names the linkage,
        // the corrective step names the on-screen panel and the offline status path.
        assertTrue(ex.getMessage().contains("已被用例关联"), "点名冲突原因: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("取消关联"), "给出取消关联入口: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("已下线"), "给出状态替代路径: " + ex.getMessage());

        assertNotNull(requirementService.get(r.id()));
    }


    @Test
    void delete_未关联成功_审计记录() {
        long pid = createProject("req11");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));

        long deleted = requirementService.delete(1L, r.id());
        assertEquals(r.id(), deleted);

        PageResult<RequirementInfo> all = requirementService.list(new RequirementQuery(pid, null, null, 1, 10));
        assertEquals(0, all.total());
        ServiceException ex = assertThrows(ServiceException.class, () -> requirementService.get(r.id()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'requirement' AND action = 'delete' AND object_id = ?",
                Integer.class, String.valueOf(r.id()));
        assertEquals(1, auditRows, "审计记录");
    }

    // Soft delete must announce requirement.changed so the knowledge-base ingest
    // chain retires the entry: the event carries the post-bump version
    // (UPDATE bumps version = current + 1, same value the delete snapshot pins).
    @Test
    void delete_publishes_changed_event_with_bumped_version() {
        long pid = createProject("req11evt");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "下线事件需求"));
        RequirementEventQueueProbe probe =
                new RequirementEventQueueProbe(amqpAdmin, rabbitTemplate, caseEventMessageConverter);
        Queue queue = probe.bind(RequirementEventType.CHANGED);

        requirementService.delete(1L, r.id());

        RequirementEventMessage evt = probe.await(queue, 5000);
        assertNotNull(evt, "soft delete must publish requirement.changed for knowledge-base retire");
        assertEquals(RequirementEventType.CHANGED.name(), evt.eventType());
        assertEquals(r.id(), evt.requirementId());
        assertEquals(pid, evt.projectId(), "projectId travels with every event");
        assertEquals(r.version() + 1, evt.requirementVersion(),
                "event version = post-delete bumped version (consumer idempotency key source)");
    }


    @Test
    void listByCaseId_含停用需求点() {
        long pid = createProject("req12");
        RequirementInfo active = requirementService.create(1L, reqInput(pid, "活跃需求"));
        RequirementInfo willDisable = requirementService.create(1L, reqInput(pid, "停用需求"));
        long c = createCase(pid, "登录成功用例");
        requirementService.linkCases(1L, active.id(), List.of(c));
        requirementService.linkCases(1L, willDisable.id(), List.of(c));

        requirementService.update(1L, willDisable.id(),
                new RequirementUpdateInput(null, null, null, null, null, 3, null));

        List<RequirementInfo> list = requirementService.listByCaseId(c);
        assertEquals(2, list.size(), "停用需求点仍可见（不计覆盖统计留 Phase 19）");
        assertTrue(list.stream().anyMatch(r -> r.status() == 3), "含已下线需求点");
        assertEquals(active.id(), list.get(0).id(), "按创建时间升序");
        // Case-detail "linked requirements" block reads through this query only;
        // the replay-stable fingerprint must come back with the row or the screen
        // loses the identity column (field report: link rows exist, UI shows empty).
        assertNotNull(active.fingerprint(), "created requirement carries fingerprint");
        assertEquals(active.fingerprint(), list.get(0).fingerprint(), "关联查询回显 fingerprint");
    }


    @Test
    void unlinkCase_取消关联_互查不可见() {
        long pid = createProject("req13");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c = createCase(pid, "登录成功用例");
        requirementService.linkCases(1L, r.id(), List.of(c));

        requirementService.unlinkCase(1L, r.id(), c);
        assertEquals(0, requirementService.listByCaseId(c).size(), "用例侧不再可见");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?", Integer.class, r.id());
        assertEquals(0, rows);

        requirementService.unlinkCase(1L, r.id(), c);
    }


    
    @Test
    void unlinkCase_单行原子_兄弟关联零互踩() {
        long pid = createProject("t126a");
        RequirementInfo r1 = requirementService.create(1L, reqInput(pid, "登录功能"));
        RequirementInfo r2 = requirementService.create(1L, reqInput(pid, "注册功能"));
        long c1 = createCase(pid, "用例甲");
        long c2 = createCase(pid, "用例乙");
        requirementService.linkCases(1L, r1.id(), List.of(c1, c2));
        requirementService.linkCases(1L, r2.id(), List.of(c1));

        requirementService.unlinkCase(1L, r1.id(), c1);

        assertEquals(0, countLinkRows(r1.id(), c1));
        assertEquals(1, countLinkRows(r1.id(), c2), "同需求点其他用例关联零互踩");
        assertEquals(1, countLinkRows(r2.id(), c1), "同用例其他需求点关联零互踩");
        List<RequirementInfo> c1Reqs = requirementService.listByCaseId(c1);
        assertEquals(1, c1Reqs.size(), "用例侧取消后仅剩 r2");
        assertEquals(r2.id(), c1Reqs.get(0).id());
    }

    
    @Test
    void unlinkCase_无关联行_幂等空操作影响行数零() {
        long pid = createProject("t126b");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c = createCase(pid, "登录成功用例");
        requirementService.linkCases(1L, r.id(), List.of(c));

        requirementService.unlinkCase(1L, r.id(), c);
        assertEquals(0, countLinkRows(r.id(), c));

        assertDoesNotThrow(() -> requirementService.unlinkCase(1L, r.id(), c));
        assertEquals(0, countLinkRows(r.id(), c));
    }

    private int countLinkRows(long requirementId, long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ? AND case_id = ?",
                Integer.class, requirementId, caseId);
        return n == null ? 0 : n;
    }


    
    @Test
    void linkCases_成功_审计link留痕() {
        long pid = createProject("req16");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c1 = createCase(pid, "用例甲");
        long c2 = createCase(pid, "用例乙");

        requirementService.linkCases(1L, r.id(), List.of(c1, c2));

        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'requirement' AND action = 'link' AND object_id = ?",
                Integer.class, String.valueOf(r.id()));
        assertEquals(1, auditRows, "link 审计恰好 1 条");
        String afterJson = jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log WHERE object_type = 'requirement' AND action = 'link' AND object_id = ?",
                String.class, String.valueOf(r.id()));
        assertNotNull(afterJson, "after_json 非空（审计含结果快照）");
        assertTrue(afterJson.contains("caseIds"), "after 含 caseIds: " + afterJson);
        assertTrue(afterJson.contains(String.valueOf(c1)) && afterJson.contains(String.valueOf(c2)),
                "after 含全部关联用例 ID: " + afterJson);
        assertTrue(afterJson.contains("linkedCount"), "after 含 linkedCount: " + afterJson);
    }

 
    @Test
    void unlinkCase_审计unlink留痕() {
        long pid = createProject("req17");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        long c = createCase(pid, "登录成功用例");
        requirementService.linkCases(1L, r.id(), List.of(c));

        requirementService.unlinkCase(1L, r.id(), c);

        Integer unlinkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'requirement' AND action = 'unlink' AND object_id = ?",
                Integer.class, String.valueOf(r.id()));
        assertEquals(1, unlinkRows, "unlink 审计留痕（与 link 对等）");
    }

 
    @Test
    void linkCases_跨项目400_审计记录失败不静默() {
        long pidA = createProject("req18");
        long pidB = createProject("req19");
        RequirementInfo r = requirementService.create(1L, reqInput(pidA, "登录功能"));
        long caseB = createCase(pidB, "他项目用例");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.linkCases(1L, r.id(), List.of(caseB)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());

        Integer failRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'requirement' AND action = 'link'"
                        + " AND object_id = ? AND after_json::text LIKE '%error%'",
                Integer.class, String.valueOf(r.id()));
        assertEquals(1, failRows, "失败路径审计不丢失（REQUIRES_NEW 独立事务）");
        Integer assoc = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?", Integer.class, r.id());
        assertEquals(0, assoc, "整笔拒绝零半生效（回滚后无关联行）");
    }


    @Test
    void linkCases_用例不存在404() {
        long pid = createProject("req14");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.linkCases(1L, r.id(), List.of(999_999L)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void linkCases_空列表400() {
        long pid = createProject("req15");
        RequirementInfo r = requirementService.create(1L, reqInput(pid, "登录功能"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                requirementService.linkCases(1L, r.id(), List.of()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void get_不存在404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> requirementService.get(999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void offlineCaseIds_仅返回关联已下线需求点的用例() {
        long pid = createProject("req20a");
        RequirementInfo off = requirementService.create(1L, reqInput(pid, "下线需求"));
        RequirementInfo on = requirementService.create(1L, reqInput(pid, "活跃需求"));
        long cBoth = createCase(pid, "双关联用例");
        long cOnlyOn = createCase(pid, "仅活跃用例");
        long cNoLink = createCase(pid, "无关联用例");
        requirementService.linkCases(1L, off.id(), List.of(cBoth));
        requirementService.linkCases(1L, on.id(), List.of(cBoth, cOnlyOn));
        requirementService.update(1L, off.id(), new RequirementUpdateInput(
                null, null, null, null, null, 3, null));

        List<Long> hit = requirementService.offlineCaseIds(pid, List.of(cBoth, cOnlyOn, cNoLink, 999_999L));

        assertEquals(List.of(cBoth), hit, "仅 cBoth 关联已下线需求点（不重复、无关联/他项目 id 不计）");
    }

    @Test
    void offlineCaseIds_空入参短路() {
        assertEquals(List.of(), requirementService.offlineCaseIds(1L, List.of()), "空 caseIds 短路空集");
    }
}
