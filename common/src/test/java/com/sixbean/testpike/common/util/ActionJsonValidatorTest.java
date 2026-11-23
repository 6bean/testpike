package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ActionJsonValidatorTest {

    private final ActionJsonValidator validator = new ActionJsonValidator();

    @Test
    void CRED占位符_标识符键放行() {
        String json = """
                {"version":"v2","caseId":"TC-CRED-001","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#pwd","chain":["css"]},"value":"{{CRED:pwd}}"}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed());
    }

    @Test
    void CRED占位符_点号变体键拒绝点名() {
        String json = """
                {"version":"v2","caseId":"TC-CRED-002","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#pwd","chain":["css"]},"value":"{{CRED:bad.key}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("{{CRED:bad.key}}")),
                "违例须点名原占位符: " + result.errors());
    }

    @Test
    void 角色名撞CRED保留命名空间_拒() {
        String json = """
                {"version":"v2","caseId":"TC-CRED-003","endType":"web",
                 "roles":[{"name":"CRED","binding":"shared"}],
                 "steps":[{"action":"input","target":{"css":"#pwd","chain":["css"]},"value":"{{CRED.token}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("CRED") && e.contains("保留")),
                "须人话点名保留命名空间撞名: " + result.errors());
    }

    @Test
    void validate_完整动作JSON_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}}],
                 "steps":[{"action":"click","target":{"css":"#login-btn","chain":["css"]}},
                          {"action":"expect","target":{"text":"登录成功"}}],
                 "env":{"ENV":"rtl-test"}}
                """;
        assertTrue(validator.validate(json).passed());
    }

    @Test
    void validate_缺version_失败() {
        String json = """
                {"caseId":"TC-RTL-001","steps":[],"env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed());
    }

    @Test
    void validate_未知动作_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"fly_to_moon","target":{"css":"#x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed());
    }

    @Test
    void validate_步骤为空_失败() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web","steps":[],"env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed());
    }

    @Test
    void validate_locator缺chain_失败() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"click","target":{"css":"#x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed());
    }

    @Test
    void validate_含if分支合法动作_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"if","condition":"x==1",
                           "steps":[{"action":"expect","target":{"text":"a","chain":["text"]}}],
                           "elseSteps":[{"action":"expect","target":{"text":"b","chain":["text"]}}]}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed());
    }

    @Test
    void validate_嵌套超5层_失败() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"if","condition":"a","steps":[
                    {"action":"if","condition":"b","steps":[
                      {"action":"if","condition":"c","steps":[
                        {"action":"if","condition":"d","steps":[
                          {"action":"if","condition":"e","steps":[
                            {"action":"if","condition":"f","steps":[
                              {"action":"expect","target":{"text":"x"}}]}]}]}]}]}]}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed());
    }

    @Test
    void validate_步骤超50条_失败() {
        StringBuilder sb = new StringBuilder(
                "{\"version\":\"v2\",\"caseId\":\"TC-RTL-001\",\"endType\":\"web\",\"steps\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"action\":\"expect\",\"target\":{\"text\":\"t").append(i).append("\"}}");
        }
        sb.append("],\"env\":{\"ENV\":\"x\"}}");
        assertFalse(validator.validate(sb.toString()).passed());
    }

    @Test
    void validate_CSS定位单策略_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}"}}],
                 "steps":[{"action":"click","target":{"css":"#login-btn","chain":["css"]}}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed());
    }


    
    private static String legalV2Json() {
        return """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}}],
                 "steps":[{"action":"click","target":{"css":"#login-btn","chain":["css"]}},
                          {"action":"expect","target":{"text":"登录成功"}}],
                 "env":{"ENV":"rtl-test"}}""";
    }

    @Test
    void validate_回显标记词前缀_容错通过() {
        String marked = "STAGE2_ACTION_JSON\n" + legalV2Json();
        assertTrue(validator.validate(marked).passed(),
                "回显标记词前缀应剥标记后解析，不得判非法: " + validator.validate(marked).errors());
    }

    @Test
    void validate_前置说明句包裹JSON_容错通过() {
        String wrapped = "以下是按动作 JSON v2 生成的脚本：\n" + legalV2Json()
                + "\n以上就是完整的脚本内容。";
        assertTrue(validator.validate(wrapped).passed(),
                "说明句包裹应提取首个平衡 JSON 段解析: " + validator.validate(wrapped).errors());
    }

    @Test
    void validate_围栏与标记词混合包裹_容错通过() {
        String fenced = "```json\nSTAGE2_ACTION_JSON\n" + legalV2Json() + "\n```";
        assertTrue(validator.validate(fenced).passed(),
                "围栏+标记词混合应双重剥离后解析: " + validator.validate(fenced).errors());
    }

    @Test
    void validate_纯文本无任何JSON_仍失败人话() {
        var result = validator.validate("抱歉，我无法生成脚本，请检查需求描述是否完整。");
        assertFalse(result.passed());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("JSON")),
                "失败原因须人话点名非法 JSON: " + result.errors());
    }

    @Test
    void validate_标记词回显后JSON本体非法_仍失败() {
        String marked = "STAGE2_ACTION_JSON\n"
                + "{\"version\":\"v2\",\"caseId\":\"TC-RTL-001\",\"endType\":\"web\","
                + "\"steps\":[{\"action\":\"fly_to_moon\",\"target\":{\"css\":\"#x\"}}],\"env\":{\"ENV\":\"x\"}}";
        assertFalse(validator.validate(marked).passed(), "未知动作仍须拒绝");
    }


    @Test
    void validate_goto用value携URL_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"goto","value":"http://localhost:8899/login"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "goto 缺 url（value 携 URL 属契约违例）须拒绝: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("goto") && e.contains("url")),
                "失败原因须人话点名 goto 缺 url: " + result.errors());
    }

    @Test
    void validate_goto顶层url字段_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"goto","url":"{{ENV:URL}}/login"}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "goto 顶层 url（执行器兼容形态）不得误杀: " + validator.validate(json).errors());
    }

    @Test
    void validate_goto嵌套if内缺url_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"if","condition":"x==1","steps":[
                    {"action":"goto","value":"http://localhost:8899/login"}]}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "嵌套 goto 缺 url 同样须拒绝");
    }

    @Test
    void validate_setup为对象非数组_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":{"appUrl":"http://localhost:8899","credentials":{"u":"admin"}},
                 "steps":[{"action":"goto","target":{"url":"{{ENV:URL}}"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "setup 为对象（契约=步骤数组）须拒绝: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("setup")),
                "失败原因须人话点名 setup: " + result.errors());
    }

    @Test
    void validate_setup内goto缺url_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","value":"http://localhost:8899/login"}],
                 "steps":[{"action":"goto","target":{"url":"{{ENV:URL}}"}}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "setup 内 goto 缺 url 须拒绝");
    }

    @Test
    void validate_占位符点号语法_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","url":"{{ENV.BASE_URL}}"}],
                 "steps":[{"action":"goto","url":"{{ENV.BASE_URL}}/login"},
                          {"action":"input","target":{"css":"#user","chain":["css"]},"value":"{{ENV.USERNAME}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "点号占位符（非契约语法）须拒绝: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("占位符") && e.contains("ENV.BASE_URL")),
                "失败原因须人话点名非法占位符: " + result.errors());
    }

    @Test
    void validate_未知命名空间占位符_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"goto","url":"{{VAR:HOST}}/login"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "未声明的 VAR 引用（v2.22 自检）须拒绝: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("占位符") || e.contains("VAR")),
                "失败原因须点名占位符: " + result.errors());
    }

    @Test
    void validate_纯未知命名空间占位符_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"goto","url":"{{FOO:HOST}}/login"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "未知命名空间占位符须拒绝");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("占位符")),
                "失败原因须点名占位符: " + result.errors());
    }

    @Test
    void validate_契约三命名空间冒号占位符_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}},
                          {"action":"input","target":{"css":"#user","chain":["css"]},"value":"{{DATA:testUser}}"},
                          {"action":"click","target":{"locator":"{{PACK:promo.apply}}","chain":["css"]}}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "合法冒号占位符不得误杀: " + validator.validate(json).errors());
    }


    
    private static String expectJson(String stepBody) {
        return """
                {"version":"v2","caseId":"TC-B10","endType":"web",
                 "steps":[{"action":"expect","target":{"css":"#amount","chain":["css"]},%s}],
                 "env":{"ENV":"x"}}
                """.formatted(stepBody);
    }

    @Test
    void validate_matchType枚举外_拒绝含可选值清单() {
        var result = validator.validate(expectJson("\"matchType\":\"between\""));
        assertFalse(result.passed(), "枚举外 matchType 须拒绝（生成即 A6 重生成）");
        assertTrue(result.errors().stream().anyMatch(
                        e -> e.contains("matchType") && e.contains("range") && e.contains("regex")),
                "失败原因须人话点名 matchType 与可选值清单: " + result.errors());
    }

    @Test
    void validate_range缺两端_拒绝() {
        var result = validator.validate(expectJson("\"matchType\":\"range\""));
        assertFalse(result.passed(), "range 缺 min/max 两端须拒绝（IT-B10-05 同族契约）");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("min") && e.contains("max")),
                "失败原因须点名 min/max: " + result.errors());
    }

    @Test
    void validate_range单端与非数值_拒绝并放行合法端() {
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"range\",\"min\":\"abc\"")).passed(), "min 非数值须拒绝");
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"range\",\"min\":100,\"bounds\":\"half\"")).passed(), "bounds 枚举外须拒绝");
        assertTrue(validator.validate(expectJson(
                        "\"matchType\":\"range\",\"min\":100,\"max\":200,\"bounds\":\"open\"")).passed(),
                "双开区间合法产物不得误杀: " + validator.validate(expectJson(
                        "\"matchType\":\"range\",\"min\":100,\"max\":200,\"bounds\":\"open\"")).errors());
    }

    @Test
    void validate_regex非法pattern_拒绝转重试回退链() {
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"regex\",\"pattern\":\"(a+)+b\"")).passed(), "嵌套量词须拒（防重正则入库）");
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"regex\",\"pattern\":\"[unclosed\"")).passed(), "不可编译正则须拒");
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"regex\",\"pattern\":\"" + "x".repeat(201) + "\"")).passed(), "超 200 字符须拒");
        assertFalse(validator.validate(expectJson(
                "\"matchType\":\"regex\"")).passed(), "regex 缺 pattern 须拒");
        assertTrue(validator.validate(expectJson(
                        "\"matchType\":\"regex\",\"pattern\":\"^PO\\\\d{8}$\"")).passed(),
                "常规单号正则不得误杀");
    }

    @Test
    void validate_matchType缺省与contains_零漂移() {
        assertTrue(validator.validate("""
                {"version":"v2","caseId":"TC-B10","endType":"web",
                 "steps":[{"action":"expect","target":{"text":"登录成功"}}],"env":{"ENV":"x"}}
                """).passed(), "无 matchType 存量产物不得误杀");
        assertTrue(validator.validate(expectJson("\"matchType\":\"contains\"")).passed(),
                "contains 不得附加字段校验");
    }

    @Test
    void validate_loop嵌套expect非法matchType_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B10","endType":"web",
                 "steps":[{"action":"loop","times":3,"steps":[
                     {"action":"expect","matchType":"fuzzy","target":{"text":"x"}}]}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "loop 嵌套 expect 的非法 matchType 须拒: " + result.errors());
    }


    @Test
    void validate_B11六动作语义合法_通过() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "setup":[{"action":"sql.query","conn":"{{ENV:DB_SIT}}","sql":"SELECT id FROM t_order WHERE status=:s","params":{"s":"PENDING"},"saveAs":"oid"}],
                 "steps":[
                   {"action":"http.request","method":"POST","url":"{{ENV:API}}/api/v1/orders","expect":{"status":200},"poll":{"intervalMs":2000,"timeoutMs":30000,"until":"match"},"saveAs":"resp1"},
                   {"action":"net.assert","match":{"urlPattern":"*/api/v1/orders*","method":"POST"},"expect":{"status":200,"body":[{"path":"$.data.id","matchType":"contains","value":"ORD"}]}},
                   {"action":"console.assert","level":["error"],"mode":"fail","scope":"sinceLast"},
                   {"action":"sql.assert","conn":"{{ENV:DB_SIT}}","sql":"SELECT count(*) FROM t_order WHERE status=:s","params":{"s":"PENDING"},"expect":{"type":"scalar","matchType":"range","min":10,"max":12,"bounds":"open"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "B11 合法产物不得误杀: " + result.errors());
    }

    @Test
    void validate_consoleAssert非法mode_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"console.assert","mode":"strict"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "非法 console mode 须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("mode")),
                "失败原因须人话点名 mode: " + result.errors());
    }

    @Test
    void validate_netAssert越界JsonPath_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"net.assert","match":{"urlPattern":"*/api/x*"},
                           "expect":{"body":[{"path":"$.data[?(@.id==1)]","matchType":"contains","value":"x"}]}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "越界 JsonPath 须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("JsonPath")),
                "失败原因须点名 JsonPath: " + result.errors());
    }

    @Test
    void validate_sqlAssert词法违例_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"sql.assert","conn":"db1","sql":"DELETE FROM t_order WHERE id=1"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "sql.assert 非 SELECT 词法须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("sql.assert")),
                "失败原因须点名 sql.assert: " + result.errors());
    }

    @Test
    void validate_sqlAssert缺conn_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"sql.assert","sql":"SELECT count(*) FROM t"}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "sql.assert 缺 conn 须拒");
    }

    @Test
    void validate_sqlExec_AI自动产_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"sql.exec","conn":"db1","sql":"DELETE FROM t_order WHERE status=:s","params":{"s":"PENDING"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "sql.exec AI 产物须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("sql.exec") && e.contains("人工")),
                "失败原因须人话点名 sql.exec+人工通道: " + result.errors());
    }

    @Test
    void validate_VAR占位符合法引用_通过() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "setup":[{"action":"sql.query","conn":"db1","sql":"SELECT id FROM t_order LIMIT 1","saveAs":"oid"}],
                 "steps":[{"action":"input","target":{"css":"#order","chain":["css"]},"value":"{{VAR:oid}}"},
                          {"action":"goto","url":"{{ENV:API}}/o/{{VAR:oid.id}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "合法 VAR 链不得误杀: " + result.errors());
    }

    @Test
    void validate_VAR未定义引用_自检拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#x","chain":["css"]},"value":"{{VAR:ghost.id}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "未定义 VAR 引用须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ghost.id") && e.contains("saveAs")),
                "失败原因须点名缺键+前序 saveAs: " + result.errors());
    }

    @Test
    void validate_VAR引用先于声明_自检拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#x","chain":["css"]},"value":"{{VAR:resp1.id}}"},
                          {"action":"http.request","method":"GET","url":"{{ENV:API}}/x","saveAs":"resp1"}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "VAR 引用先于声明须拒");
    }

    @Test
    void validate_expectCapture声明可被引用_通过() {
        String json = """
                {"version":"v2","caseId":"TC-B11","endType":"web",
                 "steps":[{"action":"expect","target":{"css":"#no","chain":["css"]},"matchType":"contains","value":"PO","capture":"ordNo"},
                          {"action":"input","target":{"css":"#q","chain":["css"]},"value":"{{VAR:ordNo}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "capture 声明链不得误杀: " + result.errors());
    }


    @Test
    void validate_loop子步默认迭代变量item_index_放行() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"loop","items":["sku-a","sku-b"],
                           "steps":[{"action":"input","target":{"css":"#kw","chain":["css"]},"value":"{{item}}"},
                                    {"action":"expect","target":{"text":"第 {{index}} 行"}}]}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "items 默认 item + index 不得误杀: " + result.errors());
    }

    @Test
    void validate_loop自定义var与count默认index_放行() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"loop","count":3,"var":"round",
                           "steps":[{"action":"input","target":{"css":"#u","chain":["css"]},"value":"user-{{round}}"},
                                    {"action":"wait","target":{"url":"{{ENV:BASE}}/r/{{index}}"}}]}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "count 形态自定义 var=round + index 不得误杀: " + result.errors());
    }

    @Test
    void validate_嵌套loop内外层迭代变量子树内均可用_放行() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"loop","items":["a"],"var":"sku",
                           "steps":[{"action":"loop","count":2,"var":"item",
                                     "steps":[{"action":"input","target":{"css":"#f","chain":["css"]},
                                               "value":"{{sku}}-{{item}}-{{index}}"}]}]}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "嵌套继承作用域不得误杀: " + result.errors());
    }

    @Test
    void validate_循环作用域外裸迭代占位_仍拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#x","chain":["css"]},"value":"{{item}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "无 loop 声明的裸 {{item}} 须拒（豁免仅限声明子树）");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("占位符") && e.contains("item")),
                "失败原因须点名非法占位符: " + result.errors());
    }

    @Test
    void validate_内层var外层子步不可见_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"loop","count":2,"var":"outer",
                           "steps":[{"action":"loop","count":2,"var":"inner",
                                     "steps":[{"action":"wait","target":{"url":"{{ENV:P}}/x"}}]},
                                    {"action":"input","target":{"css":"#y","chain":["css"]},"value":"{{inner}}"}]}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "内层声明的 var 在兄弟步须拒");
    }

    @Test
    void validate_loop子步内非法语法_豁免不误放() {
        String json = """
                {"version":"v2","caseId":"TC-B12","endType":"web",
                 "steps":[{"action":"loop","items":["a"],
                           "steps":[{"action":"input","target":{"css":"#z","chain":["css"]},
                                     "value":"{{ENV.BASE_URL}}-{{item}}-{{FOO:bar}}"}]}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "loop 内点号/未知命名空间须仍拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ENV.BASE_URL")),
                "点号变体须点名: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("FOO:bar")),
                "未知命名空间须点名: " + result.errors());
        assertTrue(result.errors().stream().noneMatch(e -> e.contains("{{item}}")),
                "同句合法 {{item}} 不得连带报非法: " + result.errors());
    }

    @Test
    void validate_setup合法数组_通过() {
        String json = """
                {"version":"v2","caseId":"TC-RTL-001","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}},
                          {"action":"input","target":{"css":"#user","chain":["css"]},"value":"admin"}],
                 "steps":[{"action":"goto","target":{"url":"{{ENV:URL}}"}}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "合法 setup 数组不得误杀: " + validator.validate(json).errors());
    }


    @Test
    void validate_roles合法声明_三形态引用过闸() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-01","endType":"web",
                 "roles":[{"name":"买家","binding":"per-vu","credential":"login"},
                          {"name":"卖家","binding":"shared"},
                          {"name":"优惠券","binding":"pool(5)"},
                          {"name":"访客","binding":"none"}],
                 "steps":[{"action":"input","target":{"css":"#a","chain":["css"]},
                           "value":"x","saveAs":"订单号"},
                          {"action":"input","target":{"css":"#b","chain":["css"]},
                           "value":"{{买家.token}}{{global.库存}}{{RUN.订单号}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertTrue(result.passed(), "roles 合法声明+三形态引用须过闸: " + result.errors());
    }

    @Test
    void validate_绑定值非法_拒绝人话() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-02","endType":"web",
                 "roles":[{"name":"买家","binding":"perVU"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"}},{"action":"input","target":{"css":"#a","chain":["css"]},"value":"{{买家.token}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "binding=perVU 须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("perVU") && e.contains("买家")),
                "须人话点名角色与非法绑定值: " + result.errors());
    }

    @Test
    void validate_绑定缺省_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-03","endType":"web",
                 "roles":[{"name":"买家","credential":"login"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "缺 binding 须拒");
    }

    @Test
    void validate_pool非正整数_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-04","endType":"web",
                 "roles":[{"name":"优惠券","binding":"pool(0)"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "pool(0) N 须 ≥1");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("pool(0)")),
                "须点名非法 pool 形态: " + result.errors());
    }

    @Test
    void validate_roles重名_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-05","endType":"web",
                 "roles":[{"name":"买家","binding":"per-vu"},{"name":"买家","binding":"shared"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "角色重名须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("买家") && e.contains("重名")),
                "须点名重名角色: " + result.errors());
    }

    @Test
    void validate_角色名撞保留命名空间_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-06","endType":"web",
                 "roles":[{"name":"global","binding":"shared"},{"name":"RUN","binding":"per-vu"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "角色名撞保留字须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("global")),
                "须点名 global: " + result.errors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("RUN")),
                "须点名 RUN: " + result.errors());
    }

    @Test
    void validate_roles非数组_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-07","endType":"web",
                 "roles":"买家",
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "roles 非数组须拒");
    }

    @Test
    void validate_未声明角色裸引用_拒绝防穿透() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-08","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{买家.token}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "无 roles 声明的裸角色引用须拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("买家.token")),
                "须点名穿透 token: " + result.errors());
    }

    @Test
    void validate_无点号角色形态_拒绝() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-09","endType":"web",
                 "roles":[{"name":"买家","binding":"per-vu"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{买家}}"}],
                 "env":{"ENV":"x"}}
                """;
        assertFalse(validator.validate(json).passed(), "{{角色}} 无点号形态须拒");
    }

    @Test
    void validate_global引用免前序声明_过闸() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-10","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{global.库存}}"}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "{{global.键}} 须免声明过闸: " + validator.validate(json).errors());
    }

    @Test
    void validate_RUN引用未声明_链自检点名() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-11","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{RUN.缺的键}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json);
        assertFalse(result.passed(), "{{RUN.未声明键}} 须链自检拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("{{RUN.缺的键}}")),
                "须按原形态点名 RUN 引用: " + result.errors());
    }

    @Test
    void validate_既有未标注VAR零迁移_过闸() {
        String json = """
                {"version":"v2","caseId":"TC-X1-ROLE-12","endType":"web",
                 "steps":[{"action":"input","target":{"css":"#a","chain":["css"]},
                           "value":"x","saveAs":"token"},
                          {"action":"input","target":{"css":"#b","chain":["css"]},"value":"{{VAR:token}}"}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "{{VAR:名}} 存量形态零迁移: " + validator.validate(json).errors());
    }


    @Test
    void validate_裸别名引用_别名表命中放行() {
        String json = """
                {"version":"v2","caseId":"TC-V7-ALIAS-1","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{创建订单.订单号}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json, java.util.Set.of("创建订单"));
        assertTrue(result.passed(), "别名表登记名须放行裸引用: " + result.errors());
    }

    @Test
    void validate_裸别名引用_双未命中穿透拒() {
        String json = """
                {"version":"v2","caseId":"TC-V7-ALIAS-2","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{创建订单.订单号}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json, java.util.Set.of("别的别名"));
        assertFalse(result.passed(), "双未命中裸引用须穿透拒");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("创建订单.订单号")),
                "须人话点名未登记别名: " + result.errors());
    }

    @Test
    void validate_角色声明名优先_别名表空零漂移() {
        String json = """
                {"version":"v2","caseId":"TC-V7-ALIAS-3","endType":"web",
                 "roles":[{"name":"买家","binding":"per-vu"}],
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{买家.token}}"}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "roles 优先序零漂移（别名表空=行为恒等）: " + validator.validate(json).errors());
    }

    @Test
    void validate_纯数字前缀_stepNo直址形不作别名() {
        String json = """
                {"version":"v2","caseId":"TC-V7-ALIAS-4","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"},"text":"{{3.订单号}}"}],
                 "env":{"ENV":"x"}}
                """;
        var result = validator.validate(json, java.util.Set.of("创建订单"));
        assertFalse(result.passed(), "纯数字前缀裸引用（stepNo 直址形）不得经别名面放行");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("3.订单号")),
                "须人话点名非法占位: " + result.errors());
    }

    // ---- sessionState structural gate (04 contract AV extension, save chain) ----

    @Test
    void validate_登录态段缺省_存量零漂移() {
        String json = """
                {"version":"v2","caseId":"TC-AV-1","endType":"web",
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(json).passed(),
                "无 sessionState 段=存量动作 JSON 零漂移: " + validator.validate(json).errors());
    }

    @Test
    void validate_登录态段单选择器_放行() {
        String byName = """
                {"version":"v2","caseId":"TC-AV-2","endType":"web",
                 "sessionState":{"name":"买家已登录"},
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        assertTrue(validator.validate(byName).passed(), "name 单选择器放行");
        String byRole = byName.replace("\"name\":\"买家已登录\"", "\"roleScope\":\"买家\"");
        assertTrue(validator.validate(byRole).passed(), "roleScope 单选择器放行");
    }

    @Test
    void validate_登录态段形态非法_逐条人话拒() {
        String notObject = """
                {"version":"v2","caseId":"TC-AV-3","endType":"web",
                 "sessionState":"买家已登录",
                 "steps":[{"action":"goto","target":{"url":"http://x"}}],
                 "env":{"ENV":"x"}}
                """;
        var r1 = validator.validate(notObject);
        assertFalse(r1.passed(), "非对象必须拒");
        assertTrue(r1.errors().stream().anyMatch(e -> e.contains("sessionState")), "点名段名: " + r1.errors());

        String noSelector = notObject.replace("\"sessionState\":\"买家已登录\"", "\"sessionState\":{}");
        var r2 = validator.validate(noSelector);
        assertFalse(r2.passed(), "既无 name 又无 roleScope 必须拒");
        assertTrue(r2.errors().stream().anyMatch(e -> e.contains("name") && e.contains("roleScope")),
                "须点名二选一: " + r2.errors());

        String both = noSelector.replace("\"sessionState\":{}",
                "\"sessionState\":{\"name\":\"a\",\"roleScope\":\"b\"}");
        assertFalse(validator.validate(both).passed(), "双选择器同现=歧义必须拒（单通道纪律）");

        String placeholder = noSelector.replace("\"sessionState\":{}",
                "\"sessionState\":{\"name\":\"{{登录态}}\"}");
        var r4 = validator.validate(placeholder);
        assertFalse(r4.passed(), "选择器含 {{占位符}} 必须拒——登录态名须为账本字面值");
        assertTrue(r4.errors().stream().anyMatch(e -> e.contains("{{")), "点名占位符非法: " + r4.errors());
    }
}
