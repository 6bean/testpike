package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.AssignBatchResult;
import com.sixbean.testpike.core.cases.api.dto.AssignResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseServiceTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanCaseTables() {
        // Fixture-scoped teardown : the training-sample purge consumes
        // the shared single source, then only rows reachable from projects
        // this suite created are swept so concurrent suites sharing the dev
        // database keep their own data.
        SharedDbFixtureSweep.purgeTrainingSamples(jdbcTemplate);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                List.of("tp_export_task"), projectIds);
    }

    /** Project ids created by this run; drained by the sweep on every hook. */
    private final List<Long> projectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private List<CaseStepInput> steps(String action, String assertion) {
        return List.of(
                new CaseStepInput(1, action, 0),
                new CaseStepInput(2, assertion, 1));
    }

    private CaseCreateInput input(long projectId, String title, int priority, int confidence, String endType) {
        return input(projectId, title, priority, confidence, endType, 1);
    }

    private CaseCreateInput input(long projectId, String title, int priority, int confidence,
                                  String endType, int status) {
        return new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页", priority, confidence, endType, status, null, 1, null, steps("输入账号密码", "页面出现登录成功提示"));
    }


    @Test
    void create_生成TC格式编号_步骤聚合stepsText_字段齐全() {
        long pid = createProject("rtl");
        CaseInfo info = caseService.create(1L, input(pid, "登录功能测试", 1, 2, "web"));

        assertTrue(info.caseNo().matches("TC-rtl-\\d{3,}"), "ID 格式 TC-rtl-001: " + info.caseNo());
        assertEquals(pid, info.projectId());
        assertEquals("登录功能测试", info.title());
        assertEquals(1, info.priority());
        assertEquals(2, info.confidence());
        assertEquals("web", info.endType());
        assertEquals(1, info.status(), "默认待评审");
        assertEquals(1, info.version(), "初始版本 1");
        assertEquals(1L, info.createdBy());
        assertNotNull(info.steps());
        assertEquals(2, info.steps().size(), "步骤落库");
        assertEquals("输入账号密码", info.steps().get(0).description());

        String stepsText = jdbcTemplate.queryForObject(
                "SELECT steps_text FROM tp_test_case WHERE id = ?", String.class, info.id());
        assertNotNull(stepsText);
        assertTrue(stepsText.contains("输入账号密码"), "steps_text 聚合步骤: " + stepsText);
    }


    @Test
    void create_同项目序号递增_不同项目互不干扰() {
        long pid = createProject("rtl");
        long pid2 = createProject("abc");
        assertEquals("TC-rtl-001", caseService.create(1L, input(pid, "A", 1, 2, "web")).caseNo());
        assertEquals("TC-rtl-002", caseService.create(1L, input(pid, "B", 1, 2, "web")).caseNo());
        assertEquals("TC-abc-001", caseService.create(1L, input(pid2, "C", 1, 2, "web")).caseNo());
        caseService.delete(1L, caseService.create(1L, input(pid, "D", 1, 2, "web")).id());
        assertEquals("TC-rtl-004", caseService.create(1L, input(pid, "E", 1, 2, "web")).caseNo());
    }


    @Test
    void create_项目不存在_40401() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(99999L, "孤儿用例", 1, 2, "web")));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "项目不存在 404");
    }

    @Test
    void create_标题为空_40001() {
        long pid = createProject("rtl");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "  ", 1, 2, "web")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void create_优先级越界_置信度越界_端类型非法_40001() {
        long pid = createProject("rtl");
        assertThrows(ServiceException.class, () -> caseService.create(1L, input(pid, "X", 9, 2, "web")));
        assertThrows(ServiceException.class, () -> caseService.create(1L, input(pid, "X", 1, 9, "web")));
        assertThrows(ServiceException.class, () -> caseService.create(1L, input(pid, "X", 1, 2, "pc")));
    }

    @Test
    void create_步骤描述为空_40001() {
        long pid = createProject("rtl");
        CaseCreateInput bad = new CaseCreateInput(pid, "坏步骤", "前置", "预期", 1, 2, "web", 1, null, 1, null, List.of(new CaseStepInput(1, "  ", 0)));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.create(1L, bad));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void update_字段级更新_只改优先级_其他不变() {
        long pid = createProject("rtl");
        CaseInfo created = caseService.create(1L, input(pid, "原标题", 0, 1, "mobile"));

        CaseInfo updated = caseService.update(1L, created.id(),
                new CaseUpdateInput(null, null, null, 2, null, null, null, null), created.version());

        assertEquals(2, updated.priority(), "priority 已更新");
        assertEquals("原标题", updated.title(), "title 未变（字段级）");
        assertEquals(1, updated.confidence(), "confidence 未变");
        assertEquals("mobile", updated.endType(), "endType 未变");
        assertEquals(created.version() + 1, updated.version(), "version+1（乐观锁）");
        assertEquals(2, updated.steps().size(), "步骤未动");
    }


    @Test
    void update_乐观锁冲突_后提交者40901() {
        long pid = createProject("rtl");
        CaseInfo created = caseService.create(1L, input(pid, "并发用例", 1, 2, "web"));

        caseService.update(1L, created.id(),
                new CaseUpdateInput("甲改的标题", null, null, null, null, null, null, null), created.version());
        OptimisticLockingException ex = assertThrows(OptimisticLockingException.class,
                () -> caseService.update(1L, created.id(),
                        new CaseUpdateInput("乙改的标题", null, null, null, null, null, null, null), created.version()));
        assertTrue(ex.getMessage().contains("版本冲突"), ex.getMessage());

        CaseInfo after = caseService.get(created.id());
        assertEquals("甲改的标题", after.title(), "后提交者不得覆盖");
        assertEquals(2, after.version());
    }

    @Test
    void update_版本号非法_40001() {
        long pid = createProject("rtl");
        CaseInfo created = caseService.create(1L, input(pid, "V", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.update(1L, created.id(), new CaseUpdateInput("x", null, null, null, null, null, null, null), 0));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void update_不存在_40401() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.update(1L, 99999L, new CaseUpdateInput("x", null, null, null, null, null, null, null), 1));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void update_步骤整体替换_聚合文本更新() {
        long pid = createProject("rtl");
        CaseInfo created = caseService.create(1L, input(pid, "步骤替换", 1, 2, "web"));

        CaseInfo updated = caseService.update(1L, created.id(),
                new CaseUpdateInput(null, null, null, null, null, null, null, List.of(new CaseStepInput(1, "第一步替换", 0))),
                created.version());

        assertEquals(1, updated.steps().size());
        assertEquals("第一步替换", updated.steps().get(0).description());
        String stepsText = jdbcTemplate.queryForObject(
                "SELECT steps_text FROM tp_test_case WHERE id = ?", String.class, created.id());
        assertTrue(stepsText.contains("第一步替换"), "steps_text 随替换更新: " + stepsText);
        assertFalse(stepsText.contains("输入账号密码"), "旧步骤文本已清除");
    }


    @Test
    void list_状态筛选_只返回该状态() {
        long pid = createProject("rtl");
        caseService.create(1L, input(pid, "待评审A", 0, 2, "web", 1));   // status=1
        caseService.create(1L, input(pid, "待评审B", 1, 2, "web", 1));
        caseService.create(1L, input(pid, "通过C", 2, 2, "web", 3));     // status=3
        caseService.create(1L, input(pid, "通过D", 1, 2, "web", 3));
        caseService.create(1L, input(pid, "拒绝E", 2, 2, "web", 5));     // status=5

        PageResult<CaseInfo> r = caseService.list(new CaseQuery(pid, 3, null, null, null, null, null, null, null, null, 1, 10));
        assertEquals(2, r.total(), "status=3 只返回 2 条");
        assertTrue(r.list().stream().allMatch(c -> c.status() == 3), "全部为已通过");
        assertTrue(r.list().stream().allMatch(c -> c.title().startsWith("通过")), "无其他状态混入");
    }

    @Test
    void list_多条件组合_交集正确() {
        long pid = createProject("rtl");
        caseService.create(1L, input(pid, "A-P0高", 0, 1, "web"));
        caseService.create(1L, input(pid, "B-P0中", 0, 2, "web"));
        caseService.create(1L, input(pid, "C-P1高", 1, 1, "web"));
        caseService.create(1L, input(pid, "D-P1中", 1, 2, "web"));

        PageResult<CaseInfo> r = caseService.list(new CaseQuery(pid, null, null, 0, 1, null, null, null, null, null, 1, 10));
        assertEquals(1, r.total(), "priority=0 且 confidence=1 只 1 条");
        assertEquals("A-P0高", r.list().get(0).title());
    }


    @Test
    void list_关键词_命中标题或步骤() {
        long pid = createProject("rtl");
        caseService.create(1L, new CaseCreateInput(pid, "登录功能", "打开首页", "登录成功", 1, 2, "web", 1, null, 1, null, steps("输入账号密码", "登录成功提示")));
        caseService.create(1L, new CaseCreateInput(pid, "注册功能", "打开注册页", "注册成功", 1, 2, "web", 1, null, 1, null, List.of(new CaseStepInput(1, "输入验证码", 0),
                new CaseStepInput(2, "注册成功提示", 1))));
        caseService.create(1L, new CaseCreateInput(pid, "订单查询", "打开订单页", "查询成功", 1, 2, "web", 1, null, 1, null, steps("输入订单号", "订单列表展示")));

        PageResult<CaseInfo> hitTitle = caseService.list(new CaseQuery(pid, null, "登录", null, null, null, null, null, null, null, 1, 10));
        assertEquals(1, hitTitle.total(), "标题命中");

        PageResult<CaseInfo> hitStep = caseService.list(new CaseQuery(pid, null, "验证码", null, null, null, null, null, null, null, 1, 10));
        assertEquals(1, hitStep.total(), "步骤命中（steps_text 聚合列 LIKE）");
        assertEquals("注册功能", hitStep.list().get(0).title());
    }


    @Test
    void list_分页_两页无重复无遗漏() {
        long pid = createProject("rtl");
        Set<Long> allIds = new HashSet<>();
        for (int i = 1; i <= 30; i++) {
            allIds.add(caseService.create(1L, input(pid, "T" + String.format("%02d", i),
                    i % 3, 2, "web")).id());
        }

        PageResult<CaseInfo> p1 = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 1, 20));
        PageResult<CaseInfo> p2 = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 2, 20));

        assertEquals(30L, p1.total());
        assertEquals(20, p1.list().size());
        assertEquals(10, p2.list().size());

        Set<Long> p1Ids = p1.list().stream().map(CaseInfo::id).collect(Collectors.toSet());
        Set<Long> p2Ids = p2.list().stream().map(CaseInfo::id).collect(Collectors.toSet());
        assertTrue(p1Ids.stream().noneMatch(p2Ids::contains), "两页无重复");
        Set<Long> union = new HashSet<>(p1Ids);
        union.addAll(p2Ids);
        assertEquals(allIds, union, "两页并集=全量，无遗漏");
    }


    @Test
    void list_排序_优先级升序_置信度升序_生成日期倒序() {
        long pid = createProject("rtl");
        caseService.create(1L, new CaseCreateInput(pid, "X1", null, null, 0, 1, "web", 1, null, 1, null, steps("a", "b")));
        caseService.create(1L, new CaseCreateInput(pid, "X2", null, null, 2, 3, "web", 1, null, 1, null, steps("a", "b")));
        caseService.create(1L, new CaseCreateInput(pid, "X3", null, null, 1, 2, "web", 1, null, 1, null, steps("a", "b")));

        PageResult<CaseInfo> byPriority = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "priority", "asc", 1, 10));
        assertEquals(List.of(0, 1, 2), byPriority.list().stream().map(CaseInfo::priority).toList(),
                "优先级升序");

        PageResult<CaseInfo> byConfidence = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "confidence", "asc", 1, 10));
        assertEquals(List.of(1, 2, 3), byConfidence.list().stream().map(CaseInfo::confidence).toList(),
                "置信度升序");

        PageResult<CaseInfo> byCreated = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "created_at", "desc", 1, 10));
        assertEquals(3, byCreated.list().size());
        long first = byCreated.list().get(0).id();
        long last = byCreated.list().get(2).id();
        assertTrue(first > last, "生成日期倒序（后创建的在前）");
    }

    @Test
    void list_非法排序字段_回退默认不报错() {
        long pid = createProject("rtl");
        caseService.create(1L, input(pid, "A", 1, 2, "web"));
        PageResult<CaseInfo> r = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "id;drop table", "asc", 1, 10));
        assertEquals(1, r.total(), "非法排序字段回退默认（防 SQL 注入）");
    }


    @Test
    void list_排序_用例编号升序() {
        long pid = createProject("rtl");
        caseService.create(1L, input(pid, "A", 1, 2, "web"));
        caseService.create(1L, input(pid, "B", 1, 2, "web"));
        caseService.create(1L, input(pid, "C", 1, 2, "web"));

        PageResult<CaseInfo> byNo = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "case_no", "asc", 1, 10));
        List<String> nos = byNo.list().stream().map(CaseInfo::caseNo).toList();
        assertEquals(List.of("TC-rtl-001", "TC-rtl-002", "TC-rtl-003"), nos, "用例编号升序");
    }

    @Test
    void list_排序_更新时间倒序() {
        long pid = createProject("rtl");
        long c1 = caseService.create(1L, input(pid, "A", 1, 2, "web")).id();
        long c2 = caseService.create(1L, input(pid, "B", 1, 2, "web")).id();
        caseService.update(1L, c1, new CaseUpdateInput(null, "A2", null, null, null, null, null, null), 1);

        PageResult<CaseInfo> byUpdated = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, "updated_at", "desc", 1, 10));
        assertEquals(2, byUpdated.list().size());
        assertEquals(c1, byUpdated.list().get(0).id(), "更新时间倒序：后更新的在前（不因创建更早而排后）");
        assertEquals(c2, byUpdated.list().get(1).id());
    }


    @Test
    void list_深分页_不报错返回空() {
        long pid = createProject("rtl");
        caseService.create(1L, input(pid, "A", 1, 2, "web"));
        PageResult<CaseInfo> r = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 10000, 20));
        assertNotNull(r);
        assertEquals(1L, r.total(), "total 不受深分页影响");
        assertTrue(r.list().isEmpty(), "深分页返回空列表（offset 保护）");
    }


    @Test
    void list_size1000_返回1000条_不钳100() {
        long pid = createProject("rtl");
        seedCases(pid, 1005);

        PageResult<CaseInfo> r = caseService.list(
                new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 1, 1000));
        assertEquals(1000, r.list().size(), "size=1000 必须返回 1000 条（旧上限 100 会截断，TC-O4-01 前提）");
        assertEquals(1005L, r.total(), "total 不受 size 影响");
    }

    @Test
    void list_size超1000_钳制到1000() {
        long pid = createProject("rtl");
        seedCases(pid, 1005);

        PageResult<CaseInfo> r = caseService.list(
                new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 1, 2000));
        assertEquals(1000, r.list().size(), "超限钳制 1000（防超大请求拖垮 DB）");
    }

    
    private void seedCases(long projectId, int n) {
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (case_no, project_id, title, priority, confidence, end_type,
                                          status, source, steps_text, deleted, created_by)
                SELECT 'VRT-' || project_id || '-' || g, project_id, '虚拟滚动用例-' || g,
                       1, 2, 'web', 1, 1, '步骤' || g, 0, 1
                FROM generate_series(1, ?) AS g, (SELECT ?::bigint AS project_id) p
                """, n, projectId);
    }


    @Test
    void delete_软删_列表不可见_详情404_审计记录() {
        long pid = createProject("rtl");
        CaseInfo created = caseService.create(1L, input(pid, "待删用例", 1, 2, "web"));

        caseService.delete(1L, created.id());

        PageResult<CaseInfo> list = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 1, 10));
        assertEquals(0, list.total());
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.get(created.id()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_test_case WHERE id = ?", Integer.class, created.id());
        assertEquals(1, deleted);
        Long auditCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'case' AND object_id = ?",
                Long.class, String.valueOf(created.id()));
        assertEquals(2L, auditCnt, "create + delete 各一条审计");
    }

    @Test
    void delete_不存在_40401() {
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.delete(1L, 99999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }



    @Test
    void review_待评审_pass_已通过_版本加一() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "登录评审", 1, 2, "web"));

        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());

        assertEquals(c.id(), r.caseId());
        assertEquals(3, r.status(), "已通过");
        assertEquals(c.version() + 1, r.version(), "version+1");
        CaseInfo after = caseService.get(c.id());
        assertEquals(3, after.status());
        assertEquals(c.version() + 1, after.version());
    }


    @Test
    void review_待评审_needChange_需修改_评审记录落库() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "支付用例", 1, 2, "web"));

        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.NEED_CHANGE, "缺少异常分支覆盖",
                c.version(), List.of("logic_missing"));

        assertEquals(4, r.status(), "需修改");
        List<Map<String, Object>> logs = jdbcTemplate.queryForList(
                "SELECT action, remark, user_id FROM tp_review_log WHERE case_id = ?", c.id());
        assertEquals(1, logs.size());
        assertEquals("需修改", logs.get(0).get("action"));
        assertEquals("缺少异常分支覆盖", logs.get(0).get("remark"));
        assertEquals(1L, logs.get(0).get("user_id"));
    }

    @Test
    void review_待评审_reject_已拒绝_评审记录落库() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "场景用例", 1, 2, "web"));

        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.REJECT, "超出 M1 范围",
                c.version(), List.of("logic_missing"));

        assertEquals(5, r.status(), "已拒绝");
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ? AND action = '拒绝'",
                Long.class, c.id());
        assertEquals(1L, cnt);
    }


    @Test
    void review_评审中_pass_已通过() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "正式评审", 1, 2, "web"));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 2 WHERE id = ?", c.id());

        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());

        assertEquals(3, r.status());
        assertEquals(c.version() + 1, r.version());
    }


    @Test
    void review_已通过_再评审_40902终态拦截() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "终态用例", 1, 2, "web"));
        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.REJECT, "想拒绝", r.version()));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertEquals(true, ex.getMessage().contains("终态"), "终态提示：%s".formatted(ex.getMessage()));
    }


    @Test
    void review_需修改_再评审_40902() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "修改池用例", 1, 2, "web"));
        ReviewResult r = caseService.review(1L, c.id(), ReviewAction.NEED_CHANGE, "补充边界",
                c.version(), List.of("logic_missing"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.PASS, null, r.version()));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
    }


    @Test
    void review_并发评审_无双成功_后提交者被拦截() throws Exception {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "并发评审", 1, 2, "web"));

        int n = 2;
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Integer> outcomes = Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            threads[i] = new Thread(() -> {
                try {
                    barrier.await();
                    caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());
                    outcomes.add(0);
                } catch (Throwable t) {
                    if (t instanceof OptimisticLockingException) {
                        outcomes.add(40901);
                    } else if (t instanceof ServiceException se
                            && se.getCode() == BizErrorCode.STATE_MACHINE_REJECT) {
                        outcomes.add(40902);
                    } else {
                        outcomes.add(-1);
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join(5000);
        }

        assertEquals(2, outcomes.size(), "两个线程都有明确结果");
        assertEquals(1L, outcomes.stream().filter(o -> o == 0).count(), "恰好一个评审成功（无双成功）");
        assertTrue(outcomes.stream().allMatch(o -> o == 0 || o == 40901 || o == 40902),
                "另一个被拦截：40901 版本冲突或 40902 状态拦截，实际=" + outcomes);
        assertEquals(3, caseService.get(c.id()).status(), "终态=已通过");
        Long logCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ?", Long.class, c.id());
        assertEquals(1L, logCnt, "评审记录唯一（无双成功）");
    }


    @Test
    void review_needChange_无备注_40001() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "备注必填", 1, 2, "web"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.NEED_CHANGE, "  ", c.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals(true, ex.getMessage().contains("评审意见"), "提示人话：%s".formatted(ex.getMessage()));
    }

    @Test
    void review_reject_无备注_40001() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "拒绝必填", 1, 2, "web"));

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.REJECT, null, c.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void reviewAction_非法动作_40001() {
        ServiceException ex = assertThrows(ServiceException.class, () -> ReviewAction.from("approve"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void review_审计记录_actionReview() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "审计评审", 1, 2, "web"));

        caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT action FROM tp_audit_log WHERE object_type = 'case' AND object_id = ? ORDER BY id",
                String.valueOf(c.id()));
        List<String> actions = audits.stream().map(m -> String.valueOf(m.get("action"))).toList();
        assertEquals(List.of("create", "review"), actions, "create + review 各一条审计");
    }


    @Test
    void list_修改池筛选_status4_只返回需修改() {
        long pid = createProject("rvw");
        CaseInfo pass = caseService.create(1L, input(pid, "通过用例", 1, 2, "web"));
        CaseInfo fix = caseService.create(1L, input(pid, "修改用例", 1, 2, "web"));
        caseService.review(1L, pass.id(), ReviewAction.PASS, null, pass.version());
        caseService.review(1L, fix.id(), ReviewAction.NEED_CHANGE, "需补充断言", fix.version(),
                List.of("logic_missing"));

        PageResult<CaseInfo> pool = caseService.list(new CaseQuery(pid, 4, null, null, null, null, null, null, null, null, 1, 10));
        assertEquals(1L, pool.total(), "修改池只有 1 条");
        assertEquals(fix.id(), pool.list().get(0).id(), "需修改用例在修改池");
        PageResult<CaseInfo> approved = caseService.list(new CaseQuery(pid, 3, null, null, null, null, null, null, null, null, 1, 10));
        assertEquals(1L, approved.total());
        assertEquals(pass.id(), approved.list().get(0).id());
    }


    @Test
    void review_不存在用例_40401() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, 99999L, ReviewAction.PASS, null, 1));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void review_version小于1_40001() {
        long pid = createProject("rvw");
        CaseInfo c = caseService.create(1L, input(pid, "版本校验", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.PASS, null, 0));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    
    private void addMember(long projectId, long userId) {
        CaseTestMembers.addMember(jdbcTemplate, projectId, userId);
    }

    
    private void addMemberWithRole(long projectId, long userId, String roleCode) {
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userId, roleCode);
    }

 
    private void removeMember(long projectId, long userId) {
        CaseTestMembers.removeMember(jdbcTemplate, projectId, userId);
    }

    private Long assigneeOf(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT assignee_id FROM tp_test_case WHERE id = ?", Long.class, caseId);
    }

    
    private List<Long> createCases(long pid, int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(caseService.create(1L, input(pid, "分配用例" + i, 1, 2, "web")).id());
        }
        return ids;
    }


    @Test
    void assign_单条分配_assignee更新状态版本不变() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        CaseInfo c = caseService.create(1L, input(pid, "单条分配", 1, 2, "web"));

        AssignResult r = caseService.assign(1L, c.id(), 10L);

        assertEquals(c.id(), r.caseId());
        assertEquals(10L, r.assigneeId());
        assertEquals(1, r.status(), "状态不变（待评审）");
        assertEquals(10L, assigneeOf(c.id()), "assignee_id 落库");
        assertEquals(1, caseService.get(c.id()).version(), "分配不改 version（TC-ASN-01）");
    }


    @Test
    void assign_改分配_assignee更新为新成员() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        addMember(pid, 11L);
        CaseInfo c = caseService.create(1L, input(pid, "改分配", 1, 2, "web"));

        caseService.assign(1L, c.id(), 10L);
        AssignResult r2 = caseService.assign(1L, c.id(), 11L);

        assertEquals(11L, r2.assigneeId());
        assertEquals(11L, assigneeOf(c.id()), "改分配后为新成员");
    }


    @Test
    void assign_已通过用例_40001() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        CaseInfo c = caseService.create(1L, input(pid, "已通过", 1, 2, "web"));
        caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, c.id(), 10L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("待评审"), "人话提示：%s".formatted(ex.getMessage()));
    }


    @Test
    void assign_不存在用例_40401() {
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, 99999L, 10L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void assign_非项目成员_40301() {
        long pid = createProject("asn");
        CaseInfo c = caseService.create(1L, input(pid, "非成员", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, c.id(), 99L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("该用户不是本项目成员"), "人话提示：%s".formatted(ex.getMessage()));
    }

    @Test
    void assign_跨项目成员_40301() {
        long pidA = createProject("k6a");
        long pidB = createProject("k6b");
        addMember(pidA, 10L);
        addMember(pidB, 11L);
        CaseInfo c = caseService.create(1L, input(pidA, "跨项目分配", 1, 2, "web"));

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, c.id(), 11L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("该用户不是本项目成员"), "人话提示：%s".formatted(ex.getMessage()));
    }


    @Test
    void assign_开发人员_40301() {
        long pid = createProject("k6c");
        addMemberWithRole(pid, 12L, "developer");
        CaseInfo c = caseService.create(1L, input(pid, "开发人员分配", 1, 2, "web"));

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, c.id(), 12L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("仅可分配给本项目测试人员"), "人话提示：%s".formatted(ex.getMessage()));
    }

    @Test
    void assign_测试负责人_可分配() {
        long pid = createProject("k6d");
        addMemberWithRole(pid, 13L, "test_lead");
        CaseInfo c = caseService.create(1L, input(pid, "负责人分配", 1, 2, "web"));

        AssignResult r = caseService.assign(1L, c.id(), 13L);
        assertEquals(13L, r.assigneeId());
    }

    @Test
    void assign_assigneeId为空_40001() {
        long pid = createProject("asn");
        CaseInfo c = caseService.create(1L, input(pid, "空分配人", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.assign(1L, c.id(), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void batchAssign_指定成员_全部更新() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> ids = createCases(pid, 10);

        AssignBatchResult r = caseService.assignBatch(1L, pid, ids, 10L);

        assertEquals(10, r.assigned().size());
        for (long id : ids) {
            assertEquals(10L, assigneeOf(id), "全部 assignee=10: " + id);
        }
    }


    @Test
    void batchAssign_含不存在用例_40401整体回滚() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> ids = createCases(pid, 3);
        List<Long> withMissing = new ArrayList<>(ids);
        withMissing.add(99999L);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, withMissing, 10L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        for (long id : ids) {
            assertEquals(null, assigneeOf(id), "整体回滚：%s 未被分配".formatted(id));
        }
    }


    @Test
    void batchAssign_含已通过用例_40001整体回滚() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> ids = createCases(pid, 3);
        CaseInfo approved = caseService.create(1L, input(pid, "已通过混入", 1, 2, "web"));
        caseService.review(1L, approved.id(), ReviewAction.PASS, null, approved.version());
        ids.add(approved.id());

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, ids, 10L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        for (long id : ids) {
            assertEquals(null, assigneeOf(id), "整体回滚：%s 未被分配".formatted(id));
        }
    }


    @Test
    void batchAssign_跨项目成员_40301() {
        long pidA = createProject("k6e");
        long pidB = createProject("k6f");
        addMember(pidA, 10L);
        addMember(pidB, 11L);
        List<Long> ids = createCases(pidA, 3);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pidA, ids, 11L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("该用户不是本项目成员"), "人话提示：%s".formatted(ex.getMessage()));
        for (long id : ids) {
            assertEquals(null, assigneeOf(id), "整体回滚：%s 未被分配".formatted(id));
        }
    }


    @Test
    void batchAssign_开发人员_40301() {
        long pid = createProject("k6g");
        addMemberWithRole(pid, 12L, "developer");
        List<Long> ids = createCases(pid, 3);

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, ids, 12L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("仅可分配给本项目测试人员"), "人话提示：%s".formatted(ex.getMessage()));
        for (long id : ids) {
            assertEquals(null, assigneeOf(id), "整体回滚：%s 未被分配".formatted(id));
        }
    }


    @Test
    void batchAssign_自动负载均衡_5人15条_每人3条() {
        long pid = createProject("asn");
        removeMember(pid, 1L);
        for (long u = 20L; u < 25L; u++) {
            addMember(pid, u);
        }
        List<Long> ids = createCases(pid, 15);

        AssignBatchResult r = caseService.assignBatch(1L, pid, ids, null);

        Map<Long, Integer> byMember = new HashMap<>();
        for (AssignResult ar : r.assigned()) {
            byMember.merge(ar.assigneeId(), 1, Integer::sum);
        }
        assertEquals(5, byMember.size(), "5 名成员都分到");
        for (long u = 20L; u < 25L; u++) {
            assertEquals(3, byMember.get(u), "每人 3 条（差≤1）: " + byMember);
        }
    }


    @Test
    void batchAssign_负载均衡_分给未评数最少者() {
        long pid = createProject("asn");
        removeMember(pid, 1L);
        addMember(pid, 30L);
        addMember(pid, 31L);
        addMember(pid, 32L);
        caseService.assignBatch(1L, pid, createCases(pid, 2), 30L);
        caseService.assignBatch(1L, pid, createCases(pid, 5), 31L);
        caseService.assignBatch(1L, pid, createCases(pid, 8), 32L);

        AssignBatchResult r = caseService.assignBatch(1L, pid, createCases(pid, 1), null);

        assertEquals(30L, r.assigned().get(0).assigneeId(), "未评数最少者（30 号成员 2 条）");
    }


    @Test
    void batchAssign_自动负载均衡_仅测试人员() {
        long pid = createProject("k6h");
        removeMember(pid, 1L);
        addMember(pid, 20L);
        addMemberWithRole(pid, 21L, "developer");
        List<Long> ids = createCases(pid, 3);

        AssignBatchResult r = caseService.assignBatch(1L, pid, ids, null);

        for (AssignResult ar : r.assigned()) {
            assertEquals(20L, ar.assigneeId(), "自动均衡只选测试人员: " + ar);
        }
    }


    @Test
    void list_我的待评审队列_只返回分配给我的待评审() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        addMember(pid, 11L);
        List<Long> mine = createCases(pid, 3);
        List<Long> others = createCases(pid, 2);
        caseService.assignBatch(1L, pid, mine, 10L);
        caseService.assignBatch(1L, pid, others, 11L);

        PageResult<CaseInfo> queue = caseService.list(new CaseQuery(pid, 1, null, null, null, 10L, null, null, null, null, 1, 10));
        assertEquals(3L, queue.total(), "我的待评审只含分配给我的 3 条");
        for (CaseInfo row : queue.list()) {
            assertEquals(10L, row.assigneeId());
        }
    }


    @Test
    void list_未分配视图_assigneeId0_只返回回池用例() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> unassigned = createCases(pid, 2);
        List<Long> assigned = createCases(pid, 1);
        caseService.assignBatch(1L, pid, assigned, 10L);

        PageResult<CaseInfo> pool = caseService.list(new CaseQuery(pid, null, null, null, null, 0L, null, null, null, null, 1, 10));
        assertEquals(2L, pool.total(), "未分配视图只含回池用例");
        Set<Long> ids = pool.list().stream().map(CaseInfo::id).collect(Collectors.toSet());
        assertEquals(new HashSet<>(unassigned), ids, "正是未分配的那 2 条");
    }


    @Test
    void assign_审计记录_actionAssign() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        CaseInfo c = caseService.create(1L, input(pid, "审计分配", 1, 2, "web"));

        caseService.assign(1L, c.id(), 10L);

        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM tp_audit_log WHERE object_type = 'case' AND object_id = ? ORDER BY id",
                String.class, String.valueOf(c.id()));
        assertEquals(List.of("create", "assign"), actions, "create + assign 各一条审计");
    }

    @Test
    void batchAssign_审计记录_摘要objectId明细afterJson() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> ids = createCases(pid, 3);

        caseService.assignBatch(1L, pid, ids, 10L);

        // Scoping: tp_audit_log is shared across suites on the dev DB and the batch
        // summary is written asynchronously from the BATCH_ASSIGNED event, so the
        // assertion anchors on this run's own case ids (globally unique) instead of
        // relying on a blanket table wipe.
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT object_id, action, after_json FROM tp_audit_log"
                        + " WHERE action = 'assign' AND object_id = 'batch:3'"
                        + " AND after_json::text LIKE ? ORDER BY id DESC",
                "%" + ids.get(0) + "%");
        assertEquals(1, audits.size(), "批量分配一条审计");
        assertEquals("batch:3", String.valueOf(audits.get(0).get("object_id")));
        String after = String.valueOf(audits.get(0).get("after_json"));
        assertTrue(after.contains("caseId"), "after_json 含逐条明细: " + after);
        assertTrue(after.contains("assigneeId"), "after_json 含分配人明细: " + after);
    }


    @Test
    void batchAssign_空列表_40001() {
        long pid = createProject("asn");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, Collections.emptyList(), 10L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void batchAssign_超100条_40001() {
        long pid = createProject("asn");
        addMember(pid, 10L);
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 101; i++) {
            ids.add(i);
        }
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, ids, 10L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("100"), "人话提示：%s".formatted(ex.getMessage()));
    }


    @Test
    void batchAssign_项目无成员_40001() {
        long pid = createProject("asn");
        removeMember(pid, 1L);
        List<Long> ids = createCases(pid, 2);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.assignBatch(1L, pid, ids, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("测试人员"), "人话提示：%s".formatted(ex.getMessage()));
    }


    @Test
    void batchReview_混合状态_成功项评审_冲突项跳过列出() {
        long pid = createProject("br1");
        CaseInfo c1 = caseService.create(1L, input(pid, "可评审用例1", 1, 2, "web"));
        CaseInfo c2 = caseService.create(1L, input(pid, "可评审用例2", 1, 2, "web"));    // status=1
        CaseInfo c3 = caseService.create(1L, input(pid, "已通过用例", 1, 2, "web", 3));

        ReviewBatchResult r = caseService.reviewBatch(1L, new ReviewBatchRequest(
                pid, "pass", null, List.of(c1.id(), c2.id(), c3.id()), true));

        assertEquals(List.of(c1.id(), c2.id()), r.succeeded(), "两条可评审成功");
        assertEquals(1, r.skipped().size(), "终态用例跳过");
        assertEquals(c3.id(), r.skipped().get(0).id());
        assertTrue(r.skipped().get(0).reason().contains("终态"),
                "跳过原因人话：%s".formatted(r.skipped().get(0).reason()));
        assertEquals(3, caseService.get(c1.id()).status(), "c1 已通过");
        assertEquals(3, caseService.get(c2.id()).status(), "c2 已通过");
        assertEquals(3, caseService.get(c3.id()).status(), "c3 保持终态");
    }

    @Test
    void batchReview_评审中用例_通过成功() {
        long pid = createProject("br2");
        CaseInfo c = caseService.create(1L, input(pid, "评审中用例", 1, 2, "web", 2));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(c.id()), false));
        assertEquals(List.of(c.id()), r.succeeded());
        assertEquals(3, caseService.get(c.id()).status(), "评审中→已通过");
    }

    @Test
    void batchReview_needChange_remark必填_缺失整体400() {
        long pid = createProject("br3");
        CaseInfo c = caseService.create(1L, input(pid, "需修改用例", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "need_change", null, List.of(c.id()), false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals(1, caseService.get(c.id()).status(), "整体拒绝，状态不变");
    }

    @Test
    void batchReview_needChange_带remark_成功且reviewLog落库() {
        long pid = createProject("br4");
        CaseInfo c = caseService.create(1L, input(pid, "需修改用例", 1, 2, "web"));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "need_change", "断言不严谨", List.of(c.id()), false,
                        List.of("expected_wrong")));
        assertEquals(List.of(c.id()), r.succeeded());
        assertEquals(4, caseService.get(c.id()).status(), "需修改");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ? AND action='需修改'",
                Integer.class, c.id());
        assertEquals(1, cnt, "review_log 落库（TC-RVW-11，action 存中文与 01 文档 §3.12 一致）");
    }

    @Test
    void batchReview_跨项目用例_跳过H1隔离() {
        long p1 = createProject("brp1");
        long p2 = createProject("brp2");
        CaseInfo c1 = caseService.create(1L, input(p1, "项目1用例", 1, 2, "web"));
        CaseInfo c2 = caseService.create(1L, input(p2, "项目2用例", 1, 2, "web"));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(p1, "pass", null, List.of(c1.id(), c2.id()), false));
        assertEquals(List.of(c1.id()), r.succeeded());
        assertEquals(c2.id(), r.skipped().get(0).id());
        assertTrue(r.skipped().get(0).reason().contains("项目"),
                "H1 隔离人话：%s".formatted(r.skipped().get(0).reason()));
    }

    @Test
    void batchReview_不存在用例_跳过不中断() {
        long pid = createProject("br5");
        CaseInfo c = caseService.create(1L, input(pid, "存在用例", 1, 2, "web"));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(c.id(), 999999L), false));
        assertEquals(List.of(c.id()), r.succeeded());
        assertEquals(999999L, r.skipped().get(0).id());
        assertTrue(r.skipped().get(0).reason().contains("不存在"),
                "跳过原因人话：%s".formatted(r.skipped().get(0).reason()));
    }

    @Test
    void batchReview_空列表_400() {
        long pid = createProject("br6");
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(), false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void batchReview_重复ID_去重只评一次() {
        long pid = createProject("br7");
        CaseInfo c = caseService.create(1L, input(pid, "去重用例", 1, 2, "web"));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(c.id(), c.id()), false));
        assertEquals(List.of(c.id()), r.succeeded());
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ?", Integer.class, c.id());
        assertEquals(1, cnt, "重复 ID 只评审一次");
    }

    @Test
    void batchReview_全部终态_全部跳过_不报错() {
        long pid = createProject("br8");
        CaseInfo c1 = caseService.create(1L, input(pid, "已通过1", 1, 2, "web", 3));
        CaseInfo c2 = caseService.create(1L, input(pid, "已通过2", 1, 2, "web", 3));
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(c1.id(), c2.id()), false));
        assertTrue(r.succeeded().isEmpty());
        assertEquals(2, r.skipped().size(), "IT-RVW-13：含已通过用例跳过该条");
    }

    @Test
    void batchReview_非法action_400() {
        long pid = createProject("br9");
        CaseInfo c = caseService.create(1L, input(pid, "非法动作", 1, 2, "web"));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "destroy", null, List.of(c.id()), false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void batchReview_超100条_400() {
        long pid = createProject("br10");
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 101; i++) {
            ids.add(i);
        }
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, ids, false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void batchReview_版本并发变更_跳过不覆盖() {
        long pid = createProject("br11");
        CaseInfo c = caseService.create(1L, input(pid, "并发用例", 1, 2, "web"));
        caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version());
        ReviewBatchResult r = caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "pass", null, List.of(c.id()), false));
        assertTrue(r.succeeded().isEmpty(), "状态已变更不重复评审");
        assertEquals(1, r.skipped().size());
        assertTrue(r.skipped().get(0).reason().contains("终态")
                        || r.skipped().get(0).reason().contains("变更"),
                "跳过原因人话：%s".formatted(r.skipped().get(0).reason()));
        assertEquals(3, caseService.get(c.id()).status(), "终态保持");
    }


    @Test
    void create_带阶段_落库与详情回显() {
        long pid = createProject("k10");
        CaseInfo info = caseService.create(1L,
                new CaseCreateInput(pid, "冒烟用例", "前置", "预期", 1, 2, "web", 1, 2, 1, null, steps("a", "b")));

        assertEquals(2, info.phase(), "创建 phase=2 回显");
        Integer dbPhase = jdbcTemplate.queryForObject(
                "SELECT phase FROM tp_test_case WHERE id = ?", Integer.class, info.id());
        assertEquals(2, dbPhase, "DB 落库 phase=2");
        assertEquals(2, caseService.get(info.id()).phase(), "详情回显 phase=2");
    }

    @Test
    void create_phase空_未归属允许() {
        long pid = createProject("k10b");
        CaseInfo info = caseService.create(1L,
                new CaseCreateInput(pid, "未归属用例", null, null, 1, 2, "web", 1, null, 1, null, steps("a", "b")));

        assertNull(info.phase(), "phase=null 允许（未归属）");
        assertNull(jdbcTemplate.queryForObject(
                "SELECT phase FROM tp_test_case WHERE id = ?", Integer.class, info.id()));
    }

    @Test
    void create_phase非法值_400() {
        long pid = createProject("k10c");
        for (int bad : new int[]{0, 5, 9, -1}) {
            ServiceException ex = assertThrows(ServiceException.class, () -> caseService.create(1L,
                    new CaseCreateInput(pid, "非法阶段" + bad, null, null, 1, 2, "web", 1, bad, 1, null, steps("a", "b"))));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "phase=" + bad + " 应 400");
            assertTrue(ex.getMessage().contains("用例阶段"), "人话提示: " + ex.getMessage());
        }
    }

    @Test
    void update_修改阶段_持久化且与状态机正交() {
        long pid = createProject("k10d");
        CaseInfo c = caseService.create(1L,
                new CaseCreateInput(pid, "阶段变更用例", null, null, 1, 2, "web", 1, 1, 1, null, steps("a", "b")));

        CaseInfo updated = caseService.update(1L, c.id(),
                new CaseUpdateInput(null, null, null, null, null, null, 3, null), c.version());
        assertEquals(3, updated.phase(), "phase 1→3 持久化");
        assertEquals(1, updated.status(), "阶段变更不影响 status（正交）");
        assertEquals(2, updated.version(), "乐观锁 version+1");

        caseService.review(1L, c.id(), ReviewAction.PASS, null, updated.version());
        assertEquals(3, caseService.get(c.id()).status(), "评审通过后 status=3（状态机不受 phase 干扰）");
        assertEquals(3, caseService.get(c.id()).phase(), "归档后阶段保留（TC-K12）");
    }

    @Test
    void update_phase非法值_400() {
        long pid = createProject("k10e");
        CaseInfo c = caseService.create(1L,
                new CaseCreateInput(pid, "非法更新", null, null, 1, 2, "web", 1, 1, 1, null, steps("a", "b")));
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.update(1L, c.id(),
                new CaseUpdateInput(null, null, null, null, null, null, 8, null), c.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void list_按阶段筛选_含未归属视图() {
        long pid = createProject("k10f");
        long smoke = caseService.create(1L,
                new CaseCreateInput(pid, "冒烟A", null, null, 1, 2, "web", 1, 1, 1, null, steps("a", "b"))).id();
        long func = caseService.create(1L,
                new CaseCreateInput(pid, "功能B", null, null, 1, 2, "web", 1, 2, 1, null, steps("a", "b"))).id();
        long reg = caseService.create(1L,
                new CaseCreateInput(pid, "回归C", null, null, 1, 2, "web", 1, 3, 1, null, steps("a", "b"))).id();
        long none = caseService.create(1L,
                new CaseCreateInput(pid, "未归属D", null, null, 1, 2, "web", 1, null, 1, null, steps("a", "b"))).id();

        PageResult<CaseInfo> smokePage = caseService.list(
                new CaseQuery(pid, null, null, null, null, null, 1, null, null, null, 1, 100));
        assertEquals(1, smokePage.total(), "按阶段 1 筛选只返回冒烟");
        assertEquals(smoke, smokePage.list().get(0).id());

        PageResult<CaseInfo> nonePage = caseService.list(
                new CaseQuery(pid, null, null, null, null, null, 0, null, null, null, 1, 100));
        assertEquals(1, nonePage.total(), "phase=0 未归属视图只返回 null 阶段");
        assertEquals(none, nonePage.list().get(0).id());

        PageResult<CaseInfo> all = caseService.list(new CaseQuery(pid, null, null, null, null, null, null, null, null, null, 1, 100));
        assertEquals(4, all.total(), "不传 phase=全部");
        assertTrue(all.list().stream().anyMatch(c -> c.id() == func), "功能B 在全部列表");
        assertTrue(all.list().stream().anyMatch(c -> c.id() == reg), "回归C 在全部列表");
    }


    
    private long insertTag(long projectId, String name) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, ?, 4) RETURNING id",
                Long.class, projectId, name);
    }

    
    private List<String> tagNamesOf(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT d.name FROM tp_case_tag ct JOIN tp_tag_dict d ON d.id = ct.tag_id
                WHERE ct.case_id = ? ORDER BY d.name
                """, String.class, caseId);
    }

    private long countOf(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

 
    private void seedAssociations(long projectId, long caseId) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status) VALUES (?, ?, 4)",
                projectId, caseId);
        jdbcTemplate.update("INSERT INTO tp_case_defect (case_id, defect_key, status) VALUES (?, 'DK-CLONE-1', 1)",
                caseId);
        jdbcTemplate.update("INSERT INTO tp_review_log (case_id, user_id, action) VALUES (?, 1, 'pass')", caseId);
        jdbcTemplate.update("INSERT INTO tp_script (case_id) VALUES (?)", caseId);
    }

    @Test
    void clone_同项目_新编号回待评审_source标注_关联零复制() {
        long pid = createProject("cln");
        CaseInfo src = caseService.create(1L, input(pid, "克隆源用例", 0, 1, "web"));
        caseService.review(1L, src.id(), ReviewAction.PASS, null, src.version());
        seedAssociations(pid, src.id());

        CaseInfo copy = caseService.clone(1L, src.id(), null);

        assertTrue(copy.caseNo().matches("TC-cln-\\d{3,}"), "目标项目规则新编号: " + copy.caseNo());
        assertFalse(copy.caseNo().equals(src.caseNo()), "编号不复用源用例");
        assertEquals(pid, copy.projectId());
        assertEquals("克隆源用例", copy.title(), "标题保留原名（决策点 d：不分叉）");
        assertEquals(1, copy.status(), "status 回 1 待评审（源=3 不继承，决策点 a）");
        assertEquals(1, copy.version(), "新线 version=1");
        assertEquals(4, copy.source(), "source=4 克隆（CaseCreateInput javadoc 既有设计）");
        assertEquals("case:" + src.id(), copy.sourceRef(), "source_ref=case:{源id} 稳定键（决策点②）");
        assertNull(copy.flakyReason(), "flaky 标记不随克隆带入");
        assertEquals(0, countOf("SELECT count(*) FROM tp_exec_task WHERE case_id = ?", copy.id()));
        assertEquals(0, countOf("SELECT count(*) FROM tp_case_defect WHERE case_id = ?", copy.id()));
        assertEquals(0, countOf("SELECT count(*) FROM tp_review_log WHERE case_id = ?", copy.id()));
        assertEquals(0, countOf("SELECT count(*) FROM tp_script WHERE case_id = ?", copy.id()));
        assertEquals(1, countOf("SELECT count(*) FROM tp_exec_task WHERE case_id = ?", src.id()));
    }

    @Test
    void clone_同项目_字段步骤等值_标签分配人保留() {
        long pid = createProject("cln");
        long tagA = insertTag(pid, "支付域");
        addMember(pid, 10L);
        CaseInfo src = caseService.get(caseService.create(1L, new CaseCreateInput(
                pid, "字段复制钉", "账号已注册且未锁定", "登录成功跳转首页",
                0, 3, "mobile", 1, 2, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0),
                        new CaseStepInput(2, "点击登录按钮", 0),
                        new CaseStepInput(3, "页面出现登录成功提示", 1)),
                List.of(tagA), null)).id());
        caseService.assign(1L, src.id(), 10L);

        CaseInfo copy = caseService.clone(1L, src.id(), null);

        assertEquals(src.precondition(), copy.precondition(), "前置条件复制");
        assertEquals(src.expectedResult(), copy.expectedResult(), "预期结果复制");
        assertEquals(src.priority(), copy.priority(), "优先级复制");
        assertEquals(src.confidence(), copy.confidence(), "置信度复制");
        assertEquals(src.endType(), copy.endType(), "端类型复制（决策点 f：env 无关直拷）");
        assertEquals(src.phase(), copy.phase(), "阶段归属复制");
        assertEquals(3, copy.steps().size(), "步骤条数复制");
        for (int i = 0; i < 3; i++) {
            assertEquals(src.steps().get(i).stepNo(), copy.steps().get(i).stepNo(), "step_no 顺序等值");
            assertEquals(src.steps().get(i).description(), copy.steps().get(i).description(), "描述等值");
            assertEquals(src.steps().get(i).isAssert(), copy.steps().get(i).isAssert(), "is_assert 等值");
        }
        assertEquals(List.of("支付域"), tagNamesOf(copy.id()), "同项目标签保留");
        assertEquals(10L, copy.assigneeId(), "同项目分配人保留（决策点 c）");
        String stepsText = jdbcTemplate.queryForObject(
                "SELECT steps_text FROM tp_test_case WHERE id = ?", String.class, copy.id());
        assertTrue(stepsText.contains("页面出现登录成功提示"), "steps_text 聚合随步骤重建: " + stepsText);
    }

    @Test
    void clone_跨项目_目标编号_标签重映射_无同名不挂() {
        long pid = createProject("clna");
        long targetPid = createProject("clnb");
        long tagSameName = insertTag(pid, "共享标签");
        long tagOrphan = insertTag(pid, "目标项目没有的标签");
        addMember(pid, 10L);
        CaseInfo src = caseService.create(1L, new CaseCreateInput(
                pid, "跨项目源", null, null, 1, 2, "web", 1, null, 1, null,
                steps("操作A", "断言B"), List.of(tagSameName, tagOrphan), null));
        caseService.assign(1L, src.id(), 10L);
        long targetTag = insertTag(targetPid, "共享标签");

        CaseInfo copy = caseService.clone(1L, src.id(), targetPid);

        assertTrue(copy.caseNo().startsWith("TC-clnb-"), "目标项目 code 规则编号: " + copy.caseNo());
        assertEquals(targetPid, copy.projectId());
        assertEquals(List.of("共享标签"), tagNamesOf(copy.id()), "标签按 name 重映射，无同名静默不挂（IT-CLONE-01）");
        assertEquals(1, countOf("SELECT count(*) FROM tp_case_tag WHERE case_id = ? AND tag_id = ?",
                copy.id(), targetTag), "挂的是目标项目字典 id 非源 id");
        assertNull(copy.assigneeId(), "跨项目 assignee 不挂（决策点 c：目标成员身份未验证）");
        assertEquals("case:" + src.id(), copy.sourceRef());
        assertEquals(4, copy.source());
        assertEquals(1, copy.status());
    }

    @Test
    void clone_跨项目_非成员403_目标不存在404() {
        long pid = createProject("clnp");
        long targetPid = createProject("clnq");
        long srcId = caseService.create(1L, input(pid, "权限钉", 1, 2, "web")).id();
        removeMember(targetPid, 1L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.clone(1L, srcId, targetPid));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("成员"), "403 人话点名成员: " + ex.getMessage());
        assertEquals(0, countOf("SELECT count(*) FROM tp_test_case WHERE project_id = ?", targetPid),
                "403 零写入");

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> caseService.clone(1L, srcId, 99999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex2.getCode());
        assertTrue(ex2.getMessage().contains("项目"), "目标项目不存在 404 人话: " + ex2.getMessage());
    }

    @Test
    void clone_软删源404_重复克隆不冲突_单层拷贝不递归() {
        long pid = createProject("clnr");
        long srcId = caseService.create(1L, input(pid, "IT-CLONE 钉", 1, 2, "web")).id();

        CaseInfo c1 = caseService.clone(1L, srcId, null);
        CaseInfo c2 = caseService.clone(1L, srcId, null);
        assertFalse(c1.caseNo().equals(c2.caseNo()), "重复克隆编号不冲突: " + c1.caseNo() + " / " + c2.caseNo());
        assertEquals("case:" + srcId, c1.sourceRef());
        assertEquals("case:" + srcId, c2.sourceRef());

        CaseInfo c3 = caseService.clone(1L, c1.id(), null);
        assertEquals("case:" + c1.id(), c3.sourceRef(), "多级血缘=查询侧沿 source_ref 链可查，拷贝侧单层（决策点 b）");

        caseService.delete(1L, srcId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.clone(1L, srcId, null));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("用例"), "源软删 404 人话: " + ex.getMessage());
    }


    
    private void seedUsableScript(long caseId) {
        jdbcTemplate.update("""
                INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version)
                VALUES (?, 'v2', '{"steps":[]}'::jsonb, 1, 1, 1)
                """, caseId);
    }

    private Integer scriptStatusOf(long caseId) {
        return jdbcTemplate.query("SELECT status FROM tp_script WHERE case_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, caseId);
    }

    @Test
    void update_步骤重排提交_脚本失效标记待更新() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "改序失效", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null, null, null,
                null, null, List.of(new CaseStepInput(1, "页面出现登录成功提示", 1),
                        new CaseStepInput(2, "输入账号密码", 0))), created.version());

        assertEquals(2, scriptStatusOf(created.id()), "steps 改序 → 脚本失效标记待更新（TC-C2-01）");
    }

    @Test
    void update_步骤描述编辑_脚本失效标记待更新() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "描述失效", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null, null, null,
                null, null, List.of(new CaseStepInput(1, "输入账号密码（改为手机号）", 0),
                        new CaseStepInput(2, "页面出现登录成功提示", 1))), created.version());

        assertEquals(2, scriptStatusOf(created.id()), "步骤描述变化 → 失效（10 文档 L74）");
    }

    @Test
    void update_测试目标编辑_脚本失效标记待更新() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "目标失效", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput("目标已修改", null, null, null,
                null, null, null, null), created.version());

        assertEquals(2, scriptStatusOf(created.id()), "title 变化 → 失效（PRD 4.1 四类字段）");
    }

    @Test
    void update_前置条件编辑_脚本失效标记待更新() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "前置失效", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, "前置已修改：先注册账号",
                null, null, null, null, null, null), created.version());

        assertEquals(2, scriptStatusOf(created.id()), "precondition 变化 → 失效（PRD 4.1）");
    }

    @Test
    void update_预期结果编辑_脚本失效标记待更新() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "预期失效", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, "预期已修改", null,
                null, null, null, null), created.version());

        assertEquals(2, scriptStatusOf(created.id()), "expectedResult 变化 → 失效（PRD 4.1）");
    }

    @Test
    void update_仅改优先级_脚本不失效() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "优先级不改", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null, 0, null,
                null, null, null), created.version());

        assertEquals(1, scriptStatusOf(created.id()), "仅优先级变化不触发失效（12 文档 L295）");
    }

    @Test
    void update_内容相同提交_脚本不失效() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "内容不改", 1, 2, "web"));
        seedUsableScript(created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput("内容不改", null, null, null,
                null, null, null, List.of(new CaseStepInput(1, "输入账号密码", 0),
                        new CaseStepInput(2, "页面出现登录成功提示", 1))), created.version());

        assertEquals(1, scriptStatusOf(created.id()), "内容未实际变化不失效（「若改步骤」句）");
    }

    @Test
    void update_编辑无脚本用例_正常成功零脚本行() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "无脚本失效", 1, 2, "web"));

        CaseInfo updated = caseService.update(1L, created.id(),
                new CaseUpdateInput("无脚本也已改", null, null, null, null, null, null, null),
                created.version());

        assertEquals("无脚本也已改", updated.title());
        assertEquals(0, countOf("SELECT count(*) FROM tp_script WHERE case_id = ?", created.id()),
                "无脚本行联动静默 no-op（定夺④）");
    }

    @Test
    void rollback_重挂脚本_不误触失效联动() {
        long pid = createProject("inv");
        CaseInfo created = caseService.create(1L, input(pid, "回滚不误伤", 1, 2, "web"));
        seedUsableScript(created.id());
        CaseInfo v2 = caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null,
                0, null, null, null, null), created.version());
        CaseInfo v3 = caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null,
                2, null, null, null, null), v2.version());
        int scriptVersionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_script WHERE case_id = ?", Integer.class, created.id());
        assertEquals(1, scriptStatusOf(created.id()), "前置：两次编辑（非四类）零联动");

        caseService.rollback(1L, created.id(), 2, v3.version());

        Integer nowScriptVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_script WHERE case_id = ?", Integer.class, created.id());
        assertEquals(scriptVersionBefore + 1, nowScriptVersion, "回滚重挂发生（B5 既有语义回归钉）");
        assertEquals(1, scriptStatusOf(created.id()),
                "回滚链零失效联动——restoreScript 重挂对齐语义不被误伤（定夺③）");
    }
}
