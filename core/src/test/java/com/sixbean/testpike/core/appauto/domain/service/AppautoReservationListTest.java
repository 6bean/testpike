package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoReservationListTest {

    @Autowired
    private AppautoReservationService reservationService;

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long deviceId;
    private long holderUserId;
    private final List<String> insertedBatchNos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String code = "arsl" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约列表项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "预约列表机", "LST-A-" + projectId, "EMULATOR", null, "Pixel 7", "13",
                "1080x2400", 420, List.of("pool")));
        holderUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanAll() {
        for (String batchNo : insertedBatchNos) {
            jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE batch_no = ?", batchNo);
        }
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long insertBatch(String tag) {
        String batchNo = "ARSL-" + tag + "-" + System.nanoTime();
        insertedBatchNos.add(batchNo);
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type)"
                        + " VALUES (?, ?, ?, 0, 1)",
                projectId, batchNo, "预约列表夹具批次-" + tag);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE batch_no = ?", Long.class, batchNo);
    }

    private AppautoReservationInput input(LocalDateTime starts, LocalDateTime ends, String reason) {
        return new AppautoReservationInput(projectId, deviceId, null, holderUserId,
                starts, ends, reason);
    }

    private AppautoReservationInput batchInput(Long batch, LocalDateTime starts,
                                               LocalDateTime ends, String reason) {
        return new AppautoReservationInput(projectId, deviceId, batch, holderUserId,
                starts, ends, reason);
    }

    @Test
    void list_defaultsToActiveOnly_terminalStatesHiddenWithoutFlag() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView first = reservationService.create(
                input(base, base.plusHours(2), "甲场用毕"));
        AppautoReservationView second = reservationService.create(
                input(base.plusHours(3), base.plusHours(4), "乙场在约"));
        reservationService.release(first.id(), "用毕归还");

        List<AppautoReservationView> active = reservationService.list(deviceId, null, null, false);
        assertEquals(1, active.size(), "默认列表只应含活跃态预约");
        assertEquals(second.id(), active.get(0).id(), "已归还的甲场不该出现");

        List<AppautoReservationView> withTerminal = reservationService.list(deviceId, null, null, true);
        assertEquals(2, withTerminal.size(), "includeTerminal=true 应含终态预约");
    }

    @Test
    void list_ordersByStartsAtNotCreationOrder_bothFilterDimsWork() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView later = reservationService.create(
                input(base.plusHours(6), base.plusHours(7), "晚场先创建"));
        AppautoReservationView earlier = reservationService.create(
                input(base.plusHours(4), base.plusHours(5), "早场后创建"));

        List<AppautoReservationView> byDevice = reservationService.list(deviceId, null, null, false);
        assertEquals(2, byDevice.size());
        assertEquals(earlier.id(), byDevice.get(0).id(), "窗起升序=日历时间轴前提，禁按创建序");
        assertEquals(later.id(), byDevice.get(1).id());

        List<AppautoReservationView> byProject = reservationService.list(null, projectId, null, false);
        assertEquals(2, byProject.size(), "项目维度过滤应命中同项目两台预约");

        List<AppautoReservationView> foreign = reservationService.list(deviceId, projectId + 999,
                null, false);
        assertTrue(foreign.isEmpty(), "设备与项目条件须同时成立（AND 语义），他人项目查不到");
    }

    @Test
    void list_batchFilter_returnsOnlyReservationsBoundToThatBatch() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        long batchA = insertBatch("A");
        long batchB = insertBatch("B");
        AppautoReservationView boundA = reservationService.create(
                batchInput(batchA, base, base.plusHours(2), "甲批次预约"));
        reservationService.create(batchInput(batchB, base.plusHours(3), base.plusHours(4),
                "乙批次预约"));
        reservationService.create(input(base.plusHours(5), base.plusHours(6), "未绑批次预约"));

        List<AppautoReservationView> onlyA = reservationService.list(null, projectId, batchA, false);
        assertEquals(1, onlyA.size(), "批次过滤只应回绑该批次的预约");
        assertEquals(boundA.id(), onlyA.get(0).id(), "命中行须是绑定甲批次的那条");

        List<AppautoReservationView> allInProject =
                reservationService.list(null, projectId, null, false);
        assertEquals(3, allInProject.size(), "batchId=null 不过滤批次维（同项目全量活跃）");
    }
}
