package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoCatalogSchemaTest {

 
    static final List<String> IR_VERBS = com.sixbean.testpike.common.util.UiActionContract.IR_VERBS;

    static final Set<String> ENGINE_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "code", "name", "platform_codes", "device_kind_codes", "default_priority",
            "runtime_type", "runtime_version_req", "capabilities", "driver_requirements",
            "env_check_spec", "status", "install_doc_url", "license",
            "created_at", "updated_at", "install_recipe"));

    static final Set<String> PLATFORM_COLUMNS = new HashSet<>(Arrays.asList(
            "id", "code", "name", "device_kind_codes", "carrier_types", "note", "status",
            "created_at", "updated_at"));

    static final Set<String> RUNTIME_TYPES = new HashSet<>(Arrays.asList(
            "PY_INPROC", "NODE_BRIDGE", "CLI_SUBPROC", "WEB_DRIVER"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void engineTableHasFullColumnSet() {
        Set<String> actual = new HashSet<>(jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'tp_appauto_engine'",
                (rs, i) -> rs.getString(1)));
        assertEquals(ENGINE_COLUMNS, actual, "V145 tp_appauto_engine 列集与冻结 §2.3 字段全集不符");
    }

    @Test
    void engineSeedCoversEightRowsWithDefaultAndCandidate() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT code, status, default_priority FROM tp_appauto_engine");
        assertEquals(8, rows.size(), "引擎种子应恰为八行（§1.3 六引擎+XCUITEST+HYPIUM 候补）");
        Set<String> codes = new HashSet<>();
        for (Map<String, Object> r : rows) {
            codes.add((String) r.get("code"));
        }
        assertTrue(codes.containsAll(Arrays.asList(
                        "MOBILEWRIGHT", "MAESTRO", "APPIUM", "XCUITEST",
                        "MINIPROGRAM_AUTOMATOR", "MINIUM", "HMNEXTAUTO", "HYPIUM")),
                "八引擎 code 集缺项：" + codes);
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_engine WHERE code='HYPIUM' AND status=0"),
                "HYPIUM 必须候补位 status=0（§8-D2 裁决）");
        assertEquals(7, count("SELECT count(*) FROM tp_appauto_engine WHERE status=1"),
                "六引擎全入一期+XCUITEST 独立描述符行=启用七行");
        String top = jdbcTemplate.queryForObject(
                "SELECT code FROM tp_appauto_engine WHERE status=1 ORDER BY default_priority LIMIT 1",
                String.class);
        assertEquals("MOBILEWRIGHT", top, "默认引擎 Mobilewright 必须置顶（§8-D1）");
    }

    @Test
    void engineRuntimeTypeWithinFourValueEnum() {
        int bad = count("SELECT count(*) FROM tp_appauto_engine WHERE runtime_type NOT IN "
                + "('PY_INPROC','NODE_BRIDGE','CLI_SUBPROC','WEB_DRIVER')");
        assertEquals(0, bad, "runtime_type 四值枚举外值（§2.3）");
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_engine WHERE code='MINIUM' AND runtime_type='PY_INPROC'"),
                "Minium=进程内直调形态（§1.3）");
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_engine WHERE code='MOBILEWRIGHT' AND runtime_type='NODE_BRIDGE'"));
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_engine WHERE code='MAESTRO' AND runtime_type='CLI_SUBPROC'"));
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_engine WHERE code='APPIUM' AND runtime_type='WEB_DRIVER'"));
    }

    @Test
    void activeEngineCapabilitiesCarryFullIrVerbBitmap() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT code, capabilities FROM tp_appauto_engine WHERE status=1");
        assertEquals(7, rows.size());
        for (Map<String, Object> r : rows) {
            String json = String.valueOf(r.get("capabilities"));
            for (String verb : IR_VERBS) {
                assertTrue(json.contains("\"" + verb + "\""),
                        r.get("code") + " 动词位图缺 §2.1 基线动词 " + verb);
            }
        }
    }

    @Test
    void platformTableSeedSevenActivePlusReservedDisabled() {
        Set<String> actual = new HashSet<>(jdbcTemplate.query(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'tp_appauto_platform'",
                (rs, i) -> rs.getString(1)));
        assertEquals(PLATFORM_COLUMNS, actual, "V147 tp_appauto_platform 列集与 §2.4 不符");
        assertEquals(7, count("SELECT count(*) FROM tp_appauto_platform WHERE status=1"),
                "平台全集=一期五活跃 + POS/PDA 零售边端两行（§2.4 + 阶段二 B8）");
        for (String code : Arrays.asList("ANDROID", "IOS", "MP", "HARMONY_NEXT", "QUICKAPP", "POS", "PDA")) {
            assertEquals(1, count("SELECT count(*) FROM tp_appauto_platform WHERE code='" + code + "' AND status=1"),
                    "平台缺行：" + code);
        }
        assertTrue(count("SELECT count(*) FROM tp_appauto_platform WHERE status=0") >= 2,
                "预留位 WEB_MOBILE/TV 必须以停用态在位（§2.4）");
        assertEquals(1, count("SELECT count(*) FROM tp_appauto_platform WHERE code='QUICKAPP' "
                + "AND carrier_types::text LIKE '%hap%'"), "快应用载体=hap+scheme（§2.4）");
    }

    @Test
    void appTableGainedPlatformCodeColumn() {
        assertEquals(1, count("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name='tp_appauto_app' AND column_name='platform_code'"),
                "tp_appauto_app 缺 platform_code 引用列（§2.4 末注实查补列）");
    }

    private int count(String sql) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }
}
