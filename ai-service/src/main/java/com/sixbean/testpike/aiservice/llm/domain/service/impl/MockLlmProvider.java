package com.sixbean.testpike.aiservice.llm.domain.service.impl;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * <ul>
 * </ul>
 *
 */
public class MockLlmProvider implements LlmProvider {

 
    public static final String STAGE1_MARKER = LlmExtractMarkers.STAGE1_CASE;

 
    public static final String STAGE2_MARKER = LlmExtractMarkers.STAGE2_ACTION;

 
    public static final String DOC_EXTRACT_MARKER = LlmExtractMarkers.DOC_EXTRACT_REQUIREMENTS;

 
    public static final String DOC_SUMMARY_MARKER = LlmExtractMarkers.DOC_EXTRACT_SUMMARY;

 
    public static final String SPEC_DRAFT_MARKER = LlmExtractMarkers.SPEC_DRAFT_EXTRACT;

 
    public static final String JUDGE_QA_GEN_MARKER = LlmExtractMarkers.JUDGE_QA_GEN;

    /** Drafting marker: stub-bundle JSON contract of the AI mock pipeline (pair of MockDraftGenServiceImpl). */
    public static final String MOCK_DRAFT_GEN_MARKER = LlmExtractMarkers.MOCK_DRAFT_GEN;

    /** Fallback marker: {status,headers,body} answer contract of the A-3 sync generation leg (pair of MockLlmFallbackGenServiceImpl). */
    public static final String MOCK_LLM_FALLBACK_MARKER = LlmExtractMarkers.MOCK_LLM_FALLBACK;

    private final String model;

    public MockLlmProvider(String model) {
        this.model = model;
    }

    @Override
    public String providerName() {
        return "mock";
    }

    @Override
    public LlmChatResult chat(ChatCompletionRequest request) {
        String system = "";
        String lastUser = "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            var msg = request.messages().get(i);
            String text = msg.textContent();
            if ("user".equals(msg.role()) && lastUser.isEmpty()) {
                lastUser = text;
            } else if ("system".equals(msg.role()) && system.isEmpty()) {
                system = text;
            }
        }
        boolean toolResultSeen = false;
        for (var msg : request.messages()) {
            if ("tool".equals(msg.role())) {
                toolResultSeen = true;
                break;
            }
        }
        if (!toolResultSeen && system.contains(LlmExtractMarkers.FC_TOOL_CALL)
                && request.tools() != null && !request.tools().isEmpty()) {
            String firstName = request.tools().get(0).function().name();
            return new LlmChatResult("", "mock", model == null ? "mock-model" : model,
                    10, 20, 3L,
                    List.of(new LlmToolCall("mock-call-1", firstName, "{}")));
        }
        String content;
        if (system.contains(STAGE1_MARKER)) {
            content = stage1Json(lastUser);
        } else if (system.contains(STAGE2_MARKER)) {
            content = stage2Json(lastUser);
        } else if (system.contains(DOC_SUMMARY_MARKER)) {
            content = docSummaryText(lastUser);
        } else if (system.contains(DOC_EXTRACT_MARKER)) {
            content = docExtractJson(lastUser);
        } else if (system.contains(SPEC_DRAFT_MARKER)) {
            content = specDraftJson(lastUser);
        } else if (system.contains(JUDGE_QA_GEN_MARKER)) {
            content = qaGenJson(lastUser);
        } else if (system.contains(MOCK_DRAFT_GEN_MARKER)) {
            content = mockDraftJson(lastUser, system);
        } else if (system.contains(MOCK_LLM_FALLBACK_MARKER)) {
            content = mockFallbackJson(lastUser);
        } else {
            content = "【mock 生成】" + (lastUser.length() > 80 ? lastUser.substring(0, 80) + "…" : lastUser);
        }
        return new LlmChatResult(content, "mock", model == null ? "mock-model" : model,
                10, 20, 3L);
    }

    
    private String qaGenJson(String prompt) {
        String body = "";
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("正文：")) {
                    body = line.substring(3);
                    break;
                }
            }
        }
        StringBuilder items = new StringBuilder();
        for (String raw : body.split("[。；;\n]")) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"question\":\"").append(esc(sentence))
                    .append("\",\"answer\":\"").append(esc(sentence))
                    .append("\",\"sourceAnchor\":\"").append(esc(sentence)).append("\"}");
        }
        return "{\"items\":[" + items + "]}";
    }

    
    private String docSummaryText(String prompt) {
        List<String> heads = new ArrayList<>();
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("正文：") && line.length() > 3) {
                    String body = line.substring(3);
                    int cut = body.indexOf('。');
                    heads.add(cut > 0 ? body.substring(0, cut + 1) : body);
                }
            }
        }
        if (heads.isEmpty()) {
            return "该组摘要：无有效正文。";
        }
        return "该组摘要：" + String.join("", heads);
    }

    
    /**
 * Draft-bundle deterministic output : builds stub items per
 * requirement line of the user prompt (the line prefixed by the drafting
 * label), shaped by the application point named in the system prompt —
 * edge-scenarios answers the full fault playbook (400/401/403/429/500
 * business shapes plus delay and disconnect faults), spec-sync answers
 * contract-sync 200 proposals, anything else stays on the nl-stub single-rule
 * shape. The title/sourceAnchor are verbatim prefixes of the requirement so
 * the stub chain stays observable end to end. A prompt without a requirement
 * line answers {"items":[]} (legal no-op; the orchestration layer owns the
 * all-empty failure).
 */
    private String mockDraftJson(String prompt, String system) {
        String req = "";
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("需求描述：")) {
                    req = line.substring(5);
                    break;
                }
            }
        }
        if (req.isBlank()) {
            return "{\"items\":[]}";
        }
        String firstLine = req.split("\n", 2)[0].trim();
        String method = "GET";
        int sp = firstLine.indexOf(' ');
        if (sp > 0) {
            String head = firstLine.substring(0, sp).toUpperCase(java.util.Locale.ROOT);
            if (head.matches("(GET|POST|PUT|DELETE|PATCH)")) {
                method = head;
            }
        }
        String title = firstLine.substring(0, Math.min(30, firstLine.length()));
        String anchor = firstLine.substring(0, Math.min(60, firstLine.length()));
        String path = "/mock/endpoint-" + Integer.toHexString(firstLine.hashCode());
        if (system != null && system.contains("应用点=edge-scenarios")) {
            return edgeScenarioPack(method, path, title, anchor);
        }
        if (system != null && system.contains("应用点=spec-sync")) {
            return specSyncPack(method, path, title, anchor);
        }
        return "{\"items\":[{\"name\":\"" + esc(title) + "\",\"method\":\"" + method
                + "\",\"path\":\"" + path + "\",\"pathMatchType\":\"exact\","
                + "\"responseJson\":\"{\\\"status\\\":200}\",\"sourceAnchor\":\"" + esc(anchor)
                + "\"}]}";
    }

    /**
 * A-3 fallback answer contract : deterministic
 * {status,headers,body} object built from the unmatched request line of
 * the user prompt — the local stub provider chain answers instantly and
 * shape-legally so the sync generation leg, core's store door and the
 * read side's isObject gate can be exercised end to end without a real
 * provider. The body honestly labels itself as a stand-in answer.
 */
    private String mockFallbackJson(String prompt) {
        String method = "GET";
        String path = "/";
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("未命中的请求：")) {
                    String head = line.substring("未命中的请求：".length()).trim();
                    int sp = head.indexOf(' ');
                    if (sp > 0) {
                        method = head.substring(0, sp).toUpperCase(java.util.Locale.ROOT);
                        path = head.substring(sp + 1).trim();
                    } else if (!head.isEmpty()) {
                        path = head;
                    }
                    break;
                }
            }
        }
        return "{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},"
                + "\"body\":\"{\\\"mockFallback\\\":true,\\\"method\\\":\\\"" + esc(method)
                + "\\\",\\\"path\\\":\\\"" + esc(path) + "\\\"}\"}";
    }

    /**
     * Edge-scenarios playbook (A-2 contract): one happy anchor rule plus the full
     * business-shape spectrum (400/401/403/429/500) and the two fault keys the
     * data plane accepts (delay interval + disconnect) — every item legal under
     * the shared MockFaultRules spectrum so a confirmed pack serves honestly.
     */
    private String edgeScenarioPack(String method, String path, String title, String anchor) {
        // Builder shape mirrors the data plane: the fault key sits at the top
        // level of the response-builder document (MockFaultInjector reads it
        // from the root), never nested inside the body.
        String[][] shapes = {
            {"400 参数错误", "400", "{\"status\":400,\"body\":{\"code\":\"BAD_REQUEST\"}}"},
            {"401 未认证", "401", "{\"status\":401,\"body\":{\"code\":\"UNAUTHORIZED\"}}"},
            {"403 无权限", "403", "{\"status\":403,\"body\":{\"code\":\"FORBIDDEN\"}}"},
            {"429 限流", "429", "{\"status\":429,\"body\":{\"code\":\"TOO_MANY_REQUESTS\"}}"},
            {"500 服务异常", "500", "{\"status\":500,\"body\":{\"code\":\"INTERNAL_ERROR\"}}"},
            {"响应缓慢", "200", "{\"status\":200,\"fault\":{\"type\":\"delay\",\"minMs\":1500,\"maxMs\":3000}}"},
            {"连接中断", "200", "{\"status\":200,\"fault\":{\"type\":\"disconnect\"}}"},
        };
        StringBuilder items = new StringBuilder();
        for (String[] shape : shapes) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"").append(esc(title + " " + shape[0]))
                    .append("\",\"method\":\"").append(method)
                    .append("\",\"path\":\"").append(path).append('/').append(shape[1])
                    .append("\",\"pathMatchType\":\"exact\",")
                    .append("\"responseJson\":").append(jsonString(shape[2]))
                    .append(",\"sourceAnchor\":\"").append(esc(anchor)).append("\"}");
        }
        return "{\"items\":[" + items + "]}";
    }

    /**
     * Spec-sync proposals (A-5 adjacent contract pack): happy-path 200 builders
     * whose body carries a contractSync marker so the confirmation drawer can
     * state "this rule syncs the contract" honestly.
     */
    private String specSyncPack(String method, String path, String title, String anchor) {
        return "{\"items\":[{\"name\":\"" + esc(title + " 契约同步桩")
                + "\",\"method\":\"" + method + "\",\"path\":\"" + path
                + "\",\"pathMatchType\":\"exact\",\"responseJson\":"
                + jsonString("{\"status\":200,\"body\":{\"contractSync\":true,\"endpoint\":\""
                        + path + "\"}}")
                + ",\"sourceAnchor\":\"" + esc(anchor) + "\"}]}";
    }

    /** Embed a JSON document as a JSON string value (responseJson is builder text). */
    private String jsonString(String json) {
        return "\"" + esc(json) + "\"";
    }

    private String specDraftJson(String prompt) {
        String body = "";
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("正文：")) {
                    body = line.substring(3);
                    break;
                }
            }
        }
        if (body.isBlank()) {
            return "{\"items\":[]}";
        }
        String firstLine = body.split("\n", 2)[0].trim();
        String method = "GET";
        String path = "/mock/endpoint-" + Integer.toHexString(firstLine.hashCode());
        int sp = firstLine.indexOf(' ');
        if (sp > 0) {
            String head = firstLine.substring(0, sp).toUpperCase(java.util.Locale.ROOT);
            if (head.matches("(GET|POST|PUT|DELETE|PATCH)")) {
                method = head;
            }
        }
        String title = firstLine.substring(0, Math.min(30, firstLine.length()));
        String anchor = firstLine.substring(0, Math.min(60, firstLine.length()));
        return "{\"items\":[{\"method\":\"" + method + "\",\"path\":\"" + path
                + "\",\"title\":\"" + esc(title) + "\",\"sourceAnchor\":\"" + esc(anchor)
                + "\"}]}";
    }

    
    private String docExtractJson(String prompt) {
        List<String[]> entries = new ArrayList<>();
        if (prompt != null) {
            for (String raw : prompt.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("【第") && line.contains("章节：")) {
                    int cs = line.indexOf("章节：") + 3;
                    int ce = line.indexOf('】', cs);
                    String chapter = ce > cs ? line.substring(cs, ce) : "";
                    if ("（无章节）".equals(chapter)) {
                        chapter = "";
                    }
                    entries.add(new String[]{chapter, ""});
                } else if (!entries.isEmpty() && line.startsWith("正文：")) {
                    int last = entries.size() - 1;
                    entries.set(last, new String[]{entries.get(last)[0], line.substring(3)});
                } else if (!entries.isEmpty() && !line.isEmpty() && !line.startsWith("输出字段")) {
                    int last = entries.size() - 1;
                    entries.set(last, new String[]{entries.get(last)[0],
                            entries.get(last)[1] + line});
                }
            }
        }
        if (entries.isEmpty()) {
            entries.add(new String[]{"", "需求点提取占位"});
        }
        StringBuilder items = new StringBuilder();
        for (String[] entry : entries) {
            String body = entry[1];
            String title = body.isEmpty() ? ("「" + entry[0] + "」章节需求验证")
                    : body.substring(0, Math.min(30, body.length()));
            String criteria = body.isEmpty() ? null : body.substring(0, Math.min(60, body.length()));
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"title\":\"").append(esc(title)).append('"');
            if (criteria != null) {
                items.append(",\"acceptanceCriteria\":\"").append(esc(criteria)).append('"');
            }
            if (!entry[0].isEmpty()) {
                items.append(",\"chapter\":\"").append(esc(entry[0])).append('"');
            }
            // AP-4(3) : the stub mirrors the real upstream shape — a current
            // model answers the five-key contract including the verbatim evidence
            // excerpt (first 40 chars of the chunk body), so the anchor chain
            // (parser → EvidenceChunkMatcher → preview jump) stays observable in
            // the controlled environment (iron law 5e: stubs reproduce real traffic).
            if (!body.isEmpty()) {
                // the stub prompt concatenates trailing prompt lines (closing tag /
                // feedback / knowledge section) into the accumulated body — excerpt
                // only the part that is genuine chunk content, so the anchor is real
                String source = body;
                int cut = source.indexOf("</input>");
                if (cut >= 0) {
                    source = source.substring(0, cut);
                }
                items.append(",\"evidence\":\"")
                        .append(esc(source.substring(0, Math.min(40, source.length())))).append('"');
            }
            items.append(",\"confidence\":0.8}");
        }
        return "{\"requirements\":[" + items + "]}";
    }

    
    private String stage1Json(String requirement) {
        String text = extractRequirement(requirement);
        String title = text.length() > 30 ? text.substring(0, 30) : text;
        String[] sentences = text.split("[。；;\n]");
        StringBuilder steps = new StringBuilder();
        int no = 1;
        for (String s : sentences) {
            String step = s.trim();
            if (step.isEmpty()) {
                continue;
            }
            if (steps.length() > 0) {
                steps.append(',');
            }
            boolean last = no == Math.min(sentences.length, 5);
            steps.append("{\"stepNo\":").append(no)
                    .append(",\"description\":\"").append(esc(step))
                    .append("\",\"isAssert\":").append(last ? 1 : 0).append('}');
            no++;
            if (no > 5) {
                break;
            }
        }
        if (steps.length() == 0) {
            steps.append("{\"stepNo\":1,\"description\":\"").append(esc("执行" + title)).append("\",\"isAssert\":0}");
        }
        String priority = title.contains("登录") || title.contains("支付") ? "0" : "1";
        return "{\"title\":\"" + esc(title) + "\",\"precondition\":\"系统登录页可访问\","
                + "\"steps\":[" + steps + "],\"expectedResult\":\"" + esc(title) + " 执行成功\","
                + "\"priority\":" + priority + ",\"confidence\":2,\"endType\":\"web\"}";
    }

    
    private String extractRequirement(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "用户登录功能";
        }
        String marker = "请针对以下需求生成 1 条结构化测试用例：";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return prompt.trim();
        }
        int reqStart = start + marker.length();
        int end = prompt.indexOf("输出字段：", reqStart);
        String req = end > reqStart ? prompt.substring(reqStart, end) : prompt.substring(reqStart);
        String trimmed = req.trim();
        return trimmed.isEmpty() ? "用户登录功能" : trimmed;
    }

 
    private String stage2Json(String caseInfo) {
        String title = extractCaseTitle(caseInfo);
        String safe = title.length() > 40 ? title.substring(0, 40) : title;
        return "{\"version\":\"v2\",\"caseId\":\"TC-MOCK-001\",\"endType\":\"web\","
                + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
                + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
                + "\"value\":\"{{DATA:testUser}}\"},"
                + "{\"action\":\"input\",\"target\":{\"css\":\"#password\",\"chain\":[\"css\"]},"
                + "\"value\":\"{{ENV:PWD}}\"},"
                + "{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\",\"chain\":[\"css\"]}},"
                + "{\"action\":\"expect\",\"target\":{\"text\":\"" + esc(safe) + "\"}}],"
                + "\"env\":{\"ENV\":\"dev\"}}";
    }

    
    private String extractCaseTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "测试用例";
        }
        String marker = "用例标题：";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return prompt.trim();
        }
        int titleStart = start + marker.length();
        int end = prompt.indexOf('\n', titleStart);
        String title = (end > titleStart ? prompt.substring(titleStart, end) : prompt.substring(titleStart)).trim();
        return title.isEmpty() ? "测试用例" : title;
    }

    
    private String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
