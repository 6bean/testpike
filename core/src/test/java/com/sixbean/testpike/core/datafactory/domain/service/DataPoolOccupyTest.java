package com.sixbean.testpike.core.datafactory.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataPoolItemInfo;
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

import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DataPoolOccupyTest {

    @Autowired
    private DataFactoryService dataFactoryService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        // R37 run-owned teardown (replaces the blanket 9-table TRUNCATE): factory
        // runs carry no project_id and no FK into tp_data_factory (live schema
        // scan), so they go first by subquery over this run's factories; then the
        // project sweep walks the FK graph and the three project-keyed tables
        // without an FK into tp_project (tp_data_factory / tp_data_pool_item /
        // tp_resource_lease). Factory baseline rows are never touched.
        for (Long pid : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_data_factory_run WHERE factory_id IN"
                    + " (SELECT id FROM tp_data_factory WHERE project_id = ?)", pid);
        }
        SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                List.of("tp_data_factory", "tp_data_pool_item", "tp_resource_lease"), projectIds);
        projectIds.clear();
    }

    private long createProject(String code) {
        // Run-unique code: the shared dev DB no longer gets wiped between runs,
        // so a fixed code could collide with a crashed earlier run's leftover.
        long pid = projectService.create(1L, new ProjectCreateInput("项目" + code,
                code + "X" + System.nanoTime(), "zh-CN", "P1", null));
        projectIds.add(pid);
        return pid;
    }

    private long createEnv(long pid, String name) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    
    private long seedPoolItem(long pid, long envId) {
        String content = "{\"poolType\":\"账号\",\"sql\":\"INSERT INTO tp_project_env "
                + "(project_id, name, env_type, base_url) VALUES (" + pid
                + ", '池项造号环境', 3, 'https://pool.example.com') RETURNING name\"}";
        long fid = dataFactoryService.create(1L, new DataFactoryCreateInput(pid, envId, 2, "池项造号", content, null)).id();
        dataFactoryService.run(1L, fid);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_data_pool_item WHERE project_id = ? ORDER BY id LIMIT 1", Long.class, pid);
    }

    private int poolStatus(long itemId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tp_data_pool_item WHERE id = ?", Integer.class, itemId);
    }

    private Long lockedBy(long itemId) {
        return jdbcTemplate.queryForObject("SELECT locked_by FROM tp_data_pool_item WHERE id = ?", Long.class, itemId);
    }


    @Test
    void occupy_thenConcurrentReject_thenRelease_thenReoccupy() {
        long pid = createProject("N2A");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        assertEquals(1, poolStatus(itemId), "造数入池 status=1 空闲");

        dataFactoryService.occupy(1L, pid, itemId, 1001L);
        assertEquals(2, poolStatus(itemId), "占用后 status=2 占用中");
        assertEquals(1001L, lockedBy(itemId), "locked_by = 占用任务 batch_case id");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT locked_at FROM tp_data_pool_item WHERE id = ?", java.sql.Timestamp.class, itemId),
                "locked_at 已记录（超时释放依据）");

        ServiceException e = assertThrows(ServiceException.class,
                () -> dataFactoryService.occupy(1L, pid, itemId, 1002L));
        assertTrue(e.getMessage().contains("已被占用"), "人话: " + e.getMessage());

        dataFactoryService.release(1L, pid, itemId);
        assertEquals(1, poolStatus(itemId), "释放后回空闲");
        assertNull(lockedBy(itemId), "locked_by 清空");

        dataFactoryService.occupy(1L, pid, itemId, 2001L);
        assertEquals(2, poolStatus(itemId));
        assertEquals(2001L, lockedBy(itemId));
    }


    @Test
    void concurrentOccupy_sameItem_onlyOneWins() throws Exception {
        long pid = createProject("N2B");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final long lockedBy = 3000L + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        dataFactoryService.occupy(1L, pid, itemId, lockedBy);
                        success.incrementAndGet();
                    } catch (Exception ex) {
                        rejected.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发占用 30s 内结束");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, success.get(), "并发 8 线程仅 1 成功（原子防抢）");
        assertEquals(threads - 1, rejected.get(), "其余 7 个被拒");
        assertEquals(2, poolStatus(itemId), "最终占用中");
        assertTrue(lockedBy(itemId) >= 3000L, "占用者为 8 线程之一");
    }


    @Test
    void expiredOccupy_autoReleasedBySweeper() {
        long pid = createProject("N2C");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 4001L);
        jdbcTemplate.update("UPDATE tp_data_pool_item SET locked_at = now() - interval '2 hours' WHERE id = ?", itemId);

        int released = dataFactoryService.releaseExpired();
        // The sweeper counts every expired row on the shared dev DB; the verdict
        // is anchored to this run's item below, not to the global count.
        assertTrue(released >= 1, "超时扫描至少覆盖本运行超时项");
        assertEquals(1, poolStatus(itemId), "超时后回空闲");
        assertNull(lockedBy(itemId));
    }

    @Test
    void freshOccupy_notReleasedBySweeper() {
        long pid = createProject("N2D");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 4002L);
        // Sweeper's return counts global expired rows (shared DB); behavior under
        // test = this fresh lock must survive the sweep, asserted on our row.
        dataFactoryService.releaseExpired();
        assertEquals(2, poolStatus(itemId), "仍在占用中");
    }


    @Test
    void invalidItem_cleaned_and_releaseRejected() {
        long pid = createProject("N2E");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        jdbcTemplate.update("UPDATE tp_data_pool_item SET status = 3 WHERE id = ?", itemId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> dataFactoryService.occupy(1L, pid, itemId, 5001L));
        assertTrue(e.getMessage().contains("失效"), "人话: " + e.getMessage());

        ServiceException r = assertThrows(ServiceException.class,
                () -> dataFactoryService.release(1L, pid, itemId));
        assertTrue(r.getMessage().contains("失效"), "人话: " + r.getMessage());

        int cleaned = dataFactoryService.cleanInvalid();
        assertTrue(cleaned >= 1, "失效清理至少覆盖本运行失效项（全局计数不锚共享库）");
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_pool_item WHERE id = ?", Long.class, itemId), "失效项已删除");
    }


    @Test
    void release_idempotent_onFreeItem() {
        long pid = createProject("N2F");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.release(1L, pid, itemId);
        assertEquals(1, poolStatus(itemId));
    }


    @Test
    void occupy_otherProject_notFound() {
        long pid = createProject("N2G");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);
        long otherPid = createProject("N2H");

        ServiceException e = assertThrows(ServiceException.class,
                () -> dataFactoryService.occupy(1L, otherPid, itemId, 6001L));
        assertTrue(e.getMessage().contains("不存在"), "跨项目统一 404 不泄露存在性: " + e.getMessage());
        assertEquals(1, poolStatus(itemId), "他项目占用不影响本项状态");
    }


    private String leaseState(String ref) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT state FROM tp_resource_lease WHERE kind = 'data' AND resource_ref = ?"
                        + " ORDER BY id DESC LIMIT 1", String.class, ref);
        return states.isEmpty() ? null : states.get(0);
    }

    private String leaseHolder(String ref) {
        List<String> holders = jdbcTemplate.queryForList(
                "SELECT holder_executor_id FROM tp_resource_lease WHERE kind = 'data' AND resource_ref = ?"
                        + " AND state = 'active'", String.class, ref);
        return holders.isEmpty() ? null : holders.get(0);
    }

    @Test
    void occupy_release_maintainsLeaseRow() {
        long pid = createProject("N2J");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 8001L);
        assertEquals("active", leaseState(String.valueOf(itemId)), "占用后租约行活跃（租约表=并发权威）");
        assertEquals("8001", leaseHolder(String.valueOf(itemId)), "租约持有者=lockedBy");

        dataFactoryService.release(1L, pid, itemId);
        assertEquals("released", leaseState(String.valueOf(itemId)), "释放后租约归还");
        assertNull(leaseHolder(String.valueOf(itemId)), "无活跃租约");
    }

    @Test
    void releaseExpired_sweepsLeaseRow() {
        long pid = createProject("N2K");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 8101L);
        jdbcTemplate.update("UPDATE tp_data_pool_item SET locked_at = now() - interval '2 hours' WHERE id = ?", itemId);

        // Global sweeper count is not this run's verdict; the lease-row anchor is.
        dataFactoryService.releaseExpired();
        assertEquals("released", leaseState(String.valueOf(itemId)), "超时兜底同步归还租约行");
        dataFactoryService.occupy(1L, pid, itemId, 8102L);
        assertEquals("8102", leaseHolder(String.valueOf(itemId)));
    }

    @Test
    void expiredLease_reoccupyPreemptsHolder_andDisplaySyncs() {
        long pid = createProject("N2L");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 8201L);
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 minute'"
                + " WHERE kind = 'data' AND resource_ref = ? AND state = 'active'", String.valueOf(itemId));
        dataFactoryService.occupy(1L, pid, itemId, 8202L);
        assertEquals("8202", leaseHolder(String.valueOf(itemId)), "过期租约抢占换持有者");
        assertEquals(8202L, lockedBy(itemId), "展示面随租约权威更新");
    }


    @Test
    void occupy_release_writtenToAuditLog() {
        long pid = createProject("N2I");
        long envId = createEnv(pid, "测试环境");
        long itemId = seedPoolItem(pid, envId);

        dataFactoryService.occupy(1L, pid, itemId, 7001L);
        dataFactoryService.release(1L, pid, itemId);

        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM tp_audit_log WHERE object_type = 'data_pool_item' ORDER BY id", String.class);
        assertTrue(actions.contains("pool_occupy"), "占用事件入审计: " + actions);
        assertTrue(actions.contains("pool_release"), "释放事件入审计: " + actions);
    }
}
