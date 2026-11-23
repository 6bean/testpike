package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseConcurrentCreateTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private static final int THREADS = 8;
    private static final int ROUNDS_PER_THREAD = 8;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> raceTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep purges this suite's cases, steps, scripts, version snapshots,
        // exec tasks, defects and audit rows.
        ProjectFixtureRig.sweep(jdbcTemplate, raceTrackedProjects);
        raceTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                raceTrackedProjects, "项目" + code, code);
    }

    private CaseCreateInput input(long projectId, String title) {
        return new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页",
                1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "登录成功提示", 1)));
    }


    @Test
    void concurrentCreate_sameProject_allSucceedWithUniqueCaseNos() throws Exception {
        long pid = createProject("race1");
        int total = THREADS * ROUNDS_PER_THREAD;
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> caseNos = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Thread> threads = IntStream.range(0, THREADS).mapToObj(t -> new Thread(() -> {
            try {
                ready.countDown();
                start.await();
                for (int r = 0; r < ROUNDS_PER_THREAD; r++) {
                    CaseInfo created = caseService.create(1L, input(pid, "并发用例-t" + t + "-r" + r));
                    caseNos.add(created.caseNo());
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        })).toList();
        threads.forEach(Thread::start);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程须全部就位");
        start.countDown();
        for (Thread th : threads) {
            th.join(60000);
        }
        assertTrue(threads.stream().noneMatch(Thread::isAlive), "全部线程须在 60s 内完成");

        assertNull(failure.get(), "并发创建不得因编号撞 UNIQUE 失败（根因=MAX(seq)+1 与 INSERT 非原子）: "
                + failure.get());
        assertEquals(total, caseNos.size(), "编号必须互异（撞号=并发竞态复发）");

        Set<Integer> seqs = caseNos.stream()
                .map(no -> Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)))
                .collect(Collectors.toSet());
        assertEquals(total, seqs.size(), "序号集合须无重复");
        IntStream.rangeClosed(1, total).forEach(s ->
                assertTrue(seqs.contains(s), "序号 " + s + " 缺失（取号竞态导致空洞）"));

        Long dbCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ?", Long.class, pid);
        assertEquals((long) total, dbCount, "落库行数须等于成功创建数");
    }


    @Test
    void concurrentCreate_differentProjects_noFalseConflict() throws Exception {
        long pidA = createProject("raceA");
        long pidB = createProject("raceB");
        AtomicReference<Throwable> failA = new AtomicReference<>();
        AtomicReference<Throwable> failB = new AtomicReference<>();
        Thread a = new Thread(() -> raceCreate(pidA, "甲", failA));
        Thread b = new Thread(() -> raceCreate(pidB, "乙", failB));
        a.start();
        b.start();
        a.join(60000);
        b.join(60000);
        assertNull(failA.get(), "项目 A 并发创建不得失败: " + failA.get());
        assertNull(failB.get(), "项目 B 并发创建不得失败（跨项目锁不得误伤）: " + failB.get());
        assertEquals(ROUNDS_PER_THREAD, countCases(pidA));
        assertEquals(ROUNDS_PER_THREAD, countCases(pidB));
    }

    private void raceCreate(long pid, String tag, AtomicReference<Throwable> failure) {
        try {
            for (int r = 0; r < ROUNDS_PER_THREAD; r++) {
                caseService.create(1L, input(pid, "跨项目并发-" + tag + "-" + r));
            }
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
        }
    }

    private long countCases(long pid) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ?", Long.class, pid);
        return n == null ? 0 : n;
    }


    @Test
    void caseNoUniqueConstraint_stillEnforced() {
        long pid = createProject("race2");
        caseService.create(1L, input(pid, "先占用例"));
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO tp_test_case (case_no, project_id, title, priority, confidence, end_type,
                    status, source, created_by)
                VALUES ('TC-race2-001', ?, '撞号插入', 1, 2, 'web', 1, 1, 1)
                """, pid), "case_no UNIQUE 约束是最后防线，不得为绕竞态而移除");
    }
}
