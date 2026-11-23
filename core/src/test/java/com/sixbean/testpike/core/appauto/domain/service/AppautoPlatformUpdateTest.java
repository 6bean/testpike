package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoPlatformUpdateTest {

    @Autowired
    private AppautoPlatformService platformService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Object[] webMobileSnapshot;
    private String androidNoteSnapshot;

    @BeforeEach
    void snapshotSeedRows() {
        webMobileSnapshot = jdbcTemplate.queryForObject(
                "SELECT note, status FROM tp_appauto_platform WHERE code = 'WEB_MOBILE'",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)});
        androidNoteSnapshot = jdbcTemplate.queryForObject(
                "SELECT note FROM tp_appauto_platform WHERE code = 'ANDROID'", String.class);
    }

    @AfterEach
    void restoreSeedRows() {
        jdbcTemplate.update("UPDATE tp_appauto_platform SET note = ?, status = ?, updated_at = now() "
                        + "WHERE code = 'WEB_MOBILE'",
                webMobileSnapshot[0], webMobileSnapshot[1]);
        jdbcTemplate.update("UPDATE tp_appauto_platform SET note = ?, updated_at = now() WHERE code = 'ANDROID'",
                androidNoteSnapshot);
    }

    @Test
    void update_note_roundTripsWithoutTruncation() {
        String note = "本行仅承载运营引导：Android 档案须填设备类型防老鸿蒙撞号（长文案完整回读不截断）";
        platformService.update("ANDROID", new AppautoPlatformUpdateInput(note, 1));
        AppautoPlatformInfo got = platformService.getByCode("ANDROID");
        assertEquals(note, got.note());
    }

    @Test
    void update_reservedPlatformStatusOn_appearsInEnabledList() {
        platformService.update("WEB_MOBILE", new AppautoPlatformUpdateInput("转正运营", 1));
        assertTrue(platformService.listPlatforms(false).stream()
                        .anyMatch(p -> "WEB_MOBILE".equals(p.code())),
                "预留位上架后应进入建档可选面");
        platformService.update("WEB_MOBILE", new AppautoPlatformUpdateInput("再下架", 0));
        assertTrue(platformService.listPlatforms(true).stream()
                        .anyMatch(p -> "WEB_MOBILE".equals(p.code()) && p.status() == 0),
                "下架后管理侧全量面仍可见");
    }

    @Test
    void update_illegalStatus_rejectsHumanly() {
        ServiceException ex = assertThrows(ServiceException.class, () -> platformService.update(
                "ANDROID", new AppautoPlatformUpdateInput("x", 2)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("1=启用"), ex.getMessage());
    }

    @Test
    void update_unknownCode_failsClosedWithHumanizedNotFound() {
        ServiceException ex = assertThrows(ServiceException.class, () -> platformService.update(
                "NO_SUCH_PLATFORM", new AppautoPlatformUpdateInput("x", 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
