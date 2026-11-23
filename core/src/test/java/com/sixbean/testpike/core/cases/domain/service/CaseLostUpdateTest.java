package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseLostUpdateTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> lostUpdateTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the FK-tree sweep
        // reaches this suite's cases, steps, versions, scripts and audit rows.
        ProjectFixtureRig.sweep(jdbcTemplate, lostUpdateTrackedProjects);
        lostUpdateTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                lostUpdateTrackedProjects, "项目" + code, code);
    }

    private List<CaseStepInput> steps() {
        return List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "登录成功提示", 1));
    }

    private CaseCreateInput input(long projectId, String title) {
        return new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页",
                1, 2, "web", 1, null, 1, null, steps());
    }


    @Test
    void concurrentUpdate_differentFields_bothPersisted_noLostUpdate() throws Exception {
        long pid = createProject("lu1");
        CaseInfo created = caseService.create(1L, input(pid, "初始标题"));

        CaseInfo snapshot = caseService.get(created.id());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> failA = new AtomicReference<>();
        AtomicReference<Throwable> failB = new AtomicReference<>();

        Thread a = new Thread(() -> {
            try {
                barrier.await();
                caseService.update(1L, snapshot.id(),
                        new CaseUpdateInput("A并发改的标题", null, null, null, null, null, null, null),
                        snapshot.version());
            } catch (Throwable t) {
                failA.set(t);
            }
        });
        Thread b = new Thread(() -> {
            try {
                barrier.await();
                caseService.update(1L, snapshot.id(),
                        new CaseUpdateInput(null, "B并发改的前置", null, null, null, null, null, null),
                        snapshot.version());
            } catch (Throwable t) {
                failB.set(t);
            }
        });
        a.start();
        b.start();
        a.join(10000);
        b.join(10000);

        assertNull(failA.get(), "A（改 title）不得失败：" + failA.get());
        assertNull(failB.get(), "B（改 precondition）不得失败——不同字段禁互相 clobber：" + failB.get());

        CaseInfo after = caseService.get(created.id());
        assertEquals("A并发改的标题", after.title(), "title 必须为 A 的值");
        assertEquals("B并发改的前置", after.precondition(), "precondition 必须为 B 的值（列级 UPDATE 非整行覆盖）");
        assertEquals(3, after.version(), "两次更新 version 各+1（1→3）");
    }


    @Test
    void concurrentUpdate_sameField_stillRejectsStaleWriter() throws Exception {
        long pid = createProject("lu2");
        CaseInfo created = caseService.create(1L, input(pid, "初始标题"));
        CaseInfo snapshot = caseService.get(created.id());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> thrownA = new AtomicReference<>();
        AtomicReference<Throwable> thrownB = new AtomicReference<>();

        Thread a = new Thread(() -> submitTitle(barrier, snapshot, "甲标题", thrownA));
        Thread b = new Thread(() -> submitTitle(barrier, snapshot, "乙标题", thrownB));
        a.start();
        b.start();
        a.join(10000);
        b.join(10000);
        assertFalse(a.isAlive() || b.isAlive(), "双线程须在 10s 内完成");

        long failures = (thrownA.get() == null ? 0 : 1) + (thrownB.get() == null ? 0 : 1);
        assertEquals(1, failures, "同字段并发必须恰有一方被拒，实际 A=" + thrownA.get() + " B=" + thrownB.get());
        Throwable loser = thrownA.get() != null ? thrownA.get() : thrownB.get();
        assertEquals(OptimisticLockingException.class, loser.getClass(),
                "落败方须 40901 乐观锁冲突（禁降级为无异常覆盖）");

        CaseInfo after = caseService.get(created.id());
        assertEquals(2, after.version(), "仅一次更新生效 version=2");
        assertTrue(List.of("甲标题", "乙标题").contains(after.title()));
    }

    private void submitTitle(CyclicBarrier barrier, CaseInfo snapshot, String title,
                             AtomicReference<Throwable> sink) {
        try {
            barrier.await();
            caseService.update(1L, snapshot.id(),
                    new CaseUpdateInput(title, null, null, null, null, null, null, null),
                    snapshot.version());
        } catch (Throwable t) {
            sink.set(t);
        }
    }
}
