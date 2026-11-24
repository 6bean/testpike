package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;
import com.sixbean.testpike.core.support.FixtureInserts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoTakeoverServiceTest {

    @Autowired
    private AppautoTakeoverService takeoverService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long requesterId;
    private long occupierId;
    private long deviceId;
    private long batchId;
    private long taskId;
    private long leaseRunId;

    @BeforeEach
    void setUp() {
        String code = "tkov" + System.nanoTime();
        requesterId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '接管申请人', 2, 1)", "tkov-req-" + code);
        occupierId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '占用派发人', 3, 1)", "tkov-occ-" + code);
        projectId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status, created_by) VALUES (?, ?, 1, ?)",
                "接管测试项目-" + code, code, requesterId);
        deviceId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                        + " VALUES (?, '接管测试机', ?, 'ADB')", projectId, "tkov-" + code);
        batchId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type,"
                        + " created_by) VALUES (?, ?, '接管测试批', 2, 1, ?)",
                projectId, "tkov-" + code, occupierId);
        taskId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_exec_task (project_id, case_id, batch_id, status,"
                        + " schema_version, action_json, task_type)"
                        + " VALUES (?, 0, ?, 2, 'appauto-v1', '{\"kind\":\"app-auto-run\",\"steps\":[]}', 6)",
                projectId, batchId);
        leaseRunId = FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_appauto_lease_run (device_id, project_id, batch_id,"
                        + " holder_executor_id, state, acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, 'tkov-ex-1', 'ACTIVE', now(), now() + interval '1 hour')",
                deviceId, projectId, batchId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_appauto_takeover_request WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE id = ?", batchId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id IN (?, ?)", requesterId, occupierId);
    }

    private long request(String reason) {
        AppautoTakeoverView view = takeoverService.request(
                new TakeoverCreateInput(deviceId, taskId, requesterId, reason));
        return view.id();
    }


    @Test
    void requestLandsPendingWithOccupierResolvedAndNotifiesOccupier() {
        long id = request("帮看下设备卡在哪一步");
        AppautoTakeoverView view = takeoverService.get(id);
        assertEquals("pending", view.state());
        assertEquals(occupierId, view.occupierId(), "占用者=活跃占用批的派发人（快照入列）");
        assertEquals(projectId, view.projectId(), "项目归属由设备行权威反查");
        assertEquals(taskId, view.taskId());
        assertEquals("帮看下设备卡在哪一步", view.reason());
        assertNotNull(view.expiresAt(), "待批窗终点=K8 键现算落列");
        Integer notes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE user_id = ? AND event_type ="
                        + " 'TAKEOVER_REQUEST' AND dedup_key = ?",
                Integer.class, occupierId, "TAKEOVER_REQ:" + id);
        assertEquals(1, notes, "占用者收接管申请站内信恰 1 行且 dedup 命中");
        Integer leaseUntouched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_lease_run WHERE id = ?"
                        + " AND state = 'ACTIVE' AND holder_executor_id = 'tkov-ex-1'",
                Integer.class, leaseRunId);
        assertEquals(1, leaseUntouched, "接管申请不改租约/流水持有者");
    }


    @Test
    void requestRejectedWhenDeviceIdle() {
        jdbcTemplate.update("DELETE FROM tp_appauto_lease_run WHERE device_id = ?", deviceId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> request("空闲时也拒"));
        assertTrue(ex.getMessage().contains("空闲"), "空闲=人话引导直连监看: " + ex.getMessage());
        assertEquals(0, takeoverCount(), "空闲拒单零落库");
    }

    @Test
    void requestRejectedWhenDeviceMissing() {
        assertThrows(ServiceException.class, () -> takeoverService.request(
                new TakeoverCreateInput(999_999_999L, null, requesterId, "不存在设备")));
    }

    @Test
    void duplicateInflightRequestRejected() {
        request("第一笔在途");
        ServiceException ex = assertThrows(ServiceException.class, () -> request("重复申请"));
        assertTrue(ex.getMessage().contains("已有"), "在途重复=人话拒: " + ex.getMessage());
        assertEquals(1, takeoverCount());
    }


    @Test
    void agreeTransitionsToActiveAndReceiptsRequester() {
        long id = request("同意路径");
        AppautoTakeoverView agreed = takeoverService.agree(id, occupierId);
        assertEquals("active", agreed.state(), "同意即转 active（单事务，二段确认简化定案）");
        assertEquals(occupierId, agreed.decidedBy());
        assertNotNull(agreed.decidedAt());
        Integer receipt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE user_id = ? AND event_type ="
                        + " 'TAKEOVER_REQUEST' AND dedup_key = ?",
                Integer.class, requesterId, "TAKEOVER_DECIDED:" + id);
        assertEquals(1, receipt, "申请人收同意回执站内信");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.agree(id, occupierId));
        assertTrue(ex.getMessage().contains("已被处理"), "重复处理=人话拒: " + ex.getMessage());
        assertEquals("ended", takeoverService.end(id, occupierId).state());
    }

    @Test
    void onlyOccupierCanAgree() {
        long id = request("越权判定");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.agree(id, requesterId));
        assertTrue(ex.getMessage().contains("仅设备当前占用会话的负责人"),
                "非占用者同意=人话拒（强接只走管理员通道）: " + ex.getMessage());
        assertEquals("pending", takeoverService.get(id).state(), "越权判定零转态");
    }


    @Test
    void forceMarksForcedAndNotifiesOccupier() {
        long id = request("强接路径");
        long adminId = insertUser("tkov-adm");
        AppautoTakeoverView forced = takeoverService.force(id, adminId);
        assertEquals("forced", forced.state());
        assertEquals(adminId, forced.decidedBy());
        Integer notice = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE user_id = ? AND event_type ="
                        + " 'TAKEOVER_FORCED' AND dedup_key = ?",
                Integer.class, occupierId, "TAKEOVER_FORCED:" + id);
        assertEquals(1, notice, "被接人收强制接管站内信");
        assertThrows(ServiceException.class, () -> takeoverService.agree(id, occupierId));
        assertEquals("ended", takeoverService.end(id, adminId).state());
    }


    @Test
    void rejectTurnsRejectedAndNotifiesRequester() {
        long id = request("驳回路径");
        AppautoTakeoverView rejected = takeoverService.reject(id, occupierId);
        assertEquals("rejected", rejected.state());
        Integer receipt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE user_id = ? AND dedup_key = ?",
                Integer.class, requesterId, "TAKEOVER_DECIDED:" + id);
        assertEquals(1, receipt, "驳回同样回执申请人");
    }

    @Test
    void endOnlyFromOngoingState() {
        long id = request("未开始先结束");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.end(id, requesterId));
        assertTrue(ex.getMessage().contains("仅对进行中的接管"), "pending 结束=人话拒: " + ex.getMessage());
    }


    @Test
    void expiredPendingFailsClosedOnDecisionAndReadView() {
        long id = request("过期路径");
        jdbcTemplate.update("UPDATE tp_appauto_takeover_request SET expires_at = ?"
                        + " WHERE id = ?", LocalDateTime.now().minusSeconds(1), id);
        assertEquals("expired", takeoverService.get(id).state(), "读视图过期 pending=expired 如实标");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeoverService.agree(id, occupierId));
        assertTrue(ex.getMessage().contains("已过期"), "过期拒批=人话: " + ex.getMessage());
        assertEquals("expired", jdbcTemplate.queryForObject(
                "SELECT state FROM tp_appauto_takeover_request WHERE id = ?",
                String.class, id), "过期判定即收口落 expired 态");
    }

    private long insertUser(String username) {
        return FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, 'x', '接管管理员', 1, 1)", username + System.nanoTime());
    }

    private int takeoverCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_takeover_request WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }
}
