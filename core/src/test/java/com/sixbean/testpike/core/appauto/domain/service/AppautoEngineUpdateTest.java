package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoEngineUpdateTest {

    @Autowired
    private AppautoEngineService engineService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Object[] snapshot;

    @BeforeEach
    void snapshotMobilewright() {
        snapshot = jdbcTemplate.queryForObject(
                "SELECT name, default_priority, install_doc_url, status FROM tp_appauto_engine WHERE code = 'MOBILEWRIGHT'",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4)});
    }

    @AfterEach
    void restoreSeedRow() {
        jdbcTemplate.update("UPDATE tp_appauto_engine SET name = ?, default_priority = ?, "
                        + "install_doc_url = ?, status = ?, updated_at = now() WHERE code = 'MOBILEWRIGHT'",
                snapshot[0], snapshot[1], snapshot[2], snapshot[3]);
    }


    @Test
    void update_replacesNamePriorityInstallDoc_roundTrips() {
        engineService.update("MOBILEWRIGHT",
                new AppautoEngineUpdateInput("Mobilewright 主力引擎", 5, "https://docs.example.org/mw", 1));
        AppautoEngineInfo got = engineService.getByCode("MOBILEWRIGHT");
        assertEquals("Mobilewright 主力引擎", got.name());
        assertEquals(5, got.defaultPriority(), "路由优先级必须落库生效（默认引擎置顶语义源）");
        assertEquals("https://docs.example.org/mw", got.installDocUrl());
        assertEquals(1, got.status());
    }

    @Test
    void update_blankInstallDoc_storedAsNull() {
        engineService.update("MOBILEWRIGHT",
                new AppautoEngineUpdateInput("改名", 10, "   ", 1));
        assertNull(engineService.getByCode("MOBILEWRIGHT").installDocUrl(),
                "安装文档链接空白=置空（前端展示人话「暂无文档」而非空白串）");
    }


    @Test
    void update_statusOff_hidesFromEnabledList_stillVisibleToManagement() {
        engineService.update("MOBILEWRIGHT",
                new AppautoEngineUpdateInput("Mobilewright", 10, null, 0));
        List<AppautoEngineInfo> enabled = engineService.listEngines(false);
        assertFalse(enabled.stream().anyMatch(e -> "MOBILEWRIGHT".equals(e.code())),
                "下架引擎不得混入用户可选面（建单/配置值域对账消费面）");
        assertTrue(engineService.listEngines(true).stream()
                        .anyMatch(e -> "MOBILEWRIGHT".equals(e.code()) && e.status() == 0),
                "管理侧全量面必须可见下架行（否则无法再上架）");
        assertEquals(0, engineService.getByCode("MOBILEWRIGHT").status(), "按 code 详情管理侧可查");
    }


    @Test
    void update_blankName_rejectsHumanly() {
        ServiceException ex = assertThrows(ServiceException.class, () -> engineService.update(
                "MOBILEWRIGHT", new AppautoEngineUpdateInput("  ", 10, null, 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("名称"), ex.getMessage());
    }

    @Test
    void update_illegalStatus_rejectsHumanly() {
        ServiceException ex = assertThrows(ServiceException.class, () -> engineService.update(
                "MOBILEWRIGHT", new AppautoEngineUpdateInput("Mobilewright", 10, null, 7)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("1=启用"), "状态值域文案要人话讲清 0/1 语义");
    }


    @Test
    void update_unknownCode_failsClosedWithHumanizedNotFound() {
        ServiceException ex = assertThrows(ServiceException.class, () -> engineService.update(
                "NO_SUCH_ENGINE", new AppautoEngineUpdateInput("x", 1, null, 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
