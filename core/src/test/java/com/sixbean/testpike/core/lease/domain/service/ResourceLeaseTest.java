package com.sixbean.testpike.core.lease.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.LeaseNamespaceExtension;
import com.sixbean.testpike.core.support.LeaseRefNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(LeaseNamespaceExtension.class)
class ResourceLeaseTest {

    @Autowired
    private ResourceLeaseService resourceLeaseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;



    @Test
    void acquire_dataKind_secondAcquireRejected_releaseIdempotent(LeaseRefNamespace leases) {
        resourceLeaseService.acquire("data", leases.ref("4201"), 1L, null, null, "1001");

        ServiceException e = assertThrows(ServiceException.class,
                () -> resourceLeaseService.acquire("data", leases.ref("4201"), 1L, null, null, "1002"));
        assertTrue(e.getMessage().contains("持有") && e.getMessage().contains("未归还"),
                "人话「持有者未归还」: " + e.getMessage());

        resourceLeaseService.release("data", leases.ref("4201"), "1001");
        resourceLeaseService.release("data", leases.ref("4201"), "1001");

        resourceLeaseService.acquire("data", leases.ref("4201"), 1L, null, null, "2001");
        assertEquals("2001", currentHolder("data", leases.ref("4201")));
    }

    @Test
    void acquire_sessionKind_sameMutex_differentRefIndependent(LeaseRefNamespace leases) {
        resourceLeaseService.acquire("session", leases.ref("7:3:tok-a"), 7L, 3L, 11L, "execA");
        ServiceException e = assertThrows(ServiceException.class,
                () -> resourceLeaseService.acquire("session", leases.ref("7:3:tok-a"), 7L, 3L, 11L, "execB"));
        assertTrue(e.getMessage().contains("持有"), "会话租约同表同互斥: " + e.getMessage());

        resourceLeaseService.acquire("session", leases.ref("7:3:tok-b"), 7L, 3L, 11L, "execB");
        resourceLeaseService.acquire("data", leases.ref("7:3:tok-a"), 7L, 3L, 11L, "execB");
    }


    @Test
    void expiredLease_preemptableByNewHolder(LeaseRefNamespace leases) {
        resourceLeaseService.acquire("data", leases.ref("5301"), 1L, null, null, "3001");
        expireNow("data", leases.ref("5301"));

        resourceLeaseService.acquire("data", leases.ref("5301"), 1L, null, null, "3002");
        assertEquals("3002", currentHolder("data", leases.ref("5301")), "过期租约换持有者");
    }

    @Test
    void sweepExpired_marksOnlyExpiredActive(LeaseRefNamespace leases) {
        resourceLeaseService.acquire("data", leases.ref("5401"), 1L, null, null, "4001");
        resourceLeaseService.acquire("session", leases.ref("1:1:tok-x"), 1L, 1L, null, "execA");
        expireNow("data", leases.ref("5401"));

        List<String> expired = resourceLeaseService.sweepExpired("data");
        // Re-anchored on this run's namespace segment: the shared table may hold rows owned by
        // other suites. Product contracts kept: this run's expired data lease is swept and the
        // unexpired session lease is not.
        assertEquals(List.of(leases.ref("5401")),
                expired.stream().filter(r -> r.startsWith(leases.prefix())).toList(),
                "sweep reclaims this run's expired data lease");
        assertFalse(expired.contains(leases.ref("1:1:tok-x")), "sweep must not touch session kind");

        assertEquals("execA", currentHolder("session", leases.ref("1:1:tok-x")));
        resourceLeaseService.acquire("data", leases.ref("5401"), 1L, null, null, "4002");
    }


    @Test
    void concurrentAcquire_sameResource_onlyOneWins(LeaseRefNamespace leases) throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final String holder = "600" + i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        resourceLeaseService.acquire("data", leases.ref("6001"), 1L, null, null, holder);
                        success.incrementAndGet();
                    } catch (Exception ex) {
                        rejected.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发租约 30s 内结束");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, success.get(), "并发 8 线程仅 1 胜（活跃唯一索引+过期抢占 upsert 原子）");
        assertEquals(threads - 1, rejected.get(), "其余 7 个被拒");
    }


    @Test
    void renew_onlyCurrentHolder_extendsExpiry(LeaseRefNamespace leases) {
        resourceLeaseService.acquire("session", leases.ref("9:9:tok-r"), 9L, 9L, null, "execA");
        assertFalse(resourceLeaseService.renew("session", leases.ref("9:9:tok-r"), "execB"), "非持有者续租拒绝");
        assertTrue(resourceLeaseService.renew("session", leases.ref("9:9:tok-r"), "execA"), "持有者续租成功");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT renew_count FROM tp_resource_lease WHERE kind='session' AND resource_ref=? AND state='active'",
                Integer.class, leases.ref("9:9:tok-r"));
        assertEquals(1, count, "renew_count 递增");
    }


    @Test
    void v122_artifacts_present_in_db() {
        Integer table = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = 'tp_resource_lease'", Integer.class);
        assertEquals(1, table, "tp_resource_lease 表已落库（手动 psql，禁只信代码合入）");

        Integer uniqIndex = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_resource_lease_active'", Integer.class);
        assertEquals(1, uniqIndex, "活跃态部分唯一索引在位");

        String ttl = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'lease.lease-ttl-seconds'", String.class);
        assertEquals("1800", ttl, "统一租期 K8 键 seed 在位（v2.56 口径 a）");
    }


    private String currentHolder(String kind, String ref) {
        List<String> holders = jdbcTemplate.queryForList(
                "SELECT holder_executor_id FROM tp_resource_lease WHERE kind=? AND resource_ref=? AND state='active'",
                String.class, kind, ref);
        return holders.isEmpty() ? null : holders.get(0);
    }

    
    private void expireNow(String kind, String ref) {
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 minute'"
                + " WHERE kind=? AND resource_ref=? AND state='active'", kind, ref);
    }
}
