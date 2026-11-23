package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.PoolProjectView;
import com.sixbean.testpike.core.executor.api.dto.PoolView;
import com.sixbean.testpike.core.executor.domain.component.PoolDispatchSupport;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PoolViewReadTest {

    @Autowired
    private PoolDispatchSupport poolDispatchSupport;
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
    private TagService tagService;

    /** Run-owned trackers: teardown deletes only the rows this suite created. */
    private final List<Long> fixtureProjectIds = new ArrayList<>();
    private final List<Long> fixtureExecutorIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (ratchet): the blanket TRUNCATE wiped other
        // suites' and chains' executors, queued tasks and projects on the
        // shared dev database. Deletes are keyed by the run trackers — no FK
        // points into tp_executor (live schema scan), so byIds is enough;
        // tp_tag_dict carries project_id without an FK, passed as the extra.
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_executor", fixtureExecutorIds);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_tag_dict"), fixtureProjectIds);
        fixtureExecutorIds.clear();
        fixtureProjectIds.clear();
    }

    private long createProject(String name) {
        long id = projectService.create(1L, new ProjectCreateInput(name,
                "V" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
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

    private long createPoolTag(long projectId, String name) {
        TagInfo tag = tagService.create(1L, new TagCreateInput(projectId, name, 7));
        return tag.id();
    }

    
    private long createBatch(long projectId, Long poolTagId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type,
                                           schedule_type, pool_tag_id)
                VALUES (?, ?, '视图批', 2, 1, 0, ?) RETURNING id
                """, Long.class, projectId, "B-view-" + System.nanoTime() % 100000, poolTagId);
    }

    private long createTask(long projectId, long envId, long caseId, long batchId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, action_json,
                                          schema_version, env_base_url)
                VALUES (?, ?, ?, 1, '{"version":"v2","steps":[]}', 'v2', 'http://demo.local')
                RETURNING id
                """, Long.class, projectId, caseId, batchId);
    }

    private long registerExecutor(String name, String groupIds, int capacity) {
        long id = executorService.register(new com.sixbean.testpike.core.executor.api.dto
                .ExecutorRegisterInput(name, groupIds, null, "web", "chromium", capacity, "1.0.0", 0));
        fixtureExecutorIds.add(id);
        return id;
    }

    private void forceOffline(long executorId) {
        jdbcTemplate.update("UPDATE tp_executor SET last_heartbeat = now() - interval '10 minutes'"
                + " WHERE id = ?", executorId);
    }

    private PoolView poolOf(List<PoolView> views, long poolTagId) {
        Optional<PoolView> hit = views.stream()
                .filter(v -> v.poolId() == poolTagId).findFirst();
        assertTrue(hit.isPresent(), "type=7 池标签必须在池视图出行，实际池=" + views.stream()
                .map(PoolView::poolId).toList());
        return hit.get();
    }

    @Test
    void 池行仅出type7标签_非池型标签禁出行() {
        long pid = createProject("视图池项目");
        long poolTag = createPoolTag(pid, "web回归池");
        TagInfo plain = tagService.create(1L, new TagCreateInput(pid, "普通功能标签", 2));

        List<PoolView> views = poolDispatchSupport.listPoolViews();
        PoolView pool = poolOf(views, poolTag);
        assertEquals(poolTag, pool.poolId());
        assertEquals("web回归池", pool.poolName());
        assertEquals(pid, pool.projectId());
        // The blanket global equality only held because the table was emptied;
        // on the shared dev database the contract anchors on this fixture's
        // two faces: type=7 pool row must appear, type!=7 tag must never.
        assertTrue(views.stream().noneMatch(v -> v.poolId() == plain.id()),
                "type≠7 标签禁出池行，实际池=" + views.stream().map(PoolView::poolId).toList());
    }

    @Test
    void 成员在线容量对账_离线成员算成员不算在线() {
        long pid = createProject("成员对账");
        long poolTag = createPoolTag(pid, "容量池");
        long online1 = registerExecutor("ex-v1", String.valueOf(poolTag), 2);
        registerExecutor("ex-v2", "88," + poolTag + ",99", 3);
        long offline = registerExecutor("ex-v3", String.valueOf(poolTag), 10);
        forceOffline(offline);
        registerExecutor("ex-out", "77", 5);

        PoolView pool = poolOf(poolDispatchSupport.listPoolViews(), poolTag);
        assertEquals(3, pool.memberCount(), "成员=group_ids 含池标签（在线+离线）");
        assertEquals(2, pool.onlineCount(), "在线数=心跳新鲜口径");
        assertEquals(5, pool.capacitySum(), "容量合计仅在线成员（2+3，离线 cap10 不计）");
        assertTrue(online1 > 0);
    }

    @Test
    void 项目明细含配额槽位在跑与排队_闸同源() {
        long pidX = createProject("配额视图X");
        long envX = createEnv(pidX);
        long caseX = createCase(pidX, "X用例");
        long poolTag = createPoolTag(pidX, "配额视图池");
        long exIn = registerExecutor("ex-q", String.valueOf(poolTag), 4);
        jdbcTemplate.update("UPDATE tp_project SET pool_quota_pct = 50 WHERE id = ?", pidX);
        long batchX = createBatch(pidX, poolTag);
        createTask(pidX, envX, caseX, batchX);
        createTask(pidX, envX, caseX, batchX);
        long plainBatch = createBatch(pidX, null);
        createTask(pidX, envX, caseX, plainBatch);
        long cancelledBatch = createBatch(pidX, poolTag);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 5 WHERE id = ?", cancelledBatch);
        createTask(pidX, envX, caseX, cancelledBatch);

        PoolView pool = poolOf(poolDispatchSupport.listPoolViews(), poolTag);
        assertEquals(2, pool.pendingTaskCount(), "排队数仅计池挂未取消批次的待派发任务");
        assertEquals(1, pool.projects().size(), "有配额或有池内任务的项目才出明细行");
        PoolProjectView rowX = pool.projects().get(0);
        assertEquals(pidX, rowX.projectId());
        assertEquals("配额视图X", rowX.projectName());
        assertEquals(50, rowX.quotaPct());
        assertEquals(2, rowX.slotCap(), "槽位=capSum(4)×50%与派发闸同源");
        assertEquals(0, rowX.runningInPool());
        assertEquals(2, rowX.pendingCount());
        assertFalse(pool.runningTaskCount() < 0);
        assertEquals(poolSlotCapOfPool(poolTag, 50), rowX.slotCap());
        assertTrue(exIn > 0);
    }

    private int poolSlotCapOfPool(long poolTag, int pct) {
        return poolDispatchSupport.poolSlotCap(poolTag, pct);
    }

    @Test
    void 空池必须出行_零成员零容量排队归项目() {
        long pid = createProject("空池项目");
        long poolTag = createPoolTag(pid, "幽灵视图池");

        PoolView pool = poolOf(poolDispatchSupport.listPoolViews(), poolTag);
        assertEquals(0, pool.memberCount());
        assertEquals(0, pool.onlineCount());
        assertEquals(0, pool.capacitySum());
        assertEquals(0, pool.pendingTaskCount());
        assertTrue(pool.projects().isEmpty(), "无配额无任务的项目不出明细行");
    }

    @Test
    void 未设配额项目在池内跑任务出行槽位不限() {
        long pid = createProject("无配额跑批");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "无配用例");
        long poolTag = createPoolTag(pid, "无配额池");
        long batch = createBatch(pid, poolTag);
        createTask(pid, envId, caseId, batch);
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 2 WHERE batch_id = ?", batch);

        PoolView pool = poolOf(poolDispatchSupport.listPoolViews(), poolTag);
        assertEquals(1, pool.runningTaskCount());
        assertEquals(1, pool.projects().size());
        PoolProjectView row = pool.projects().get(0);
        assertNull(row.quotaPct(), "未设配额=null 不限");
        assertEquals(-1, row.slotCap(), "不限槽位以 -1 呈现（前端显示不限）");
        assertEquals(1, row.runningInPool());
    }
}
