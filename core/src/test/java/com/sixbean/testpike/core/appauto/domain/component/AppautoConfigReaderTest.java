package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoConfigReaderTest {

    static final String KEY = "appauto.default-engine";

    @Autowired
    private AppautoConfigReader reader;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreSeed() {
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'MOBILEWRIGHT' WHERE config_key = ?", KEY);
        configCacheHolder.remove(KEY);
    }

    @Test
    void seededKeyPresentAndResolvesMobilewrightDefault() {
        configCacheHolder.remove(KEY);
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, KEY);
        assertEquals("MOBILEWRIGHT", stored, "V146 种子键必须落库在位且默认位=Mobilewright（10-01 口令）");
        assertEquals("MOBILEWRIGHT", reader.defaultEngine(), "默认引擎解析应取键值 Mobilewright");
    }

    @Test
    void enabledEngineHotUpdateTakesEffectWithoutRestart() {
        configCacheHolder.put(KEY, "MAESTRO");
        assertEquals("MAESTRO", reader.defaultEngine(),
                "启用面合法引擎经键热更即时生效（TC-K8-02 语义）");
    }

    @Test
    void lowercaseKeyNormalizedToUpperCode() {
        configCacheHolder.put(KEY, " maestro ");
        assertEquals("MAESTRO", reader.defaultEngine(), "键值大小写/空白归一后对账描述符 code");
    }

    @Test
    void illegalEngineValueFallsBackToBuiltinDefault() {
        configCacheHolder.put(KEY, "AIRTEST");
        assertEquals("MOBILEWRIGHT", reader.defaultEngine(),
                "非法引擎值（airtest=10-01 裁决不入矩阵）回退内置默认，禁裸信任");
    }

    @Test
    void delistedCandidateEngineFallsBackToBuiltinDefault() {
        configCacheHolder.put(KEY, "HYPIUM");
        assertEquals("MOBILEWRIGHT", reader.defaultEngine(),
                "已下架候补位（status=0）不得作为默认引擎生效——值域对账=描述符启用面");
    }

    @Test
    void blankEngineValueFallsBackToBuiltinDefault() {
        configCacheHolder.put(KEY, "");
        assertEquals("MOBILEWRIGHT", reader.defaultEngine(), "空键值回退内置默认（配置面容错）");
    }
}
