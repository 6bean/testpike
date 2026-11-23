package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseReviewLogInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchRequest;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseFeedbackTypeTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Project ids created by this run; drained by the sweep on every hook. */
    private final List<Long> projectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Run token keeps the fixture project codes unique on the shared dev db. */
    private final String runToken = SharedDbFixtureSweep.newToken();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.purgeTrainingSamples(jdbcTemplate);
        // Fixture-scoped teardown : unwind only this run's tracked projects —
        // audit/cases/steps/links, exec tasks, defects and the script tree drop via
        // the shared FK sweep, never a blanket TRUNCATE of shipped tables.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code,
                code + "_" + runToken, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private CaseInfo createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页",
                1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0))));
    }


    @Test
    void review_reject_未选类型_400人话点名() {
        long pid = createProject("d1a");
        CaseInfo c = createCase(pid, "缺类型拒绝");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.REJECT, "超出范围", c.version(), List.of()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请选择问题类型"),
                "提示人话点名：%s".formatted(ex.getMessage()));
        assertEquals(1, caseService.get(c.id()).status(), "拒绝动作被拦，状态不变");
    }

    @Test
    void review_needChange_null类型_400人话点名() {
        long pid = createProject("d1b");
        CaseInfo c = createCase(pid, "缺类型需修改");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.NEED_CHANGE, "补充断言", c.version(), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请选择问题类型"));
    }


    @Test
    void review_pass_空类型与旧签名_放行不误拦() {
        long pid = createProject("d1c");
        CaseInfo c = createCase(pid, "通过放行");
        ReviewResult r1 = caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version(), null);
        assertEquals(3, r1.status(), "pass 带 null 类型放行");

        CaseInfo c2 = createCase(pid, "旧签名通过");
        ReviewResult r2 = caseService.review(1L, c2.id(), ReviewAction.PASS, null, c2.version());
        assertEquals(3, r2.status(), "旧五参签名零破坏（IT-D1-01 存量调用绿回归）");
    }


    @Test
    void review_非法类型编码_40001含清单() {
        long pid = createProject("d1d");
        CaseInfo c = createCase(pid, "非法编码");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.review(1L, c.id(), ReviewAction.REJECT, "理由", c.version(),
                        List.of("not_a_type")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("步骤不准确"),
                "错误提示含中文合法清单：%s".formatted(ex.getMessage()));
    }


    @Test
    void review_reject_多类型_落列读回中文标签保序去重() {
        long pid = createProject("d1e");
        CaseInfo c = createCase(pid, "多类型拒绝");

        caseService.review(1L, c.id(), ReviewAction.REJECT, "重复且缺逻辑", c.version(),
                List.of("logic_missing", "locate_error", "logic_missing"));

        String stored = jdbcTemplate.queryForObject(
                "SELECT feedback_types::text FROM tp_review_log WHERE case_id = ?", String.class, c.id());
        assertNotNullText(stored);
        assertTrue(stored.contains("逻辑遗漏") && stored.contains("定位错误"),
                "落列存储：%s".formatted(stored));

        List<CaseReviewLogInfo> logs = caseService.listReviewLogs(c.id());
        assertEquals(List.of("逻辑遗漏", "定位错误"), logs.get(0).feedbackTypes(), "读路径保序去重中文标签");
    }

    @Test
    void review_pass_带类型_落列_不带类型读回空列表() {
        long pid = createProject("d1f");
        CaseInfo c = createCase(pid, "通过带类型");
        caseService.review(1L, c.id(), ReviewAction.PASS, null, c.version(), List.of("wording"));
        List<CaseReviewLogInfo> withTypes = caseService.listReviewLogs(c.id());
        assertEquals(List.of("措辞不准确"), withTypes.get(0).feedbackTypes());

        CaseInfo c2 = createCase(pid, "通过不带类型");
        caseService.review(1L, c2.id(), ReviewAction.PASS, null, c2.version(), null);
        List<CaseReviewLogInfo> noTypes = caseService.listReviewLogs(c2.id());
        assertEquals(List.of(), noTypes.get(0).feedbackTypes(), "空类型存 NULL（V93），读路径契约稳定空列表");
    }


    @Test
    void reviewBatch_带类型_逐条落列() {
        long pid = createProject("d1g");
        CaseInfo c1 = createCase(pid, "批量一");
        CaseInfo c2 = createCase(pid, "批量二");

        ReviewBatchResult r = caseService.reviewBatch(1L, new ReviewBatchRequest(
                pid, "need_change", "断言不严谨", List.of(c1.id(), c2.id()), false,
                List.of("expected_wrong", "step_inaccurate")));
        assertEquals(List.of(c1.id(), c2.id()), r.succeeded());

        for (long id : List.of(c1.id(), c2.id())) {
            List<CaseReviewLogInfo> logs = caseService.listReviewLogs(id);
            assertEquals(List.of("预期结果错误", "步骤不准确"), logs.get(0).feedbackTypes(),
                    "批量逐条落列（case " + id + "）");
        }
    }

    @Test
    void reviewBatch_needChange_缺类型_整体400状态不变() {
        long pid = createProject("d1h");
        CaseInfo c = createCase(pid, "批量缺类型");

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.reviewBatch(1L,
                new ReviewBatchRequest(pid, "need_change", "有问题", List.of(c.id()), false, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请选择问题类型"));
        assertEquals(1, caseService.get(c.id()).status(), "整体拒绝，状态不变");
    }

    private static void assertNotNullText(String s) {
        assertTrue(s != null && !s.isBlank(), "落列不应为空");
    }
}
