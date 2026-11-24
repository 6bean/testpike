package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoPlatformServiceTest {

    @Autowired
    private AppautoPlatformService platformService;

    @Test
    void enabledListReturnsActivePlatforms() {
        List<AppautoPlatformInfo> enabled = platformService.listPlatforms(false);
        assertEquals(7, enabled.size(), "启用面行数=出厂种子现态（七活跃行）");
        for (String code : List.of("ANDROID", "IOS", "MP", "HARMONY_NEXT", "QUICKAPP")) {
            assertTrue(enabled.stream().anyMatch(p -> code.equals(p.code())),
                    "启用面缺平台：" + code);
        }
        assertTrue(enabled.stream().noneMatch(p -> "WEB_MOBILE".equals(p.code())
                        || "TV".equals(p.code())),
                "预留停用位不得混入建档可选面");
    }

    @Test
    void fullListIncludesReservedDisabledPlatforms() {
        List<AppautoPlatformInfo> all = platformService.listPlatforms(true);
        assertEquals(9, all.size(), "含停用全量面=九行（七活跃+两预留）");
        assertTrue(all.stream().anyMatch(p -> "WEB_MOBILE".equals(p.code()) && p.status() == 0),
                "WEB_MOBILE 预留位应在全量面以停用态出现");
    }

    @Test
    void getByCodeDeserializesCarrierTypesAndGuidanceNote() {
        AppautoPlatformInfo mp = platformService.getByCode("MP");
        assertNotNull(mp);
        assertTrue(mp.carrierTypes().contains("appid+project_path"),
                "小程序载体=appid+工程路径（§2.4）");
        assertTrue(mp.deviceKindCodes().contains("DEVTOOLS_IDE"),
                "开发者工具形态设备型应在位");
        AppautoPlatformInfo android = platformService.getByCode("ANDROID");
        assertNotNull(android.note(), "老鸿蒙防撞引导文案不得丢失（§13.2 注记级折入）");
        assertTrue(android.note().contains("鸿蒙"), "引导文案应为人话中文");
    }

    @Test
    void unknownCodeFailsClosedWithHumanizedNotFound() {
        com.sixbean.testpike.common.exception.ServiceException ex = assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> platformService.getByCode("NO_SUCH_PLATFORM"));
        assertEquals(com.sixbean.testpike.common.exception.BizErrorCode.NOT_FOUND,
                ex.getCode(), "未知平台应人话 404");
    }
}
