package com.sixbean.testpike.common.genkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public enum GenFunction {

    
    ID_CARD("idCard", "证件族", "生成带 GB11643 校验位的 18 位身份证号",
            List.of("seed?:long=显式随机种子（缺省=系统随机）"), "440305198812063725"),
    
    BANK_CARD("bankCard", "证件族", "生成带 Luhn 校验位的银行卡号（真实 BIN 前缀）",
            List.of("seed?:long=显式随机种子"), "6222021234567890128"),
    
    USCC("uscc", "证件族", "生成带 GB32100 校验位的 18 位统一社会信用代码",
            List.of("seed?:long=显式随机种子"), "91310000MA1FL0XX3X"),
    
    PHONE("phone", "证件族", "生成 1 开头的 11 位合法手机号号段",
            List.of("seed?:long=显式随机种子"), "13812345678"),
    
    CHINESE_NAME("chineseName", "证件族", "生成常见姓氏的中文姓名（2-3 字）",
            List.of("seed?:long=显式随机种子"), "张伟"),
    
    EMAIL("email", "证件族", "生成本地部分随机、域名取自内置表的邮箱地址",
            List.of("seed?:long=显式随机种子"), "t7k2m9@example.com"),
    
    DATE_TIME("dateTime", "变换族", "输出当前时刻（yyyy-MM-dd HH:mm:ss），format 可指定 pattern",
            List.of("format?:string=日期格式（缺省 yyyy-MM-dd HH:mm:ss）"), "2026-09-30 01:20:00"),
    
    MD5("md5", "编码族", "文本 MD5 摘要（32 位小写十六进制）",
            List.of("text:string=必填原文"), "900150983cd24fb0d6963f7d28e17f72"),
    
    SHA256("sha256", "编码族", "文本 SHA-256 摘要（64 位小写十六进制）",
            List.of("text:string=必填原文"), "ba7816bf8f01cfea414140de5dae2223…"),
    
    BASE64_ENCODE("base64Encode", "编码族", "UTF-8 文本 Base64 编码",
            List.of("text:string=必填原文"), "5L2g5aW9=d"),
    
    BASE64_DECODE("base64Decode", "编码族", "Base64 解码为 UTF-8 文本",
            List.of("text:string=必填密文"), "你好"),
    
    JSON_FORMAT("jsonFormat", "变换族", "JSON 缩进美化（非法 JSON 报错说明原因）",
            List.of("text:string=必填 JSON 文本"), "{\"a\": 1}"),
    
    JSON_MINIFY("jsonMinify", "变换族", "JSON 去空白压缩（非法 JSON 报错说明原因）",
            List.of("text:string=必填 JSON 文本"), "{\"a\":1}"),
    
    COMPANY_NAME("companyName", "证件族", "生成「地区+字号+行业+有限公司」形态的公司名称",
            List.of("seed?:long=显式随机种子"), "深圳市鑫源科技有限公司"),
    
    ADDRESS("address", "证件族", "生成内置地市+区县+路名+门牌形态的中文地址",
            List.of("seed?:long=显式随机种子"), "深圳市南山区中山路128号"),
    
    LNG_LAT("lngLat", "证件族", "生成中国陆地范围内的经纬度对（lng.lat,6 位小数）",
            List.of("seed?:long=显式随机种子"), "113.935123,22.547891"),
    
    PLATE_NUMBER("plateNumber", "证件族", "生成省份汉字+字母+5 位（除 I/O）的车牌形态",
            List.of("seed?:long=显式随机种子"), "粤B8K2Q5"),
    
    IBAN("iban", "证件族", "生成 mod97 校验自洽的 DE 版式 IBAN（ISO 13616）",
            List.of("seed?:long=显式随机种子"), "DE89370400440532013000"),
    
    SHA1("sha1", "编码族", "文本 SHA-1 摘要（40 位小写十六进制）",
            List.of("text:string=必填原文"), "a9993e364706816aba3e25717850c26c9cd0d89d"),
    
    URL_ENCODE("urlEncode", "编码族", "application/x-www-form-urlencoded 编码（空格→+，UTF-8 百分号大写）",
            List.of("text:string=必填原文"), "a+b%26c%3D1"),
    
    UNICODE_ESCAPE("unicodeEscape", "编码族", "非 ASCII 字符转反斜杠-u 四位十六进制（小写）",
            List.of("text:string=必填原文"), "\\u4f60\\u597d"),
    
    RADIX("radix", "编码族", "任意 2-36 进制整数互转（输出小写字母）",
            List.of("text:string=必填数字串", "from?:string=源进制（缺省 10）", "to:string=目标进制"), "ff"),
    
    TIMESTAMP_TO_DATE("timestampToDate", "变换族", "秒/毫秒时间戳转日期文本（缺省 UTC，跨语言结果一致）",
            List.of("text:string=必填时间戳", "format?:string=日期格式", "zone?:string=时区（缺省 UTC）"), "2025-03-01 00:00:00"),
    
    JSON_PATH_EXTRACT("jsonPathExtract", "变换族", "极简 JSONPath（$.a.b[0] 逐段寻址）提取标量，未命中时报错说明原因",
            List.of("text:string=必填 JSON 文本", "path:string=取值路径"), "20"),
    
    FLATTEN("flatten", "变换族", "JSON 拍平为 key.path= / key[i]= 行文本",
            List.of("text:string=必填 JSON 文本"), "a.b=1\nc[0]=2"),
    
    CAMEL_CASE("camelCase", "变换族", "下划线/中划线/空格分词转小驼峰",
            List.of("text:string=必填原文"), "orderIdList"),
    
    TRIM("trim", "变换族", "去除首尾空白字符",
            List.of("text:string=必填原文"), "a b"),
    
    WORD_COUNT("wordCount", "变换族", "按空白分词统计词数",
            List.of("text:string=必填文本"), "4"),
    
    REGEX_MATCH("regexMatch", "变换族", "正则首个匹配（有捕获组取第 1 组），未命中或正则非法时报错说明原因",
            List.of("text:string=必填文本", "pattern:string=正则表达式"), "42"),
    /** Current epoch timestamp, seconds (default) or milliseconds. Used by auth signature templates. */
    TIMESTAMP("timestamp", "签名族", "当前时刻时间戳（unit=s 缺省 10 位秒，unit=ms 13 位毫秒）",
            List.of("unit?:string=s（缺省）或 ms"), "1700000000"),
    /** Random alphanumeric nonce; reproducible via explicit seed like the rest of the random family. */
    NONCE("nonce", "签名族", "小写字母+数字随机串（length 缺省 16，同 seed 可复现）",
            List.of("length?:int=长度 8-64", "seed?:long=显式随机种子"), "k3f9a1zq7m2x5c8d"),
    /** Re-order k=v pairs lexicographically and join — the canonical query-string assembly step. */
    SORT_CONCAT("sortConcat", "签名族", "k=v 对按字典序重排后以分隔符拼接（缺 &，可指定）",
            List.of("text:string=必填 k=v 串", "separator?:string=分隔符（缺省 &）"),
            "appKey=k&nonce=abc&timestamp=1"),
    /** HMAC-SHA256 over UTF-8 text with the given key. Templates must reference the key via @cred only. */
    HMAC_SHA256("hmacSha256", "签名族", "text 以 key 做 HMAC-SHA256（64 位小写十六进制）",
            List.of("text:string=必填待签原文", "key:string=必填密钥（装配侧只接受 @cred 引用）"),
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"),
    /** Requirement-coverage digest, read-only: executed by the server-side dialog analysis tool. */
    COVERAGE_SUMMARY("coverageSummary", "分析族",
            "汇总当前项目需求点的覆盖情况，并列出未被覆盖的需求点",
            List.of("chapter?:string=需求章节名称（缺省=项目全部章节）"),
            "需求点 40 个，已覆盖 31 个；未覆盖：REQ-12、REQ-27"),
    /** Assertion-density digest, read-only: executed by the server-side dialog analysis tool. */
    ASSERTION_DENSITY("assertionDensity", "分析族",
            "按项目内用例最新版本实算步骤数与断言步骤数，输出平均断言密度",
            List.of("endType?:string=终端类型筛选（缺省=全部终端用例）"),
            "用例 25 个，共 180 步，断言步 66，平均断言密度 0.37"),
    /** Regression-rehearsal draft assembly, read-only: turns one suite or one plan template of
     *  the current project into a plan-create shaped draft; the row lands only after a human confirms. */
    REHEARSAL_DRAFT("rehearsalDraft", "分析族",
            "按回归集或既有计划装配一份回归演练草案（只生成草案不写库，人工确认后走计划创建链）",
            List.of("suiteId?:int=回归集 ID（与 planId 二选一）", "planId?:int=既有计划 ID（与 suiteId 二选一）"),
            "演练草案：来源=回归集 77「支付回归集」，3 条用例，准出=通过率≥0.95 且无未关闭缺陷"),
    /** Rehearsal risk report card, read-only: batch result ledger plus the
     *  gate verdict and the coverage view passed through from their existing
     *  computations (same numbers as the batch badge; never a second formula). */
    REHEARSAL_RISK_REPORT("rehearsalRiskReport", "分析族",
            "取一次回归演练批次的风险报告卡：结果账统计、准出键对照与需求覆盖（只读，不改变批次或计划状态）；回执附带成品展示句（批次状态、判定结论、需求覆盖各一句），回答时直接引用展示句原文，不要复述内部字段名或重新计算",
            List.of("batchId:int=演练批次编号（草案确认回执或演练启动回执中的编号）"),
            "批次 512 判定不通过：通过率 82.0% 低于阈值 95.0%，失败 2 条，需求覆盖 31/40");

    private final String functionName;
    private final String group;
    private final String description;
    private final List<String> paramSchema;
    private final String example;

    GenFunction(String functionName, String group, String description,
                List<String> paramSchema, String example) {
        this.functionName = functionName;
        this.group = group;
        this.description = description;
        this.paramSchema = paramSchema;
        this.example = example;
    }

    public String functionName() {
        return functionName;
    }

    public String group() {
        return group;
    }

    public String description() {
        return description;
    }

    public List<String> paramSchema() {
        return paramSchema;
    }

    public String example() {
        return example;
    }

    
    private static final java.util.Set<GenFunction> RANDOM_FAMILY = java.util.EnumSet.of(
            ID_CARD, BANK_CARD, USCC, PHONE, CHINESE_NAME, EMAIL,
            COMPANY_NAME, ADDRESS, LNG_LAT, PLATE_NUMBER, IBAN, NONCE);

    
    public boolean isRandom() {
        return RANDOM_FAMILY.contains(this);
    }

    /** Single-source membership test for the read-only analysis family: these functions
     *  are executed by server-side dialog analysis tools and never by template evaluation. */
    private static final java.util.Set<GenFunction> ANALYSIS_FAMILY =
            java.util.EnumSet.of(COVERAGE_SUMMARY, ASSERTION_DENSITY, REHEARSAL_DRAFT,
                    REHEARSAL_RISK_REPORT);

    /** Whether this function belongs to the read-only analysis family. */
    public boolean isAnalysis() {
        return ANALYSIS_FAMILY.contains(this);
    }

    
    public static GenFunction byName(String name) {
        if (name == null) {
            return null;
        }
        for (GenFunction f : values()) {
            if (f.functionName.equals(name)) {
                return f;
            }
        }
        return null;
    }

    
    public static List<String> allNames() {
        return java.util.Arrays.stream(values()).map(GenFunction::functionName).toList();
    }

    
    public static Map<String, List<GenFunction>> groupedCatalog() {
        Map<String, List<GenFunction>> out = new LinkedHashMap<>();
        for (GenFunction f : values()) {
            out.computeIfAbsent(f.group, k -> new java.util.ArrayList<>()).add(f);
        }
        return out;
    }

    static {
        for (GenFunction f : values()) {
            if (!f.functionName.matches("[a-z][A-Za-z0-9]*")) {
                throw new IllegalStateException("genkit 注册函数名不合法: " + f.functionName);
            }
        }
    }
}
