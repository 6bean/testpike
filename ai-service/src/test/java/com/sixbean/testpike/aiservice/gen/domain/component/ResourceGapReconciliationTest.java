package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.ResourceGapDeclaration.GapItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side mechanical gap reconciliation ( slice-c addendum, ruling
 * "c extra live audit" 2026-11-12): the model DOES build structured
 * placeholders for resources it lacks ({@code {{ENV:SMS_GATEWAY_SEND_URL}}}
 * with a self-describing step text — proven live on tasks 2908/2909), while
 * its voluntary {@code missingResources[]} reply missed 2/2 rounds. So the
 * server no longer waits for the model: every {@code {{ENV:KEY}}}/{@code
 * {{DATA:KEY}}} placeholder key and every top-level env-block ENV/DATA value
 * is scored against the FULL pool rows through the slice-b scoring single
 * source; a reference hitting no row at all becomes a ledger declaration
 * malformed drafts declare nothing (never block generation, never pad).
 * <p>Bounds shared with {@link ResourceGapDeclaration}: 8 items max, 120
 * chars per field, dedup on the category text, union with model-declared
 * gaps keeping the model entries first.
 */
class ResourceGapReconciliationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> row(String name, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        return m;
    }

    /** The live spec-C fixture pool: factories / collection / env URLs,
     * none of them carrying an sms-gateway token. */
    private static List<Map<String, Object>> poolWithoutSmsGateway() {
        List<Map<String, Object>> pool = new ArrayList<>();
        pool.add(row("pool-ws1.example.com", "池环境"));
        pool.add(row("资源池压测工厂33", ""));
        pool.add(row("物流面单池-运单号核验", ""));
        pool.add(row("api7", "接口集"));
        return pool;
    }

    /** Mirrors the persisted task-2908 draft: one dangling send placeholder
     * plus an env block whose ENV value is the real pool URL (must NOT add
     * noise declarations). */
    private static String danglingPlaceholderDraft() {
        return """
                {"version":"v2","caseId":"c1",
                 "setup":[{"action":"goto","url":"https://pool-ws1.example.com/login",
                           "description":"打开登录页"}],
                 "steps":[
                   {"action":"http.request","url":"{{ENV:BASE_URL}}/api/send",
                    "description":"调用短信验证码网关发送验证码；因缺短信验证码网关资源，当前无法构造完整请求体与断言。"},
                   {"action":"http.request","url":"{{ENV:BASE_URL}}/api/query",
                    "description":"查询验证码到达状态"}],
                 "env":{"ENV":"pool-ws1.example.com","DATA":"资源池压测工厂33","PACK":"default"}}""";
    }

    @Test
    void danglingEnvPlaceholderAgainstTokenlessPoolDeclaresExactlyOneGap() {
        // BASE_URL is the only reference absent from the pool; the env-block
        // values echo real pool rows (URL + factory full name) so they stay
        // silent, and the two placeholders share one key -> one row (dedup).
        List<GapItem> gaps = ResourceGapReconciliation.reconcile(mapper,
                danglingPlaceholderDraft(), poolWithoutSmsGateway());

        assertEquals(1, gaps.size(), "exactly one mechanical declaration expected");
        assertEquals("环境占位 BASE_URL", gaps.get(0).category());
        assertEquals("调用短信验证码网关发送验证码；因缺短信验证码网关资源，当前无法构造完整请求体与断言。",
                gaps.get(0).intent(),
                "the enclosing step description rides the declaration");
    }

    @Test
    void fullPoolNameReferenceRidesTheScoringSingleSourceAndDeclaresNothing() {
        String draft = """
                {"version":"v2","caseId":"c2",
                 "steps":[{"action":"input","value":"{{DATA:资源池压测工厂33}}",
                           "description":"从数据工厂取数"}],
                 "env":{"ENV":"pool-ws1.example.com","DATA":"资源池压测工厂33","PACK":"default"}}""";

        assertEquals(List.of(),
                ResourceGapReconciliation.reconcile(mapper, draft, poolWithoutSmsGateway()),
                "a reference hitting a pool row by full name must never be declared");
    }

    @Test
    void draftWithoutReferencesDeclaresNothingAndPayloadStaysUntouched() {
        String draft = """
                {"version":"v2","caseId":"c3",
                 "steps":[{"action":"click","target":{"css":"#btn","chain":["css"]},
                           "description":"点击提交"}],
                 "env":{"ENV":"pool-ws1.example.com","DATA":"资源池压测工厂33","PACK":"default"}}""";

        assertEquals(List.of(),
                ResourceGapReconciliation.reconcile(mapper, draft, poolWithoutSmsGateway()));
        assertTrue(draft.contains("\"PACK\":\"default\""),
                "reconciliation is a read-only surface — no payload rewrite");
    }

    @Test
    void malformedDraftFoldsToSilenceNeverThrows() {
        String broken = "}{ not a json {{ENV:BASE_URL}}";

        List<GapItem> gaps = assertDoesNotThrow(() ->
                ResourceGapReconciliation.reconcile(mapper, broken, poolWithoutSmsGateway()),
                "the honest audit surface must never break the generation chain");
        assertEquals(List.of(), gaps, "unreadable draft = no declaration (no padding)");
    }

    @Test
    void unionDeduplicatesOnCategoryTextAndCapsAtEight() {
        List<GapItem> modelGaps = List.of(new GapItem("第三方支付沙箱", "模拟支付回调"));
        List<GapItem> serverGaps = List.of(
                new GapItem("第三方支付沙箱", "重复类目应并入"),
                new GapItem("环境占位 SMS_URL", "发送"),
                new GapItem("环境占位 QUERY_URL", "查询"));

        List<GapItem> merged = ResourceGapReconciliation.union(modelGaps, serverGaps);

        assertEquals(3, merged.size(), "same category text merges into one row");
        assertEquals("第三方支付沙箱", merged.get(0).category());
        assertEquals("模拟支付回调", merged.get(0).intent(),
                "the model wording wins on a shared category");

        List<GapItem> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new GapItem("环境占位 KEY_" + i, ""));
        }
        assertEquals(8, ResourceGapReconciliation.union(List.of(), many).size(),
                "the accumulating ledger stays bounded at 8 rows (R57)");
    }
}
