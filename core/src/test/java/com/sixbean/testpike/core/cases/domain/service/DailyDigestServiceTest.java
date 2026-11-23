package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.DailyDigestResult;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p> teardown posture: {@code scan} is a global job on the shared dev
 * database, so its aggregate counters are asserted as bounds while every
 * precise assertion is pinned to this run's own observables (q5 users and
 * fixture project ids, swept by {@link ProjectFixtureRig}).
 */
@SpringBootTest(classes = CoreApplication.class)
class DailyDigestServiceTest {

    @Autowired
    private DailyDigestService dailyDigestService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private static final long CREATOR = 9510L;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> trackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    
    private void ensureUser(long id) {
        jdbcTemplate.update("INSERT INTO tp_user (id, username, password_hash, role, status) "
                + "VALUES (?, 'q5_user' || ?, NULL, 1, 1) ON CONFLICT (id) DO NOTHING", id, id);
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — only this suite's
        // tracked fixture projects and their rows are swept, then its own users.
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        jdbcTemplate.update("DELETE FROM tp_notification_pref WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'q5_%' OR username LIKE 'k6user95_%')");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'q5_%' OR username LIKE 'k6user95_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'q5_%' OR username LIKE 'k6user95_%'");
        trackedProjects.clear();
    }

    
    private long createProject(String code) {
        ensureUser(CREATOR);
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, CREATOR,
                trackedProjects, "Q5项目" + code, code);
    }

    private CaseInfo createCase(long pid, String title) {
        return caseService.create(CREATOR, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 1, null, null, null, List.of()));
    }

 
    private void rewindCaseToYesterday(long caseId) {
        jdbcTemplate.update("UPDATE tp_test_case SET created_at = "
                + "date_trunc('day', now()) - interval '1 day' + interval '1 hour' WHERE id = ?", caseId);
    }

    
    private void insertYesterdayExec(long pid, long caseId, int status) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status, finished_at) "
                        + "VALUES (?, ?, ?, date_trunc('day', now()) - interval '1 day' + interval '2 hour')",
                pid, caseId, status);
    }

    private void insertYesterdayDefect(long pid) {
        jdbcTemplate.update("INSERT INTO tp_defect (project_id, title, status, reporter_id, created_at) "
                        + "VALUES (?, '昨日缺陷', 1, ?, date_trunc('day', now()) - interval '1 day' + interval '3 hour')",
                pid, CREATOR);
    }

    
    private void insertYesterdayLink(long pid, long caseId) {
        Long reqId = jdbcTemplate.queryForObject("INSERT INTO tp_requirement (project_id, title, status) "
                + "VALUES (?, '登录需求', 1) RETURNING id", Long.class, pid);
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by, created_at) "
                        + "VALUES (?, ?, ?, date_trunc('day', now()) - interval '1 day' + interval '4 hour')",
                caseId, reqId, CREATOR);
    }

    
    private void subscribeDigest(long userId) {
        ensureUser(userId);
        jdbcTemplate.update("INSERT INTO tp_notification_pref (user_id, event_type, enabled) "
                + "VALUES (?, 'DAILY_DIGEST', 1) ON CONFLICT (user_id, event_type) DO UPDATE SET enabled = 1",
                userId);
    }

    private void unsubscribeDigest(long userId) {
        ensureUser(userId);
        jdbcTemplate.update("INSERT INTO tp_notification_pref (user_id, event_type, enabled) "
                + "VALUES (?, 'DAILY_DIGEST', 0) ON CONFLICT (user_id, event_type) DO UPDATE SET enabled = 0",
                userId);
    }

    private void addOwnerMember(long pid, long userId) {
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, userId, "test_lead");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2) "
                + "ON CONFLICT DO NOTHING", pid, userId);
    }

    private int digestCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DAILY_DIGEST' AND user_id = ?",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    /**
     * Digest rows of one project. The scan visits every active project, so the
     * run-owned pin is the notification row keyed to this fixture project.
     */
    private int digestRowsOfProject(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification"
                        + " WHERE event_type = 'DAILY_DIGEST' AND project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }

    private String digestContent(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE event_type = 'DAILY_DIGEST' AND user_id = ?"
                        + " ORDER BY id DESC LIMIT 1",
                String.class, userId);
    }

    
    private void assignToYesterdayReview(long caseId) {
        ensureUser(CREATOR);
        jdbcTemplate.update("UPDATE tp_test_case SET assignee_id = ?, assigned_at = "
                + "date_trunc('day', now()) - interval '1 day' + interval '5 hour' WHERE id = ?",
                CREATOR, caseId);
    }


    @Test
    void 有昨日数据_负责人与创建人各收一条含四要素() {
        long owner = 9501L;
        long pid = createProject("q5a");
        addOwnerMember(pid, owner);
        subscribeDigest(owner);
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "登录验证码校验");
        rewindCaseToYesterday(c.id());
        assignToYesterdayReview(c.id());
        insertYesterdayExec(pid, c.id(), 3);
        insertYesterdayExec(pid, c.id(), 4);
        insertYesterdayDefect(pid);
        insertYesterdayLink(pid, c.id());
        createCase(pid, "今日用例");

        DailyDigestResult result = dailyDigestService.scan();

        // Aggregate counters are global on the shared database: bounds here,
        // precision in the per-recipient and per-project counts below.
        assertTrue(result.scanned() >= 1, "本运行项目应计入扫描: " + result);
        assertTrue(result.sent() >= 2, "负责人与创建人各一条: " + result);
        assertEquals(1, digestCount(owner));
        assertEquals(1, digestCount(CREATOR));
        assertEquals(2, digestRowsOfProject(pid), "本项目恰好两条日报收件");
        String content = digestContent(owner);
        assertTrue(content.contains("新增用例 1"), content);
        assertTrue(content.contains("成功 1"), content);
        assertTrue(content.contains("失败 1"), content);
        assertTrue(content.contains("新增缺陷 1"), content);
        assertTrue(content.contains("未闭环缺陷 1"), content);
        assertTrue(content.contains("待评审积压 1"), content);
        assertTrue(content.contains("新增覆盖关联 1"), content);
        String title = jdbcTemplate.queryForObject(
                "SELECT title FROM tp_notification WHERE event_type = 'DAILY_DIGEST' AND user_id = ?",
                String.class, owner);
        assertTrue(title.contains("Q5项目q5a"), title);
    }

    @Test
    void 负责人等于创建人_去重只收一条() {
        long pid = createProject("q5b");
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "订单退款流程");
        rewindCaseToYesterday(c.id());

        dailyDigestService.scan();

        assertEquals(1, digestCount(CREATOR), "负责人与创建人重叠只发一条");
        assertEquals(1, digestRowsOfProject(pid));
    }


    @Test
    void 无昨日数据项目_跳发不发空报() {
        long owner = 9502L;
        long pid = createProject("q5c");
        addOwnerMember(pid, owner);
        subscribeDigest(owner);
        subscribeDigest(CREATOR);
        createCase(pid, "今日新建");

        DailyDigestResult result = dailyDigestService.scan();

        assertTrue(result.skipped() >= 1, "空数据项目应计入跳过: " + result);
        assertEquals(0, digestCount(owner));
        assertEquals(0, digestCount(CREATOR));
        assertEquals(0, digestRowsOfProject(pid), "空数据不得发空报");
    }

    @Test
    void 归档项目不发送() {
        long pid = createProject("q5d");
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "归档前用例");
        rewindCaseToYesterday(c.id());
        jdbcTemplate.update("UPDATE tp_project SET status = 2 WHERE id = ?", pid);

        dailyDigestService.scan();

        // The scan visits every active project, so the pin is this archived
        // fixture project's own digest rows rather than the global counter.
        assertEquals(0, digestRowsOfProject(pid), "归档项目不得生成日报");
        assertEquals(0, digestCount(CREATOR));
    }


    @Test
    void 同日重跑_去重不重复生成() {
        long pid = createProject("q5e");
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "重跑幂等用例");
        rewindCaseToYesterday(c.id());

        dailyDigestService.scan();
        DailyDigestResult second = dailyDigestService.scan();

        assertEquals(1, digestCount(CREATOR));
        assertEquals(1, digestRowsOfProject(pid), "同日重跑不得再发");
        assertTrue(second.deduped() >= 1, "本运行收件人应计入去重: " + second);
    }


    @Test
    void 订阅偏好关闭摘要_不生成() {
        long owner = 9503L;
        long pid = createProject("q5f");
        addOwnerMember(pid, owner);
        unsubscribeDigest(owner);
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "退订用户用例");
        rewindCaseToYesterday(c.id());

        DailyDigestResult result = dailyDigestService.scan();

        assertEquals(0, digestCount(owner));
        assertEquals(1, digestCount(CREATOR));
        assertTrue(result.sent() >= 1, "订阅收件人应计入发送: " + result);
        assertTrue(result.prefFiltered() >= 1, "退订收件人应计入偏好过滤: " + result);
    }

    @Test
    void 默认关闭无记录_订阅后才生成() {
        long owner = 9504L;
        long pid = createProject("q5g");
        addOwnerMember(pid, owner);
        subscribeDigest(CREATOR);
        CaseInfo c = createCase(pid, "默认关无记录用例");
        rewindCaseToYesterday(c.id());

        DailyDigestResult first = dailyDigestService.scan();
        assertEquals(0, digestCount(owner));
        assertTrue(first.prefFiltered() >= 1, "无记录=default_off 应计入偏好过滤: " + first);

        subscribeDigest(owner);
        DailyDigestResult second = dailyDigestService.scan();
        assertEquals(1, digestCount(owner));
        assertTrue(second.sent() >= 1, "订阅后应计入发送: " + second);
    }

    @Test
    void 多项目_各项目独立生成独立去重() {
        long pid1 = createProject("q5h");
        long pid2 = createProject("q5i");
        subscribeDigest(CREATOR);
        CaseInfo c1 = createCase(pid1, "项目1用例");
        rewindCaseToYesterday(c1.id());
        CaseInfo c2 = createCase(pid2, "项目2用例");
        rewindCaseToYesterday(c2.id());

        DailyDigestResult result = dailyDigestService.scan();

        assertTrue(result.scanned() >= 2, "两个项目均应计入扫描: " + result);
        assertTrue(result.sent() >= 2, "两个项目各发一条: " + result);
        assertEquals(2, digestCount(CREATOR));
        assertEquals(1, digestRowsOfProject(pid1));
        assertEquals(1, digestRowsOfProject(pid2));
    }
}
