package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoReservationBindBatchTest {

    @Autowired
    private AppautoReservationService reservationService;

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long foreignProjectId;
    private long deviceId;
    private long holderUserId;
    private long batchId;
    private long foreignBatchId;
    private final List<String> insertedBatchNos = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String code = "arbb" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约绑批项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String foreignCode = "arbbf" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约绑批他人项目-" + foreignCode, foreignCode);
        foreignProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, foreignCode);
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "预约绑批机", "BND-A-" + projectId, "EMULATOR", null, "Pixel 7", "13",
                "1080x2400", 420, List.of("pool")));
        holderUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user ORDER BY id LIMIT 1", Long.class);
        batchId = insertBatch(projectId, "own");
        foreignBatchId = insertBatch(foreignProjectId, "foreign");
    }

    @AfterEach
    void cleanAll() {
        for (String batchNo : insertedBatchNos) {
            jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE batch_no = ?", batchNo);
        }
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)",
                projectId, foreignProjectId);
    }

    private long insertBatch(long ownerProjectId, String tag) {
        String batchNo = "ARBB-" + tag + "-" + System.nanoTime();
        insertedBatchNos.add(batchNo);
        jdbcTemplate.update("INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type)"
                        + " VALUES (?, ?, ?, 0, 1)",
                ownerProjectId, batchNo, "预约绑批夹具批次-" + tag);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_batch WHERE batch_no = ?", Long.class, batchNo);
    }

    private AppautoReservationView createReservation(Long reservationProjectId,
                                                     LocalDateTime starts) {
        return reservationService.create(new AppautoReservationInput(
                reservationProjectId, deviceId, null, holderUserId,
                starts, starts.plusHours(2), "绑批演练"));
    }

    @Test
    void bindBatch_echoesBatchId_andUnbindReturnsNull() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView created = createReservation(projectId, base);

        AppautoReservationView bound = reservationService.bindBatch(created.id(), batchId);
        assertEquals(batchId, bound.batchId(), "绑定后回显批次号须等于入参批次");

        AppautoReservationView unbound = reservationService.bindBatch(created.id(), null);
        assertNull(unbound.batchId(), "解绑（batchId=null）回显须为空");
    }

    @Test
    void bindBatch_ghostReservation_failsWithNotFound() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservationService.bindBatch(999999999L, batchId));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode().intValue(),
                "幽灵预约号应回 404 码，实际=" + ex.getCode());
        assertTrue(ex.getMessage().contains("预约"),
                "幽灵预约应人话点名预约，实际=" + ex.getMessage());
    }

    @Test
    void bindBatch_terminalRow_rejectedWithStateLabel() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView created = createReservation(projectId, base);
        reservationService.release(created.id(), "用毕归还");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservationService.bindBatch(created.id(), batchId));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode().intValue(),
                "终态行绑定应回 409 状态机码，实际=" + ex.getCode());
        assertTrue(ex.getMessage().contains("已是「已归还」"),
                "终态拒应点名当前状态，实际=" + ex.getMessage());
    }

    @Test
    void bindBatch_projectMismatchAndGhostBatch_rejectedAndSharedRowSkipsCheck() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView created = createReservation(projectId, base);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> reservationService.bindBatch(created.id(), foreignBatchId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, conflict.getCode().intValue(),
                "批次归属项目≠预约归属项目应回 409 冲突码，实际=" + conflict.getCode());
        assertTrue(conflict.getMessage().contains("项目"),
                "冲突拒应点名两侧项目归属，实际=" + conflict.getMessage());

        ServiceException ghostBatch = assertThrows(ServiceException.class,
                () -> reservationService.bindBatch(created.id(), 999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ghostBatch.getCode().intValue(),
                "幽灵批次应回 404 码，实际=" + ghostBatch.getCode());
        assertTrue(ghostBatch.getMessage().contains("批次不存在"),
                "幽灵批次应人话点名批次，实际=" + ghostBatch.getMessage());

        AppautoReservationView shared = createReservation(null, base.plusHours(6));
        AppautoReservationView sharedBound = reservationService.bindBatch(shared.id(), batchId);
        assertEquals(batchId, sharedBound.batchId(),
                "共享位预约绑定应跳过一致判直接成功");
    }
}
