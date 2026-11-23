package com.sixbean.testpike.core.project.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class EnvParamCipherSupportTest {

    @Autowired
    private EnvParamCipherSupport support;

    @Autowired
    private ConfigCipher configCipher;

    @Autowired
    private ObjectMapper objectMapper;

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode paramsNode(String configJson) throws Exception {
        return objectMapper.readTree(configJson).path(EnvParamCipherSupport.PARAMS_SECTION);
    }


    @Test
    void merge_合法键名_全部通过() throws Exception {
        String merged = support.mergeParams(null, params("BASE_URL", "http://a", "_x1", "v"));
        JsonNode p = paramsNode(merged);
        assertTrue(p.has("BASE_URL") && p.has("_x1"), "两合法键均在（值可能已加密，键在即通过）");
    }

    @Test
    void merge_非法键名_400人话点名() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params("a-b", "v")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("a-b"), "报错须点名违规键，当前=" + ex.getMessage());
    }

    @Test
    void merge_多个非法键_一次点名全部() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params("1bad", "v", "好键", "v", "ok_key", "v")));
        assertTrue(ex.getMessage().contains("1bad") && ex.getMessage().contains("好键"),
                "全部违规键一次报出（信息完整不截断），当前=" + ex.getMessage());
        assertFalse(ex.getMessage().contains("ok_key"), "合法键不连带点名");
    }

    @Test
    void merge_超长键名_400() {
        String longKey = "K".repeat(EnvParamCipherSupport.KEY_MAX_LEN + 1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params(longKey, "v")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("64"), "人话含长度上限，当前=" + ex.getMessage());
    }

    @Test
    void merge_空值_400人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params("ACCOUNT", "  ")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("ACCOUNT"), "点名空值键，当前=" + ex.getMessage());
    }


    @Test
    void merge_密钥可用_存ENC密文_DB无明文() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(configCipher.isEnabled(),
                "本测试仅在 CONFIG_AES_KEY 可用环境钉密文形态（fail-closed 例另行覆盖语义）");
        String merged = support.mergeParams("{\"other\":\"keep\"}", params("PASSWORD", "s3cret!"));
        JsonNode p = paramsNode(merged);
        String stored = p.path("PASSWORD").asText();
        assertTrue(stored.startsWith(EnvParamCipherSupport.ENC_PREFIX), "加密值带 ENC: 前缀");
        assertFalse(merged.contains("s3cret!"), "DB 侧 config_json 不得含明文（IT-K8-02 同族钉）");
        assertEquals("keep", objectMapper.readTree(merged).path("other").asText(),
                "非 params 键原样保留");
    }

    @Test
    void merge_密钥可用_打码回传保留原密文() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(configCipher.isEnabled(), "同上");
        String merged = support.mergeParams(null, params("TOKEN", "abc123"));
        String firstCipher = paramsNode(merged).path("TOKEN").asText();
        String again = support.mergeParams(merged, params("TOKEN", ConfigCipher.MASK));
        assertEquals(firstCipher, paramsNode(again).path("TOKEN").asText(),
                "MASK 回传=未修改语义，保留原密文");
    }

    @Test
    void merge_打码回传但原键非密文_400人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params("NEW_KEY", ConfigCipher.MASK)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("NEW_KEY"), "点名键，当前=" + ex.getMessage());
    }

    @Test
    void merge_提交键值伪装ENC前缀_400防绕过() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams(null, params("K", EnvParamCipherSupport.ENC_PREFIX + "fake")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "当前=" + ex.getMessage());
    }

    @Test
    void merge_空map_移除params段保留其他键() throws Exception {
        String merged = support.mergeParams("{\"a\":1,\"params\":{\"X\":\"ENC:zzz\"}}", Map.of());
        assertFalse(objectMapper.readTree(merged).has("params"), "空 map=清空参数段");
        assertEquals(1, objectMapper.readTree(merged).path("a").asInt(), "其他键保留");
    }

    @Test
    void merge_全量替换语义_未提交键删除() throws Exception {
        String merged = support.mergeParams(null, params("A", "1", "B", "2"));
        String replaced = support.mergeParams(merged, params("A", "9"));
        JsonNode p = paramsNode(replaced);
        assertTrue(p.has("A") && !p.has("B"), "PUT 全量替换：未提交键删除");
    }

    @Test
    void merge_非法configJson输入_400人话不500() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeParams("not-json{{{", params("A", "1")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "当前=" + ex.getMessage());
    }

    @Test
    void merge_密钥缺失_failClosed_400人话() {
        EnvParamCipherSupport disabled =
                new EnvParamCipherSupport(new ConfigCipher(""), objectMapper);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disabled.mergeParams(null, params("K", "v")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(),
                "fail-closed=400 人话（500=INTERNAL 口径弃用，PRD 显式 400）当前=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("CONFIG_AES_KEY"), "指引键名，当前=" + ex.getMessage());
    }


    @Test
    void readItems_密文键打码_明文键原样() {
        String config = "{\"params\":{\"P\":\"ENC:abc==\",\"LEGACY\":\"plain\"}}";
        List<EnvParamItem> items = support.readItems(config);
        assertEquals(2, items.size());
        EnvParamItem enc = items.stream().filter(i -> i.key().equals("P")).findFirst().orElseThrow();
        assertTrue(enc.encrypted());
        assertEquals(ConfigCipher.MASK, enc.value(), "密文键对外恒打码（永不回传实值）");
        EnvParamItem legacy = items.stream().filter(i -> i.key().equals("LEGACY")).findFirst().orElseThrow();
        assertFalse(legacy.encrypted());
        assertEquals("plain", legacy.value());
    }

    @Test
    void readItems_无params段_空列表() {
        assertTrue(support.readItems(null).isEmpty());
        assertTrue(support.readItems("{\"other\":1}").isEmpty());
    }


    @Test
    void assertUnchanged_回传原configJson_放行() {
        String current = "{\"a\":1,\"params\":{\"K\":\"ENC:x\"}}";
        support.assertParamsSectionUnchanged(current, current);
    }

    @Test
    void assertUnchanged_无params段请求_放行() {
        support.assertParamsSectionUnchanged(null, "{\"other\":2}");
        support.assertParamsSectionUnchanged(null, null);
    }

    @Test
    void assertUnchanged_现段有params提交null_400防删除绕过() {
        ServiceException ex = assertThrows(ServiceException.class, () -> support.assertParamsSectionUnchanged(
                "{\"params\":{\"K\":\"ENC:x\"}}", null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("参数") || ex.getMessage().contains("params"),
                "当前=" + ex.getMessage());
    }

    @Test
    void assertUnchanged_改动params段_400指引params端点() {
        String current = "{\"a\":1,\"params\":{\"K\":\"ENC:x\"}}";
        ServiceException ex = assertThrows(ServiceException.class, () -> support.assertParamsSectionUnchanged(
                current, "{\"a\":1,\"params\":{\"K\":\"ENC:x\",\"EVIL\":\"plain-injection\"}}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("参数") || ex.getMessage().contains("params"),
                "人话指引走 params 端点，当前=" + ex.getMessage());
    }
}
