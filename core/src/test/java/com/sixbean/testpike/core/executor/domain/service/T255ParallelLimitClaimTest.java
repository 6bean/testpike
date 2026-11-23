package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class T255ParallelLimitClaimTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ConfigService configService;

    // Fixture-scoped teardown : fixed-name projects fall through
    // the shared name-family sweep, executors drop by exact names. The two
    // config keys are still reset to 0 (= unlimited) through the ConfigService
    // write chain so a stale limit from another suite can never gate this one.
    private static final List<String> FIXTURE_PROJECT_NAMES =
            List.of("并行全局闸", "项目闸A", "项目闸B", "不误杀", "并发无超卖", "水位对账");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name IN "
                + "('ex-global','ex-project','ex-nokill','ex-race','ex-water')");
        setLimitKey("exec.parallel.max", "0");
        setLimitKey("exec.parallel.per-project", "0");
    }

    
    private void setLimitKey(String key, String value) {
        ConfigInfo info = configService.list("系统").stream()
                .filter(c -> key.equals(c.configKey())).findFirst()
                .orElseGet(() -> configService.create(1L, new ConfigInput(key, value,
                        "系统", "int", 0, "I5 并行度上限键（0=不限）")));
        configService.update(1L, info.id(), new ConfigInput(key, value,
                info.groupName(), info.valueType(), info.encryptFlag(), info.description()));
    }

    private long createProject(String name) {
        return projectService.create(1L, new ProjectCreateInput(name,
                "P" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title,
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    private long createTask(long projectId, long envId, long caseId) {
        return execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2", ACTION_JSON, envId));
    }

    private long registerExecutor(String name, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    private ClaimTaskView claimOrNull(long exId) {
        return execTaskService.claim(exId);
    }

    @Test
    void 全局上限_在跑满额新领排队_热更即时放行_0不限全开() {
        long pid = createProject("并行全局闸");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "全局闸用例");
        for (int i = 0; i < 4; i++) {
            createTask(pid, envId, caseId);
        }
        long exId = registerExecutor("ex-global", 4);

        setLimitKey("exec.parallel.max", "1");
        assertNotNull(claimOrNull(exId), "在跑 0 < 上限 1 必须可领");
        assertNull(claimOrNull(exId), "在跑 1 ≥ 上限 1 新领必须排队（滞留待派发）");
        assertEquals(3L, pendingCount(), "闸命中=只挡新领，待派发队列零变化");

        setLimitKey("exec.parallel.max", "2");
        assertNotNull(claimOrNull(exId), "热更上调后即时放行");
        setLimitKey("exec.parallel.max", "0");
        assertNotNull(claimOrNull(exId), "0=不限必须全放行");
        assertNotNull(claimOrNull(exId), "0=不限第 4 条同样放行");
        assertEquals(4L, runningCount(), "4 条全部进入执行中");
    }

    @Test
    void 项目闸_命中项目仅该候选跳过_其它项目仍可领() {
        long pidA = createProject("项目闸A");
        long envA = createEnv(pidA);
        long caseA = createCase(pidA, "A用例");
        long pidB = createProject("项目闸B");
        long envB = createEnv(pidB);
        long caseB = createCase(pidB, "B用例");
        createTask(pidA, envA, caseA);
        createTask(pidB, envB, caseB);
        createTask(pidA, envA, caseA);
        createTask(pidB, envB, caseB);
        long exId = registerExecutor("ex-project", 4);

        setLimitKey("exec.parallel.per-project", "1");
        ClaimTaskView first = claimOrNull(exId);
        assertNotNull(first, "首条必可领");
        ClaimTaskView second = claimOrNull(exId);
        assertNotNull(second, "项目闸命中仅跳该候选：另一项目候选仍可领（禁全局停摆）");
        assertTrue(projectOfTask(first.taskId()) != projectOfTask(second.taskId()),
                "两笔领取必须分属两项目（项目闸=逐候选跳，禁全局停摆）");
        assertNull(claimOrNull(exId), "各项目在跑均达上限时新领必须排队");
        assertEquals(2L, runningCount(), "闸只挡新领，在跑数停在 2");

        setLimitKey("exec.parallel.per-project", "2");
        assertNotNull(claimOrNull(exId), "上限 1→2 热更后第三条即时放行");
    }

    @Test
    void 上限调低不误杀已领_在跑任务回传链路正常() {
        long pid = createProject("不误杀");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "回传用例");
        long runningTask = createTask(pid, envId, caseId);
        createTask(pid, envId, caseId);
        long exId = registerExecutor("ex-nokill", 4);

        setLimitKey("exec.parallel.max", "2");
        ClaimTaskView view = claimOrNull(exId);
        assertNotNull(view);
        setLimitKey("exec.parallel.max", "1");
        assertEquals(2, taskStatus(runningTask), "闸只读不写：已领任务必须仍在执行中");
        assertNull(claimOrNull(exId), "在跑 1 ≥ 上限 1 新领排队");
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "pass", null, null, null, null, null, null));
        assertEquals(3, taskStatus(runningTask), "回传成功必须落终态 3");
        assertNotNull(claimOrNull(exId), "终态释放后新领恢复");
    }

    @Test
    void 并发双领同任务_条件更新单胜出无超卖() throws Exception {
        long pid = createProject("并发无超卖");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "竞态用例");
        createTask(pid, envId, caseId);
        long exId = registerExecutor("ex-race", 4);

        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            Future<ClaimTaskView> f1 = pool.submit(() -> {
                start.await();
                return execTaskService.claim(exId);
            });
            Future<ClaimTaskView> f2 = pool.submit(() -> {
                start.await();
                return execTaskService.claim(exId);
            });
            start.countDown();
            ClaimTaskView v1 = f1.get(30, TimeUnit.SECONDS);
            ClaimTaskView v2 = f2.get(30, TimeUnit.SECONDS);
            assertTrue((v1 == null) ^ (v2 == null), "并发双领必须单胜出: v1="
                    + (v1 == null ? "null" : v1.taskId()) + " v2=" + (v2 == null ? "null" : v2.taskId()));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, runningCount(), "在跑落库必须恰 1（无超卖）");
    }

    @Test
    void 水位视图_全局与项目在跑数对账() {
        long pid = createProject("水位对账");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "水位用例");
        createTask(pid, envId, caseId);
        createTask(pid, envId, caseId);
        long other = createProject("水位对账B");
        long envO = createEnv(other);
        long caseO = createCase(other, "B水位用例");
        createTask(other, envO, caseO);
        long exId = registerExecutor("ex-water", 4);
        setLimitKey("exec.parallel.max", "10");
        setLimitKey("exec.parallel.per-project", "3");
        assertNotNull(claimOrNull(exId));
        assertNotNull(claimOrNull(exId));
        assertNotNull(claimOrNull(exId));

        ParallelWaterLevelView view = execTaskService.parallelWaterLevel();
        assertEquals(3L, view.globalRunning(), "全局在跑=3（与任务表对账）");
        assertEquals(10, view.globalMax(), "全局上限即时读（K8）");
        assertEquals(3, view.perProjectMax(), "项目上限即时读（K8）");
        long pidRunning = view.projects().stream()
                .filter(p -> p.projectId() == pid).findFirst().orElseThrow().running();
        assertEquals(2L, pidRunning, "项目A在跑=2");
        long otherRunning = view.projects().stream()
                .filter(p -> p.projectId() == other).findFirst().orElseThrow().running();
        assertEquals(1L, otherRunning, "项目B在跑=1");
    }

    private long pendingCount() {
        return FixtureTaskCounters.countByStatus(jdbcTemplate, 1, FIXTURE_PROJECT_NAMES);
    }

    private long runningCount() {
        return FixtureTaskCounters.countByStatus(jdbcTemplate, 2, FIXTURE_PROJECT_NAMES);
    }

    private long projectOfTask(long taskId) {
        Long p = jdbcTemplate.queryForObject("SELECT project_id FROM tp_exec_task WHERE id = ?",
                Long.class, taskId);
        return p == null ? -1 : p;
    }

    private int taskStatus(long taskId) {
        Integer s = jdbcTemplate.queryForObject("SELECT status FROM tp_exec_task WHERE id = ?",
                Integer.class, taskId);
        return s == null ? -1 : s;
    }
}
