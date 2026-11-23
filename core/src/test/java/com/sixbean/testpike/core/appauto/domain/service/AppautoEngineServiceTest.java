package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoEngineServiceTest {

    @Autowired
    private AppautoEngineService engineService;

    @Test
    void enabledListReturnsSevenEnginesSortedByPriorityWithDefaultFirst() {
        List<AppautoEngineInfo> enabled = engineService.listEngines(false);
        assertEquals(7, enabled.size(), "启用面应恰为七引擎（HYPIUM 候补位不出现）");
        assertEquals("MOBILEWRIGHT", enabled.get(0).code(), "默认引擎 Mobilewright 必须置顶（§8-D1）");
        for (int i = 1; i < enabled.size(); i++) {
            assertTrue(enabled.get(i).defaultPriority() >= enabled.get(i - 1).defaultPriority(),
                    "启用面必须按 default_priority 升序（路由优先级单源）");
        }
        assertTrue(enabled.stream().noneMatch(e -> "HYPIUM".equals(e.code())),
                "候补停用引擎不得混入启用面");
    }

    @Test
    void fullListIncludesDisabledCandidateEngine() {
        List<AppautoEngineInfo> all = engineService.listEngines(true);
        assertEquals(8, all.size(), "含停用全量面=八行（管理侧可见候补位）");
        assertTrue(all.stream().anyMatch(e -> "HYPIUM".equals(e.code()) && e.status() == 0),
                "HYPIUM 候补位应在全量面以停用态出现");
    }

    @Test
    void getByCodeDeserializesCapabilityBitmapAndDriverChecklist() {
        AppautoEngineInfo mobilewright = engineService.getByCode("MOBILEWRIGHT");
        assertNotNull(mobilewright);
        assertEquals("NODE_BRIDGE", mobilewright.runtimeType());
        assertTrue(mobilewright.platformCodes().contains("ANDROID"));
        assertTrue(mobilewright.deviceKindCodes().contains("REAL"));
        for (String verb : AppautoCatalogSchemaTest.IR_VERBS) {
            assertTrue(mobilewright.capabilities().containsKey(verb),
                    "动词位图缺 §2.1 基线动词：" + verb);
        }
        assertTrue(Boolean.TRUE.equals(mobilewright.capabilities().get("webview-hybrid")),
                "Mobilewright WebView 混合旗标应有实证值");
        assertTrue(mobilewright.driverRequirements().size() >= 2,
                "人话依赖清单（mobilecli 自装/ADB/macOS 宿主）不得丢失或截断");
        assertTrue(mobilewright.envCheckSpec().size() >= 2,
                "环境自检命令清单应在位");
        assertNotNull(mobilewright.license());
    }

    @Test
    void installRecipeExposedForSeededEnginesAndHonestEmptyForUnseeded() {
        AppautoEngineInfo mobilewright = engineService.getByCode("MOBILEWRIGHT");
        assertEquals("0.0.63", String.valueOf(mobilewright.installRecipe().get("version_pin")),
                "Mobilewright 配方版本钉（npm 官方源实证）应原样透出");
        assertTrue(mobilewright.installRecipe().containsKey("hosts"),
                "配方三宿主段（windows/macos/linux）应在位");
        assertTrue(mobilewright.installRecipe().get("manual_steps") instanceof java.util.List<?> steps
                        && steps.size() >= 2,
                "人工步骤清单（ADB/Xcode 宿主前置、遥测建议）不得丢失或截断");
        AppautoEngineInfo minium = engineService.getByCode("MINIUM");
        assertEquals("1.6.1", String.valueOf(minium.installRecipe().get("version_pin")));
        Object wxTool = minium.installRecipe().get("manual_steps").toString();
        assertTrue(String.valueOf(wxTool).contains("微信开发者工具"),
                "微信开发者工具三步人工引导必须内置配方（§9.3）");
        assertTrue(String.valueOf(wxTool).contains("9420"),
                "9420 连通探针引导在配方人工步骤（禁脚本硬编码第二源）");
        assertEquals("2.11.0", String.valueOf(engineService.getByCode("MAESTRO").installRecipe().get("version_pin")),
                "Maestro 配方版本钉（GitHub Release 直下实证）应原样透出");
        assertTrue(engineService.getByCode("HYPIUM").installRecipe().isEmpty(),
                "未实装引擎=诚实空对象（禁补猜命令）");
    }

    @Test
    void quickappBestEffortPlatformDeclaredOnlyForAndroidFamilyEngines() {
        for (String code : List.of("MOBILEWRIGHT", "APPIUM", "MAESTRO")) {
            assertTrue(engineService.getByCode(code).platformCodes().contains("QUICKAPP"),
                    code + " 安卓族引擎须声明 QUICKAPP 尽力位（快应用用例派发闸消费面）");
        }
        for (String code : List.of("XCUITEST", "MINIUM", "HMNEXTAUTO")) {
            assertTrue(!engineService.getByCode(code).platformCodes().contains("QUICKAPP"),
                    code + " 非安卓通道引擎不得声明 QUICKAPP（尽力位只给安卓族）");
        }
    }

    @Test
    void unknownCodeFailsClosedWithHumanizedNotFound() {
        com.sixbean.testpike.common.exception.ServiceException ex = assertThrows(
                com.sixbean.testpike.common.exception.ServiceException.class,
                () -> engineService.getByCode("NO_SUCH_ENGINE"));
        assertEquals(com.sixbean.testpike.common.exception.BizErrorCode.NOT_FOUND,
                ex.getCode(), "未知引擎应人话 404");
    }
}
