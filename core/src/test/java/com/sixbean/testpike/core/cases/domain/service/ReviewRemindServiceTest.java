package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.ReviewRemindResult;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p> teardown posture: {@code scan} is a global job over the shared dev
 * database, so its aggregate counters are asserted as lower bounds while every
 * precise assertion is pinned to this run's own fixture project — notifications
 * carry the project id, so counting by (event_type, project_id) is exact even
 * when unrelated rows exist. Teardown sweeps only the tracked fixture projects
 * ({@link ProjectFixtureRig}); no blanket table wipe.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class ReviewRemindServiceTest {

    private static final String KEY_REMIND = "review.remind-hours";
    private static final String KEY_TIMEOUT = "review.timeout-hours";

    @Autowired
    private ReviewRemindService reviewRemindService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> remindTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the FK-tree sweep
        // reaches this suite's cases, steps, versions, notifications and audit
        // rows. The k6 fixture users keep deterministic ids and are reused by
        // CaseTestMembers (never deleted here, so no FK break for other suites).
        ProjectFixtureRig.sweep(jdbcTemplate, remindTrackedProjects);
        remindTrackedProjects.clear();
    }

    
    private long createProjectWithTester(long testerId) {
        String code = "q4" + testerId;
        long pid = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                remindTrackedProjects, "Q4项目" + code, code);
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, testerId, "tester");
        CaseTestMembers.addMember(jdbcTemplate, pid, testerId);
        return pid;
    }

    private CaseInfo createCase(long pid, String title) {
        return caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 1, null, null, null, List.of()));
    }

    
    private void assignAndRewind(long caseId, long testerId, int hoursAgo) {
        caseService.assign(1L, caseId, testerId);
        jdbcTemplate.update("UPDATE tp_test_case SET assigned_at = now() - (? || ' hours')::interval"
                + " WHERE id = ?", hoursAgo, caseId);
    }

    /** Notifications this scan emitted for one recipient inside one fixture project. */
    private int notifRows(String eventType, long projectId, long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = ? AND project_id = ?"
                        + " AND user_id = ?",
                Integer.class, eventType, projectId, userId);
        return n == null ? 0 : n;
    }

    /** All notifications this scan emitted for one fixture project (any recipient). */
    private int notifRows(String eventType, long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = ? AND project_id = ?",
                Integer.class, eventType, projectId);
        return n == null ? 0 : n;
    }

    private String notifContent(String eventType, long projectId, long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE event_type = ? AND project_id = ?"
                        + " AND user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, eventType, projectId, userId);
    }

    private void setThreshold(String key, String value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, key);
        assertNotNull(id, "V18c 种子键缺失: " + key);
        String group = jdbcTemplate.queryForObject(
                "SELECT group_name FROM tp_sys_config WHERE config_key = ?", String.class, key);
        String desc = jdbcTemplate.queryForObject(
                "SELECT description FROM tp_sys_config WHERE config_key = ?", String.class, key);
        configService.update(1L, id, new ConfigInput(key, value, group, "int", 0, desc));
    }


    @Test
    void 超24小时未评审_催办评审员REVIEW_REMIND() {
        long tester = 9101L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "登录验证码校验");
        assignAndRewind(c.id(), tester, 25);

        ReviewRemindResult result = reviewRemindService.scan();

        assertTrue(result.scanned() >= 1, "本运行用例应计入扫描: " + result);
        assertTrue(result.reminded() >= 1, "本运行催办应计入发送: " + result);
        assertEquals(1, notifRows("REVIEW_REMIND", pid, tester), "本项目恰好一条催办");
        assertEquals(0, notifRows("REVIEW_TIMEOUT", pid, 1L), "25 小时未达升级线");
        String content = notifContent("REVIEW_REMIND", pid, tester);
        assertTrue(content.contains("登录验证码校验") && content.contains(c.caseNo()),
                "催办内容应含用例标题与编号: " + content);
    }

    @Test
    void 未超阈值不发_23小时静默() {
        long tester = 9102L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "边界时长用例");
        assignAndRewind(c.id(), tester, 23);

        reviewRemindService.scan();

        // The scanned counter is global, so the precise judgement is this
        // project's notification rows: below the 24h line nothing is emitted.
        assertEquals(0, notifRows("REVIEW_REMIND", pid, tester), "未达阈值不应催办");
        assertEquals(0, notifRows("REVIEW_TIMEOUT", pid));
    }


    @Test
    void 同用例同评审员同自然日重复扫描不重发() {
        long tester = 9103L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "催办去重用例");
        assignAndRewind(c.id(), tester, 30);

        reviewRemindService.scan();
        ReviewRemindResult second = reviewRemindService.scan();

        // Second round: the candidate is still scanned but the calendar-day
        // dedup key suppresses a resend (global counter as a bound, exactness
        // pinned to this project's single notification row).
        assertTrue(second.scanned() >= 1, "本运行用例应再次计入扫描: " + second);
        assertTrue(second.deduped() >= 1, "重复扫描应计入去重: " + second);
        assertEquals(1, notifRows("REVIEW_REMIND", pid, tester), "同自然日重扫不得重发");
    }


    @Test
    void 超72小时升级项目负责人REVIEW_TIMEOUT_并继续每日催办评审员() {
        long tester = 9104L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "超时升级用例");
        assignAndRewind(c.id(), tester, 75);

        ReviewRemindResult result = reviewRemindService.scan();

        assertTrue(result.reminded() >= 1, "本运行催办应计入发送: " + result);
        assertTrue(result.escalated() >= 1, "本运行升级应计入发送: " + result);
        assertEquals(1, notifRows("REVIEW_TIMEOUT", pid, 1L), "本项目恰好一条升级");
        assertEquals(1, notifRows("REVIEW_REMIND", pid, tester), "升级后评审人仍收催办");
        String content = notifContent("REVIEW_TIMEOUT", pid, 1L);
        assertTrue(content.contains("超时") || content.contains("未评审"),
                "升级内容人话: " + content);
        assertTrue(content.contains(c.caseNo()), "升级内容应含用例编号: " + content);
    }

    @Test
    void 超时次日再扫_升级继续每日一条() {
        long tester = 9105L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "持续超时用例");
        assignAndRewind(c.id(), tester, 75);
        reviewRemindService.scan();
        // Pinned to this fixture project: the shared database never gets rewritten.
        jdbcTemplate.update("UPDATE tp_notification SET dedup_key = replace(dedup_key, ?, ?)"
                + " WHERE dedup_key IS NOT NULL AND project_id = ?",
                java.time.LocalDate.now().toString(),
                java.time.LocalDate.now().minusDays(1).toString(), pid);

        ReviewRemindResult second = reviewRemindService.scan();

        assertTrue(second.reminded() >= 1, "跨日后催办应再次计入发送: " + second);
        assertTrue(second.escalated() >= 1, "跨日后升级应再次计入发送: " + second);
        assertEquals(2, notifRows("REVIEW_REMIND", pid, tester), "跨日各自一条，本项目累计两条催办");
        assertEquals(2, notifRows("REVIEW_TIMEOUT", pid, 1L), "跨日各自一条，本项目累计两条升级");
    }


    @Test
    void 已通过评审或未分配或回池用例不进入催办() {
        long tester = 9106L;
        long pid = createProjectWithTester(tester);
        CaseInfo done = createCase(pid, "已评审用例");
        assignAndRewind(done.id(), tester, 100);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", done.id());
        createCase(pid, "未分配用例");
        CaseInfo pooled = createCase(pid, "回池用例");
        assignAndRewind(pooled.id(), tester, 90);
        jdbcTemplate.update("UPDATE tp_test_case SET assignee_id = NULL, assigned_at = NULL WHERE id = ?",
                pooled.id());

        reviewRemindService.scan();

        assertEquals(0, notifRows("REVIEW_REMIND", pid), "已处理/未分配/回池均不催办");
        assertEquals(0, notifRows("REVIEW_TIMEOUT", pid), "同上，升级一级同样不发");
    }

    @Test
    void 分配动作刷新assigned_at为now() {
        long tester = 9107L;
        long pid = createProjectWithTester(tester);
        CaseInfo c = createCase(pid, "重新分配刷新起算点");
        assignAndRewind(c.id(), tester, 100);

        long tester2 = 9108L;
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, tester2, "tester");
        CaseTestMembers.addMember(jdbcTemplate, pid, tester2);
        caseService.assign(1L, c.id(), tester2);

        reviewRemindService.scan();

        assertEquals(0, notifRows("REVIEW_REMIND", pid, tester), "原评审人超期前已换人不应收催办");
        assertEquals(0, notifRows("REVIEW_REMIND", pid, tester2), "重新分配后 assigned_at=now，不应判超时");
    }


    @Test
    void 阈值配0关闭催办_升级仍生效() {
        setThreshold(KEY_REMIND, "0");
        try {
            long tester = 9109L;
            long pid = createProjectWithTester(tester);
            CaseInfo c = createCase(pid, "催办关闭场景");
            assignAndRewind(c.id(), tester, 80);

            ReviewRemindResult result = reviewRemindService.scan();

            // remind-hours=0 switches the reminder level off for every project,
            // so the global counter staying at 0 is exact, not a bound.
            assertEquals(0, result.reminded(), "remind-hours=0 关闭催办级");
            assertTrue(result.escalated() >= 1, "timeout 级独立生效: " + result);
            assertEquals(0, notifRows("REVIEW_REMIND", pid, tester));
            assertEquals(1, notifRows("REVIEW_TIMEOUT", pid, 1L), "本项目恰好一条升级");
        } finally {
            setThreshold(KEY_REMIND, "24");
        }
    }

    @Test
    void 双阈值0全关闭() {
        setThreshold(KEY_REMIND, "0");
        setThreshold(KEY_TIMEOUT, "0");
        try {
            long tester = 9110L;
            long pid = createProjectWithTester(tester);
            CaseInfo c = createCase(pid, "全关闭场景");
            assignAndRewind(c.id(), tester, 200);

            ReviewRemindResult result = reviewRemindService.scan();

            // Both levels disabled short-circuits the whole scan (zero rows for
            // every project), so these global counters are exact.
            assertEquals(0, result.scanned());
            assertEquals(0, result.reminded());
            assertEquals(0, result.escalated());
            assertEquals(0, notifRows("REVIEW_REMIND", pid));
            assertEquals(0, notifRows("REVIEW_TIMEOUT", pid));
        } finally {
            setThreshold(KEY_REMIND, "24");
            setThreshold(KEY_TIMEOUT, "72");
        }
    }


    @Test
    void 项目负责人缺失时升级回退项目创建人() {
        long tester = 9111L;
        long pid = createProjectWithTester(tester);
        jdbcTemplate.update("UPDATE tp_user_project SET role = 1 WHERE project_id = ? AND user_id = 1", pid);
        CaseInfo c = createCase(pid, "负责人缺位用例");
        assignAndRewind(c.id(), tester, 80);

        ReviewRemindResult result = reviewRemindService.scan();

        assertTrue(result.escalated() >= 1, "负责人缺位时升级仍应发出: " + result);
        assertEquals(1, notifRows("REVIEW_TIMEOUT", pid, 1L), "回退创建人恰好一条升级");
    }
}
