package com.sixbean.testpike.common.genkit;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GenKitTest {


    @Test
    void registry_minTenFunctions_singleSourceNames() {
        assertTrue(GenFunction.values().length >= 10, "最小 10 函数红线");
        assertNotNull(GenFunction.byName("idCard"));
        assertNotNull(GenFunction.byName("phone"));
        assertNotNull(GenFunction.byName("base64Encode"));
        assertNull(GenFunction.byName("noSuchFn"));
        assertNull(GenFunction.byName(null));
        // Signature family registered in both mirrors (executor genkit.py pins 33 names).
        assertNotNull(GenFunction.byName("timestamp"));
        assertNotNull(GenFunction.byName("nonce"));
        assertNotNull(GenFunction.byName("sortConcat"));
        assertNotNull(GenFunction.byName("hmacSha256"));
        Map<String, java.util.List<GenFunction>> groups = GenFunction.groupedCatalog();
        int total = groups.values().stream().mapToInt(java.util.List::size).sum();
        assertEquals(GenFunction.values().length, total);
    }

    // ---------- Signature family (auth preset building blocks; anchor vectors cross-pinned with executor test_t272_genkit) ----------

    @Test
    void hmacSha256_knownAnchorVector() {
        assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                GenKit.invoke("hmacSha256", Map.of(
                        "text", "The quick brown fox jumps over the lazy dog", "key", "key")));
    }

    @Test
    void hmacSha256_missingKey_rejected() {
        assertThrows(ServiceException.class,
                () -> GenKit.invoke("hmacSha256", Map.of("text", "abc")));
    }

    @Test
    void sortConcat_ordersPairsLexicographically() {
        assertEquals("appKey=k&nonce=abc&timestamp=1",
                GenKit.invoke("sortConcat", Map.of("text", "timestamp=1&nonce=abc&appKey=k")));
        assertEquals("a=1;b=2",
                GenKit.invoke("sortConcat", Map.of("text", "b=2;a=1", "separator", ";")));
    }

    @Test
    void timestamp_unitSecondsAndMillis() {
        assertTrue(GenKit.invoke("timestamp", Map.of()).matches("\\d{10}"));
        assertTrue(GenKit.invoke("timestamp", Map.of("unit", "ms")).matches("\\d{13}"));
        assertThrows(ServiceException.class,
                () -> GenKit.invoke("timestamp", Map.of("unit", "us")));
    }

    @Test
    void nonce_lengthAndSeedReproducible() {
        assertTrue(GenKit.invoke("nonce", Map.of("length", "8")).matches("[a-z0-9]{8}"));
        assertEquals(GenKit.invoke("nonce", Map.of("seed", 5L, "length", "12")),
                GenKit.invoke("nonce", Map.of("seed", 5L, "length", "12")));
        assertTrue(GenFunction.byName("nonce").isRandom());
        assertFalse(GenFunction.byName("hmacSha256").isRandom());
    }


    @Test
    void idCard_passesGb11643Checksum() {
        String id = GenKit.idCard(20260930L);
        assertEquals(18, id.length());
        assertTrue(id.matches("\\d{17}[0-9X]"), "17 位数字+校验位");
        assertEquals(GenKit.idCardCheckDigit(id.substring(0, 17)), id.charAt(17),
                "校验位必须与 GB11643 算法一致");
        assertTrue(GenKit.KNOWN_AREA_CODES.contains(id.substring(0, 6)));
    }

    @Test
    void idCard_sameSeed_reproducible() {
        assertEquals(GenKit.idCard(7L), GenKit.idCard(7L));
    }

    @Test
    void idCard_knownValidChecksums() {
        assertEquals('X', GenKit.idCardCheckDigit("11010519491231002"));
        assertEquals('5', GenKit.idCardCheckDigit("44030519881206372"));
    }

    @Test
    void bankCard_passesLuhn() {
        String card = GenKit.bankCard(11L);
        assertEquals(19, card.length());
        assertTrue(GenKit.luhnValid(card), "Luhn 校验位必须成立");
        assertEquals(card, GenKit.bankCard(11L));
    }

    @Test
    void uscc_passesGb32100Checksum() {
        String code = GenKit.uscc(5L);
        assertEquals(18, code.length());
        assertTrue(code.matches("[0-9A-HJ-NP-RTUW-Y]{18}"), "禁用 I/O/S/Z/V 字符集");
        assertEquals(GenKit.usccCheckChar(code.substring(0, 17)), code.charAt(17));
        assertEquals(code, GenKit.uscc(5L));
    }

    @Test
    void phone_validSegment_andReproducible() {
        String phone = GenKit.phone(3L);
        assertEquals(11, phone.length());
        assertTrue(phone.matches("1[3-9]\\d{9}"));
        assertEquals(phone, GenKit.phone(3L));
        assertNotEqualsAcrossSeeds(GenKit.phone(1L), GenKit.phone(2L));
    }

    private static void assertNotEqualsAcrossSeeds(String a, String b) {
        assertTrue(!a.equals(b), "不同 seed 应产出不同值（观察面）");
    }

    @Test
    void chineseName_and_email_shape() {
        String name = GenKit.chineseName(9L);
        assertTrue(name.matches("[\\u4e00-\\u9fa5]{2,3}"), "2-3 个汉字");
        String email = GenKit.email(9L);
        assertTrue(email.matches("[a-z0-9.]+@[a-z0-9.\\-]+\\.[a-z]{2,}"));
        assertEquals(name, GenKit.chineseName(9L));
        assertEquals(email, GenKit.email(9L));
    }


    @Test
    void dateTime_formatApplies() {
        String v = GenKit.dateTime("yyyy-MM-dd HH:mm:ss");
        assertTrue(v.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), v);
    }


    @Test
    void md5_sha256_knownVectors() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", GenKit.md5("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GenKit.sha256("abc"));
    }

    @Test
    void base64_roundTrip() {
        assertEquals("5L2g5aW9", GenKit.base64Encode("你好"));
        assertEquals("你好", GenKit.base64Decode("5L2g5aW9"));
    }

    @Test
    void jsonFormat_minify_andRejectsInvalid() {
        assertEquals("{\n  \"a\" : 1\n}", GenKit.jsonFormat("{\"a\":1}"));
        assertEquals("{\"a\":1}", GenKit.jsonMinify("{ \"a\" : 1 }"));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> GenKit.jsonFormat("not-json"));
        assertTrue(e.getMessage().contains("不是合法的 JSON"), e.getMessage());
    }



    @Test
    void registry_coreTwenty_allNewNamesRegistered() {
        assertTrue(GenFunction.values().length >= 20, "核心 20 函数红线");
        for (String n : new String[]{"companyName", "address", "lngLat", "plateNumber", "iban",
                "sha1", "urlEncode", "unicodeEscape", "radix", "timestampToDate",
                "jsonPathExtract", "flatten", "camelCase", "trim", "wordCount", "regexMatch"}) {
            assertNotNull(GenFunction.byName(n), "注册表缺函数: " + n);
        }
    }

    @Test
    void idGenFamily_newMembers_shape_andReproducible() {
        String iban = GenKit.invoke("iban", Map.of("seed", 7L));
        assertTrue(iban.matches("DE\\d{2}[0-9A-Z]{18}"), iban);
        assertEquals(1, GenKit.ibanRemainder(iban), "mod97 自洽（校验位使余数为 1）");
        assertTrue(GenKit.ibanValid(iban));
        assertTrue(!GenKit.ibanValid("DE00" + iban.substring(4)), "篡改校验位必须判非法");
        assertEquals(iban, GenKit.invoke("iban", Map.of("seed", 7L)));
        assertTrue(GenKit.invoke("plateNumber", Map.of("seed", 4L)).matches("[京津沪粤苏浙鲁川][A-HJ-NP-Z][A-HJ-NP-Z0-9]{5}"));
        assertTrue(GenKit.invoke("lngLat", Map.of("seed", 4L)).matches("\\d{2,3}\\.\\d{6},\\d{1,2}\\.\\d{6}"));
        assertTrue(GenKit.invoke("companyName", Map.of("seed", 4L)).endsWith("有限公司"));
        assertTrue(GenKit.invoke("address", Map.of("seed", 4L)).endsWith("号"));
        assertEquals(GenKit.invoke("plateNumber", Map.of("seed", 4L)),
                GenKit.invoke("plateNumber", Map.of("seed", 4L)));
    }

    @Test
    void codecFamily_newVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", GenKit.invoke("sha1", Map.of("text", "abc")));
        assertEquals("a+b%26c%3D1", GenKit.invoke("urlEncode", Map.of("text", "a b&c=1")));
        assertEquals("%E4%BD%A0%E5%A5%BD", GenKit.invoke("urlEncode", Map.of("text", "你好")));
        assertEquals("\\u4f60\\u597da", GenKit.invoke("unicodeEscape", Map.of("text", "你好a")));
        assertEquals("ff", GenKit.invoke("radix", Map.of("text", "255", "to", "16")));
        assertEquals("255", GenKit.invoke("radix", Map.of("text", "ff", "from", "16", "to", "10")));
        assertEquals("11111111", GenKit.invoke("radix", Map.of("text", "255", "to", "2")));
        RuntimeException bad = assertThrows(RuntimeException.class,
                () -> GenKit.invoke("radix", Map.of("text", "zz", "from", "16", "to", "10")));
        assertTrue(bad.getMessage().contains("不是合法的"), bad.getMessage());
        RuntimeException nobase = assertThrows(RuntimeException.class,
                () -> GenKit.invoke("radix", Map.of("text", "ff", "to", "99")));
        assertTrue(nobase.getMessage().contains("进制必须"), nobase.getMessage());
    }

    @Test
    void transformFamily_newVectors() {
        assertEquals("2025-03-01 00:00:00", GenKit.invoke("timestampToDate", Map.of("text", "1740787200")));
        assertEquals("2025-03-01", GenKit.invoke("timestampToDate", Map.of("text", "1740787200", "format", "yyyy-MM-dd")));
        assertEquals("2025-03-01 00:00:00", GenKit.invoke("timestampToDate", Map.of("text", "1740787200000")));
        assertEquals("20", GenKit.invoke("jsonPathExtract", Map.of("text", "{\"a\":{\"b\":[10,20]}}", "path", "$.a.b[1]")));
        RuntimeException miss = assertThrows(RuntimeException.class,
                () -> GenKit.invoke("jsonPathExtract", Map.of("text", "{\"a\":1}", "path", "$.x")));
        assertTrue(miss.getMessage().contains("未命中"), miss.getMessage());
        assertEquals("a.b=1\nc[0]=2\nc[1]=3", GenKit.invoke("flatten", Map.of("text", "{\"a\":{\"b\":1},\"c\":[2,3]}")));
        assertEquals("orderIdList", GenKit.invoke("camelCase", Map.of("text", "order_id-list")));
        assertEquals("a b", GenKit.invoke("trim", Map.of("text", "  a b  ")));
        assertEquals("4", GenKit.invoke("wordCount", Map.of("text", "hello 世界 abc 123")));
        assertEquals("42", GenKit.invoke("regexMatch", Map.of("text", "id=42", "pattern", "id=(\\d+)")));
        RuntimeException nomatch = assertThrows(RuntimeException.class,
                () -> GenKit.invoke("regexMatch", Map.of("text", "abc", "pattern", "id=(\\d+)")));
        assertTrue(nomatch.getMessage().contains("未命中"), nomatch.getMessage());
    }

    @Test
    void invoke_dispatchesByRegistryName_rejectsUnknown() {
        assertEquals(GenKit.idCard(1L), GenKit.invoke("idCard", Map.of("seed", 1L)));
        assertEquals("abc", GenKit.invoke("base64Decode",
                Map.of("text", GenKit.base64Encode("abc"))));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> GenKit.invoke("noSuchFn", Map.of()));
        assertTrue(e.getMessage().contains("noSuchFn"), "未注册函数人话点名");
    }

    // ---------- Read-only analysis family: served by server-side dialog tools,
    // ------------- never evaluable from a data-fabrication template ----------

    @Test
    void analysisFamily_registeredInSingleSourceCatalog() {
        GenFunction coverage = GenFunction.byName("coverageSummary");
        GenFunction density = GenFunction.byName("assertionDensity");
        assertNotNull(coverage, "coverageSummary 必须注册进 GenFunction 单源");
        assertNotNull(density, "assertionDensity 必须注册进 GenFunction 单源");
        for (GenFunction f : new GenFunction[]{coverage, density}) {
            assertEquals("分析族", f.group());
            assertTrue(f.isAnalysis());
            assertFalse(f.isRandom(), "分析族不得进随机族清单（seed 事件流/下传清单零污染）");
            assertFalse(f.paramSchema().isEmpty(), "目录条目必须带参数说明");
            assertFalse(f.description().isBlank());
        }
        assertTrue(GenFunction.groupedCatalog().containsKey("分析族"),
                "只读目录投影自动包含分析族（零第二资产地）");
        // Stronger pin: the fabrication-dispatchable set (everything but the analysis
        // family) must stay exactly what the executor mirror registers (genkit.py
        // ALL_FUNCTION_NAMES = 33 names); analysis functions never enter that mirror.
        long fabricable = java.util.Arrays.stream(GenFunction.values())
                .filter(f -> !f.isAnalysis()).count();
        assertEquals(33, fabricable, "造数可求值函数集须与执行器镜像同数（新增只进本枚举）");
        assertEquals(4, java.util.Arrays.stream(GenFunction.values())
                .filter(GenFunction::isAnalysis).count(), "分析族四枚（覆盖摘要/断言密度/回归演练草案/演练风险报告卡）");
    }

    @Test
    void analysisFamily_refusedByTemplateEvaluation_failClosed() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> GenKit.invoke("coverageSummary", Map.of()));
        assertTrue(e.getMessage().contains("coverageSummary"), "拒绝原因必须点名被拒函数");
        assertTrue(e.getMessage().contains("AI 对话"), "指路必须点名可用位置");

        assertThrows(ServiceException.class,
                () -> GenKit.invoke("assertionDensity", Map.of()));

        // Template resolution must fail closed, not emit partial text.
        assertThrows(ServiceException.class,
                () -> GenTemplateResolver.resolve("${gen.coverageSummary()}", null));

        // Data-fabrication surface stays intact: the existing families still evaluate.
        assertEquals(GenKit.idCard(7L), GenKit.invoke("idCard", Map.of("seed", 7L)));
        assertEquals("5", GenKit.invoke("wordCount", Map.of("text", "a b c d e")));
        assertFalse(GenFunction.byName("md5").isAnalysis());
    }
}
