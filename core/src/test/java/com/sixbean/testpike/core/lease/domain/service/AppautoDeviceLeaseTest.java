package com.sixbean.testpike.core.lease.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(LeaseNamespaceExtension.class)
class AppautoDeviceLeaseTest {

    static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";
    static final String GLOBAL_TTL_KEY = "lease.lease-ttl-seconds";

    @Autowired
    private ResourceLeaseService resourceLeaseService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    @AfterEach
    void restoreCache() {
        configCacheHolder.remove(DEVICE_TTL_KEY);
        configCacheHolder.remove(GLOBAL_TTL_KEY);
    }


    @Test
    void acquire_deviceKind_ttlFollowsDeviceKey_notGlobal(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(DEVICE_TTL_KEY, "3600");

        resourceLeaseService.acquire("device", leases.ref("emulator-5554"), 1L, null, null, "execA");

        long window = expiryWindowSeconds("device", leases.ref("emulator-5554"));
        assertTrue(window > 120 && window <= 3600,
                "device 档租约过期窗按 appauto.device-lease-ttl-seconds（实测余量 " + window + "s，全局键=60 非误读）");

        resourceLeaseService.acquire("data", leases.ref("9101"), 1L, null, null, "execA");
        long dataWindow = expiryWindowSeconds("data", leases.ref("9101"));
        assertTrue(dataWindow <= 120, "data 档不受 device 键影响（实测余量 " + dataWindow + "s，全局键=60）");
    }

    @Test
    void acquire_deviceKind_ttlKeyMissing_fallsBackToGlobal(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "600");
        configCacheHolder.remove(DEVICE_TTL_KEY);

        resourceLeaseService.acquire("device", leases.ref("emulator-5555"), 1L, null, null, "execA");
        long window = expiryWindowSeconds("device", leases.ref("emulator-5555"));
        assertTrue(window > 120 && window <= 600,
                "device 键缺失回退全局键（实测余量 " + window + "s）");
    }

    @Test
    void acquire_deviceKind_ttlKeyIllegalOrNonPositive_fallsBackToGlobal(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "600");
        for (String bad : List.of("abc", "0", "-5", " ")) {
            configCacheHolder.put(DEVICE_TTL_KEY, bad);
            resourceLeaseService.acquire("device", leases.ref("R3CB22AB") + bad, 1L, null, null, "execA");
            long window = expiryWindowSeconds("device", leases.ref("R3CB22AB") + bad);
            assertTrue(window > 120 && window <= 600,
                    "device 键非法值「" + bad + "」回退全局键（实测余量 " + window + "s）");
        }
    }

    @Test
    void renew_deviceKind_ttlFollowsDeviceKey(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "60");
        configCacheHolder.put(DEVICE_TTL_KEY, "3600");
        resourceLeaseService.acquire("device", leases.ref("emulator-5556"), 1L, null, null, "execA");
        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 second'"
                + " WHERE kind='device' AND resource_ref=?", leases.ref("emulator-5556"));

        assertTrue(resourceLeaseService.renew("device", leases.ref("emulator-5556"), "execA"));

        long window = expiryWindowSeconds("device", leases.ref("emulator-5556"));
        assertTrue(window > 120 && window <= 3600,
                "device 续租窗按本档 TTL（实测余量 " + window + "s）");
    }


    @Test
    void deviceLease_mutexAndRelease_sameStateMachine(LeaseRefNamespace leases) {
        configCacheHolder.put(GLOBAL_TTL_KEY, "1800");
        resourceLeaseService.acquire("device", leases.ref("R3CB22AC"), 1L, null, null, "execA");

        ServiceException e = assertThrows(ServiceException.class,
                () -> resourceLeaseService.acquire("device", leases.ref("R3CB22AC"), 1L, null, null, "execB"));
        assertTrue(e.getMessage().contains("持有") && e.getMessage().contains("未归还"),
                "设备占用互斥人话文案: " + e.getMessage());

        resourceLeaseService.release("device", leases.ref("R3CB22AC"), "execA");
        resourceLeaseService.acquire("device", leases.ref("R3CB22AC"), 1L, null, null, "execB");
        List<String> holders = jdbcTemplate.queryForList(
                "SELECT holder_executor_id FROM tp_resource_lease"
                        + " WHERE kind='device' AND resource_ref=? AND state='active'",
                String.class, leases.ref("R3CB22AC"));
        assertEquals(List.of("execB"), holders, "归还后他人可占（claim 即锁语义）");
    }


    @Test
    void v143_artifacts_present_in_db() {
        for (String table : List.of("tp_appauto_device", "tp_appauto_app",
                "tp_appauto_element", "tp_appauto_lease_run")) {
            Integer present = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE tablename = ?", Integer.class, table);
            assertEquals(1, present, table + " 已落库（手动 psql，禁只信代码合入）");
        }
        String seed = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class,
                DEVICE_TTL_KEY);
        assertEquals("1800", seed, "device 租期 K8 键 seed 在位（v2.56 口径 a）");
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
