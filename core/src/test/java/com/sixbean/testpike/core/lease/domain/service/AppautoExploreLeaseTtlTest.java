package com.sixbean.testpike.core.lease.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.LeaseNamespaceExtension;
import com.sixbean.testpike.core.support.LeaseRefNamespace;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(LeaseNamespaceExtension.class)
class AppautoExploreLeaseTtlTest {

    static final String EXPLORE_TTL_KEY = "appauto.lease-ttl-explore";
    static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";
    static final String GLOBAL_TTL_KEY = "lease.lease-ttl-seconds";

    
    static final String KIND_EXPLORE = "explore";

    @Autowired
    private ResourceLeaseService resourceLeaseService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @AfterEach
    void restoreCache() {
        configCacheHolder.remove(EXPLORE_TTL_KEY);
        configCacheHolder.remove(DEVICE_TTL_KEY);
        configCacheHolder.remove(GLOBAL_TTL_KEY);
    }


    @Test
    void acquire_exploreKind_ttlFollowsExploreKey_notDeviceOrGlobal(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(DEVICE_TTL_KEY, "300");
        configCacheHolder.put(EXPLORE_TTL_KEY, "3600");

        resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("emulator-5554"), 1L, null, null, "execA");

        long window = expiryWindowSeconds(KIND_EXPLORE, leases.ref("emulator-5554"));
        assertTrue(window > 600 && window <= 3600,
                "explore 档租约窗按 appauto.lease-ttl-explore（实测余量 " + window
                        + "s，设备档=300/全局=60 非误读）");
    }

    @Test
    void renew_exploreKind_ttlFollowsExploreKey(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(EXPLORE_TTL_KEY, "3600");
        resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("emulator-5556"), 1L, null, null, "execA");
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                + " WHERE kind=? AND resource_ref=?", KIND_EXPLORE, leases.ref("emulator-5556"));

        assertTrue(resourceLeaseService.renew(KIND_EXPLORE, leases.ref("emulator-5556"), "execA"));

        long window = expiryWindowSeconds(KIND_EXPLORE, leases.ref("emulator-5556"));
        assertTrue(window > 600 && window <= 3600,
                "explore 续租窗按本档 TTL（实测余量 " + window + "s）");
    }


    @Test
    void acquire_exploreKind_missingOrIllegalExploreKey_fallsBackToDeviceKey(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(DEVICE_TTL_KEY, "1800");
        for (String bad : List.of("abc", "0", "-5", " ")) {
            configCacheHolder.put(EXPLORE_TTL_KEY, bad);
            resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("R3CB22AX") + bad, 1L, null, null, "execA");
            long window = expiryWindowSeconds(KIND_EXPLORE, leases.ref("R3CB22AX") + bad);
            assertTrue(window > 120 && window <= 1800,
                    "探索键非法值「" + bad + "」应回退设备档键（实测余量 " + window + "s，非全局 60）");
        }
        configCacheHolder.remove(EXPLORE_TTL_KEY);
        resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("R3CB22A9"), 1L, null, null, "execA");
        long window = expiryWindowSeconds(KIND_EXPLORE, leases.ref("R3CB22A9"));
        assertTrue(window > 120 && window <= 1800,
                "探索键缺失应回退设备档键（实测余量 " + window + "s）");
    }

    @Test
    void acquire_exploreKind_bothTiersMissing_fallsBackToGlobal(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "600");
        configCacheHolder.remove(EXPLORE_TTL_KEY);
        configCacheHolder.remove(DEVICE_TTL_KEY);

        resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("emulator-5557"), 1L, null, null, "execA");
        long window = expiryWindowSeconds(KIND_EXPLORE, leases.ref("emulator-5557"));
        assertTrue(window > 120 && window <= 600,
                "探索+设备双缺失回退全局键（实测余量 " + window + "s）");
    }


    @Test
    void exploreKey_absentFromDeviceAndDataTiers_zeroDrift(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(DEVICE_TTL_KEY, "300");
        configCacheHolder.put(EXPLORE_TTL_KEY, "7200");

        resourceLeaseService.acquire("device", leases.ref("R3CB22AB"), 1L, null, null, "execA");
        long deviceWindow = expiryWindowSeconds("device", leases.ref("R3CB22AB"));
        assertTrue(deviceWindow > 60 && deviceWindow <= 300,
                "device 档不受探索键影响（实测余量 " + deviceWindow + "s，仍按设备档=300）");

        resourceLeaseService.acquire("data", leases.ref("9101"), 1L, null, null, "execA");
        long dataWindow = expiryWindowSeconds("data", leases.ref("9101"));
        assertTrue(dataWindow <= 120, "data 档恒等全局键（实测余量 " + dataWindow + "s）");
    }


    @Test
    void exploreKind_mutexIndependentFromDeviceKind_sameTable(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "1800");
        resourceLeaseService.acquire("device", leases.ref("R3CB22AC"), 1L, null, null, "execA");
        resourceLeaseService.acquire(KIND_EXPLORE, leases.ref("R3CB22AC"), 1L, null, null, "execB");

        List<String> holders = jdbcTemplate.queryForList(
                "SELECT holder_executor_id FROM tp_resource_lease"
                        + " WHERE resource_ref=? AND state='active' ORDER BY kind",
                String.class, leases.ref("R3CB22AC"));
        assertEquals(List.of("execA", "execB"), holders,
                "kind 维度隔离：同 serial 探索租约与跑测租约各自独立活跃");
    }


    @Test
    void v196_keys_present_in_db() {
        for (String key : List.of(EXPLORE_TTL_KEY, "ai.explore.appauto")) {
            String value = jdbcTemplate.queryForObject(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                    String.class, key);
            assertNotNull(value, key + " 已落库（V196 手动 psql 实证）");
        }
        // seed default flipped to on by the AI default-on batch (2026-11-02 expansion): dev DB and schema-init both 'true'
        assertEquals("true", jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'ai.explore.appauto'",
                String.class), "App 探索开关出厂默认开（翻值批；§14.3 关态零残留语义随显式关态夹具验证）");
    }


    
    private long expiryWindowSeconds(String kind, String ref) {
        Integer window = jdbcTemplate.queryForObject("""
                SELECT floor(extract(epoch FROM (expires_at - now())))::int
                FROM tp_resource_lease
                WHERE kind=? AND resource_ref=? AND state='active'
                """, Integer.class, kind, ref);
        assertNotNull(window, "活跃租约行在位: " + kind + "/" + ref);
        return window;
    }
}
