package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * delete_case gates membership first, then posts exactly one approval ticket (never the core delete surface); 8) core business failure envelopes ride through in human words.
 *
 */
class RestMcpToolGatewayTest {

    private static final long OPERATOR = 4242L;

    private StubCoreHttpServer core;
    private RestMcpToolGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        core = StubCoreHttpServer.start();
        gateway = new RestMcpToolGateway(core.baseUrl());
    }

    @AfterEach
    void stopStub() {
        core.close();
    }

    // ===== create_case =====

    @Test
    void createCasePostsCoreWithOperatorHeaderAndMcpSourceRef() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":777,\"caseNo\":\"TC-DEMO-0001\",\"projectId\":11,\"title\":\"登录冒烟\",\"status\":1}"));

        String result = gateway.invoke("create_case", Map.of(
                "project_id", 11,
                "title", "登录冒烟",
                "steps", "打开首页并输入账号",
                "expected", "登录成功"), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall call = core.call(1);
        assertEquals(2, core.callCount(), "guard+create, no link hop without requirement_id");
        assertEquals("POST", call.method());
        assertEquals("/internal/cases", call.path());
        assertEquals(String.valueOf(OPERATOR), call.lastOperatorHeader());
        assertTrue(call.body().contains("\"projectId\":11"), call.body());
        assertTrue(call.body().contains("登录冒烟"), call.body());
        assertTrue(call.body().contains("打开首页并输入账号"), "steps 文本须落为步骤描述: " + call.body());
        assertTrue(call.body().contains("登录成功"), "expected 须映射 expectedResult: " + call.body());
        assertTrue(call.body().contains("\"sourceRef\":\"mcp\""), "MCP 来源标注: " + call.body());
        assertTrue(result.contains("\"caseNo\":\"TC-DEMO-0001\""), "创建结果原样回传: " + result);
    }

    @Test
    void createCaseWithRequirementIdLinksAfterCreate() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("{\"id\":888,\"caseNo\":\"TC-DEMO-0002\",\"status\":1}"));
        core.enqueue(StubCoreHttpServer.ok("9"));

        gateway.invoke("create_case", Map.of(
                "project_id", 11,
                "requirement_id", 55,
                "title", "退款回归",
                "steps", "发起退款",
                "expected", "退款到账"), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount());
        StubCoreHttpServer.RecordedCall link = core.call(2);
        assertEquals("POST", link.method());
        assertEquals("/internal/requirements/55/cases", link.path());
        assertTrue(link.body().contains("888"), "关联体 caseIds 须为新建用例 ID: " + link.body());
        assertEquals(String.valueOf(OPERATOR), link.lastOperatorHeader());
    }

    // ===== tag_case =====

    @Test
    void tagCaseResolvesNamesViaProjectDictAndSetsTags() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok(
                "[{\"id\":5,\"projectId\":11,\"name\":\"冒烟\",\"type\":1,\"caseCount\":3},"
                        + "{\"id\":6,\"projectId\":11,\"name\":\"回归\",\"type\":2,\"caseCount\":1}]"));
        core.enqueue(StubCoreHttpServer.ok("[{\"id\":5,\"name\":\"冒烟\"}]"));

        String result = gateway.invoke("tag_case", Map.of(
                "project_id", 11,
                "case_id", 321,
                "tags", List.of("冒烟")), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount());
        StubCoreHttpServer.RecordedCall lookup = core.call(1);
        assertEquals("GET", lookup.method());
        assertEquals("/internal/tags", lookup.path());
        assertEquals("projectId=11", lookup.query());
        StubCoreHttpServer.RecordedCall set = core.call(2);
        assertEquals("PUT", set.method());
        assertEquals("/internal/tags/cases/321/tags", set.path());
        assertTrue(set.body().contains("[5]"), "名称须翻译为字典 ID: " + set.body());
        assertEquals(String.valueOf(OPERATOR), set.lastOperatorHeader());
        assertTrue(result.contains("冒烟"));
    }

    @Test
    void tagCaseRejectsUnknownTagNameWithZeroWriteCalls() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("[{\"id\":5,\"projectId\":11,\"name\":\"冒烟\",\"type\":1,\"caseCount\":3}]"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "tag_case", Map.of("project_id", 11, "case_id", 321, "tags", List.of("字典外标签")), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("字典外标签"), "人话须含被拒标签名: " + ex.getMessage());
        assertEquals(2, core.callCount(), "guard+dict read, zero write calls for unknown tag");
    }

    // ===== get_cases =====

    @Test
    void getCasesPassesEncodedKeywordAndPaging() {
        core.enqueue(StubCoreHttpServer.ok("{\"list\":[],\"total\":0}"));

        gateway.invoke("get_cases", Map.of(
                "project_id", 11, "keyword", "支付 超时", "page", 2, "size", 50), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/cases", call.path());
        String decoded = URLDecoder.decode(call.query(), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("projectId=11"), call.query());
        assertTrue(decoded.contains("keyword=支付 超时"), "单次编码（服务端解一次即还原）: " + call.query());
        assertTrue(decoded.contains("page=2"), call.query());
        assertTrue(decoded.contains("size=50"), call.query());
        assertEquals(String.valueOf(OPERATOR), call.lastOperatorHeader());
    }

    @Test
    void getCasesDefaultsPagingWhenAbsent() {
        core.enqueue(StubCoreHttpServer.ok("{\"list\":[],\"total\":0}"));

        gateway.invoke("get_cases", Map.of("project_id", 11), McpCaller.unrestricted(OPERATOR));

        String query = core.call(0).query();
        assertTrue(query.contains("page=1") && query.contains("size=20"), query);
    }

    @Test
    void getCasesRejectsSuiteFilterFailClosed() {
        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "get_cases", Map.of("project_id", 11, "suite_id", 7), McpCaller.unrestricted(OPERATOR)));
        assertTrue(ex.getMessage().contains("suite"), ex.getMessage());
        assertEquals(0, core.callCount());
    }

    // ===== get_exec_result =====

    @Test
    void getExecResultTakesLatestHistoryEntry() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"list\":[{\"id\":91,\"status\":2,\"durationMs\":1500,\"errorDetail\":null}],\"total\":7}"));

        String result = gateway.invoke("get_exec_result",
                Map.of("project_id", 11, "case_id", 321), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/executors/cases/321/history", call.path());
        assertTrue(call.query().contains("projectId=11"), call.query());
        assertTrue(call.query().contains("size=1"), "最近一次=首页 1 条: " + call.query());
        assertTrue(result.contains("\"id\":91"), result);
    }

    // ===== get_script / list_requirements =====

    @Test
    void getScriptReadsByCaseId() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":12,\"caseId\":321,\"schemaVersion\":\"v2\",\"actionJson\":\"{}\",\"version\":3}"));

        String result = gateway.invoke("get_script", Map.of("case_id", 321), McpCaller.unrestricted(OPERATOR));

        assertEquals("/internal/scripts/321", core.call(0).path());
        assertTrue(result.contains("\"schemaVersion\":\"v2\""), result);
    }

    @Test
    void listRequirementsPassesPaging() {
        core.enqueue(StubCoreHttpServer.ok("{\"list\":[],\"total\":0}"));

        gateway.invoke("list_requirements", Map.of("project_id", 11), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/requirements", call.path());
        assertTrue(call.query().contains("projectId=11"), call.query());
    }


    @Test
    void deleteCasePostsDeleteRequestWithOperatorHeader() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":9,\"targetType\":1,\"targetId\":321,\"projectId\":11,\"status\":2,"
                        + "\"statusLabel\":\"待审批\",\"reason\":\"重复用例\"}"));

        String result = gateway.invoke("delete_case",
                Map.of("project_id", 11, "case_id", 321, "reason", "重复用例"), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall call = core.call(1);
        assertEquals(2, core.callCount(), "guard+approval ticket, delete_case never touches the core delete surface");
        assertEquals("POST", call.method());
        assertEquals("/internal/delete-requests", call.path());
        assertEquals(String.valueOf(OPERATOR), call.lastOperatorHeader(), "申请人=Token 属主");
        assertTrue(call.body().contains("\"targetType\":1"), "用例径 targetType=1: " + call.body());
        assertTrue(call.body().contains("\"targetId\":321"), call.body());
        assertTrue(call.body().contains("\"projectId\":11"), call.body());
        assertTrue(call.body().contains("重复用例"), "申请理由透传: " + call.body());
        assertTrue(result.contains("\"statusLabel\":\"待审批\""), "审批单 JSON 原样回传: " + result);
    }

    // ===== Case-write membership gate (external-agent red line: guard first, business door second) =====

    @Test
    void createCaseGatesMemberCheckBeforeAnyWriteHop() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("{\"id\":777,\"caseNo\":\"TC-DEMO-0001\",\"status\":1}"));

        gateway.invoke("create_case", Map.of(
                "project_id", 11, "title", "t", "steps", "s", "expected", "e"), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall guard = core.call(0);
        assertEquals("POST", guard.method());
        assertEquals("/internal/projects/access-check", guard.path());
        assertTrue(guard.body().contains("\"projectId\":11"), guard.body());
        assertTrue(guard.body().contains("\"userId\":" + OPERATOR), guard.body());
        assertTrue(guard.body().contains("\"requiredRole\":\"MEMBER\""), guard.body());
        assertEquals("/internal/cases", core.call(1).path());
    }

    @Test
    void createCaseRefusesNonMemberBeforeFirstWriteHop() {
        core.enqueue(StubCoreHttpServer.fail("40301", "非项目成员，无权访问"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "create_case", Map.of("project_id", 11, "title", "t", "steps", "s", "expected", "e"),
                McpCaller.unrestricted(OPERATOR)));

        assertEquals("非项目成员，无权访问", ex.getMessage());
        assertEquals(1, core.callCount(), "guard refusal = zero write hops");
    }

    @Test
    void tagCaseGatesMemberCheckBeforeDictAndPut() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("[{\"id\":5,\"projectId\":11,\"name\":\"冒烟\",\"type\":1,\"caseCount\":3}]"));
        core.enqueue(StubCoreHttpServer.ok("[{\"id\":5,\"name\":\"冒烟\"}]"));

        gateway.invoke("tag_case",
                Map.of("project_id", 11, "case_id", 321, "tags", List.of("冒烟")), McpCaller.unrestricted(OPERATOR));

        assertEquals("/internal/projects/access-check", core.call(0).path());
        assertEquals("/internal/tags", core.call(1).path());
        assertEquals("/internal/tags/cases/321/tags", core.call(2).path());
    }

    @Test
    void deleteCaseRefusesNonMemberBeforeApprovalTicket() {
        core.enqueue(StubCoreHttpServer.fail("40301", "非项目成员，无权访问"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "delete_case", Map.of("project_id", 11, "case_id", 321, "reason", "dup"), McpCaller.unrestricted(OPERATOR)));

        assertEquals("非项目成员，无权访问", ex.getMessage());
        assertEquals(1, core.callCount(), "guard refusal = zero approval-ticket writes");
    }


    @Test
    void coreBusinessFailureMessagePropagatesForIsError() {
        core.enqueue(StubCoreHttpServer.fail("40401", "项目不存在或无权限访问"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "get_script", Map.of("case_id", 999), McpCaller.unrestricted(OPERATOR)));
        assertEquals("项目不存在或无权限访问", ex.getMessage());
    }

 // ===== Mock family : guard composition = access-check first,
 // business door second =====

    @Test
    void listMockSpacesGatesMemberCheckBeforeSpaceList() {
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok(
                "[{\"id\":5,\"projectId\":11,\"spaceKey\":\"mk_a\",\"status\":1,\"ruleCount\":3}]"));

        String result = gateway.invoke("list_mock_spaces", Map.of("project_id", 11), McpCaller.unrestricted(OPERATOR));

        assertEquals(2, core.callCount(), "恰两跳：守卫门+列表门");
        StubCoreHttpServer.RecordedCall guard = core.call(0);
        assertEquals("POST", guard.method());
        assertEquals("/internal/projects/access-check", guard.path());
        assertTrue(guard.body().contains("\"requiredRole\":\"MEMBER\""), guard.body());
        assertTrue(guard.body().contains("\"userId\":" + OPERATOR), "属主=守卫判定原点: " + guard.body());
        StubCoreHttpServer.RecordedCall list = core.call(1);
        assertEquals("/internal/mock/space/list", list.path());
        assertTrue(list.query().contains("projectId=11"), list.query());
        assertTrue(result.contains("mk_a"), "列表 JSON 原样回传: " + result);
    }

    @Test
    void inspectMockHitsResolvesSpaceProjectThenGates() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":5,\"projectId\":11,\"status\":1}")); // space/get
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check passes
        core.enqueue(StubCoreHttpServer.ok("{\"list\":[],\"total\":0}")); // hit/list

        gateway.invoke("inspect_mock_hits",
                Map.of("space_id", 5, "matched", true, "page", 2, "page_size", 50), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "反查归属→成员闸→账本页，恰三跳");
        assertEquals("/internal/mock/space/get", core.call(0).path());
        assertEquals("/internal/projects/access-check", core.call(1).path());
        assertTrue(core.call(1).body().contains("\"projectId\":11"),
                "守卫项目=空间归属项目（非入参可伪造）: " + core.call(1).body());
        StubCoreHttpServer.RecordedCall hits = core.call(2);
        assertEquals("/internal/mock/hit/list", hits.path());
        assertTrue(hits.query().contains("spaceId=5"), hits.query());
        assertTrue(hits.query().contains("matched=true"), hits.query());
        assertTrue(hits.query().contains("page=2"), hits.query());
        assertTrue(hits.query().contains("pageSize=50"), hits.query());
    }

    @Test
    void inspectMockHitsRefusesForeignSpaceBeforeLeakingPage() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":5,\"projectId\":11,\"status\":1}"));
        core.enqueue(StubCoreHttpServer.fail("40301", "非项目成员，无权访问"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "inspect_mock_hits", Map.of("space_id", 5), McpCaller.unrestricted(OPERATOR)));

        assertEquals("非项目成员，无权访问", ex.getMessage());
        assertEquals(2, core.callCount(), "守卫拒=零触账本门，跨项目空间不外泄一行");
    }

    @Test
    void createMockStubMapsAgentArgsOntoSaveChainPayload() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":5,\"projectId\":11}")); // space/get
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check
        core.enqueue(StubCoreHttpServer.ok("77")); // stub/create → new id

        String result = gateway.invoke("create_mock_stub", Map.of(
                "space_id", 5,
                "name", "登录成功",
                "method", "POST",
                "path_pattern", "/api/login",
                "response", Map.of("status", 200, "body", "{\"ok\":true}")), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount());
        StubCoreHttpServer.RecordedCall create = core.call(2);
        assertEquals("POST", create.method());
        assertEquals("/internal/mock/stub/create", create.path());
        assertTrue(create.query().contains("spaceId=5"), create.query());
        assertTrue(create.body().contains("\"pathMatchType\":\"exact\""),
                "缺省匹配型=exact（core 保存链同源）: " + create.body());
        assertTrue(create.body().contains("\"source\":\"manual\""), create.body());
        assertTrue(create.body().contains("200"), "响应构造透传入 responseJson: " + create.body());
        assertEquals("77", result, "新行 id 原样回传");
    }

    @Test
    void publishMockSpaceReadsVersionEchoAndTransitionsToPublished() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":5,\"projectId\":11,\"status\":0,\"version\":3}")); // space/get
        core.enqueue(StubCoreHttpServer.ok("null")); // access-check
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":5,\"projectId\":11,\"status\":1,\"version\":4}")); // transition result

        String result = gateway.invoke("publish_mock_space", Map.of("space_id", 5), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "一行读双用（归属+版本）→守卫→转换，零重复读");
        StubCoreHttpServer.RecordedCall transition = core.call(2);
        assertEquals("POST", transition.method());
        assertEquals("/internal/mock/space/transition", transition.path());
        assertTrue(transition.query().contains("targetStatus=1"), transition.query());
        assertTrue(transition.query().contains("expectedVersion=3"),
                "缺省钉版=刚守卫的行版本: " + transition.query());
        assertTrue(result.contains("\"status\":1"), "发布结果原样回传: " + result);
    }

    @Test
    void publishMockSpaceHonoursExplicitVersionEcho() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":5,\"projectId\":11,\"version\":3}"));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok("{\"id\":5,\"status\":1,\"version\":9}"));

        gateway.invoke("publish_mock_space",
                Map.of("space_id", 5, "expected_version", 8), McpCaller.unrestricted(OPERATOR));

        assertTrue(core.call(2).query().contains("expectedVersion=8"),
                "显式钉版优先（乐观锁诚实语义不被缺省读覆盖）: " + core.call(2).query());
    }

    @Test
    void mockWriteToolsRefuseGhostSpaceWithoutAnyBusinessCall() {
        core.enqueue(StubCoreHttpServer.fail("40401", "Mock 空间不存在"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "create_mock_stub", Map.of("space_id", 999, "name", "n",
                        "method", "GET", "path_pattern", "/x"), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("Mock 空间不存在"), ex.getMessage());
        assertEquals(1, core.callCount(), "反查失败即止：守卫门与创建门零外呼");
    }

    // ===== document channel: doc.upload / doc_task.poll / doc_task.cancel =====

    private static String base64Of(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static final String DOC_ROW_3001 =
            "{\"id\":3001,\"projectId\":11,\"name\":\"login-spec\",\"fileType\":\"pdf\","
                    + "\"status\":0,\"chunkCount\":0,\"errorMsg\":null}";

    @Test
    void docUploadGatesMembershipThenPostsMultipartAndQueuesExtraction() {
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(DOC_ROW_3001)); // multipart upload
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":11,\"status\":3}")); // extract hop

        String result = gateway.invoke("doc.upload", Map.of(
                "project_id", 11, "name", "login-spec.pdf",
                "content_base64", base64Of("# login requirement\n".getBytes(
                        StandardCharsets.UTF_8))), McpCaller.unrestricted(OPERATOR));

        assertEquals(3, core.callCount(), "gate+upload+extract, no other hop");
        StubCoreHttpServer.RecordedCall upload = core.call(1);
        assertEquals("POST", upload.method());
        assertEquals("/internal/documents", upload.path());
        assertTrue(upload.query().contains("projectId=11"), upload.query());
        assertTrue(upload.contentType().stream().anyMatch(c -> c.startsWith("multipart/")),
                "原件必须走 multipart 件（与站内上传同一 door）: " + upload.contentType());
        assertTrue(upload.body().contains("filename=\"login-spec.pdf\""),
                "展示名决定解析格式: " + upload.body());
        assertTrue(upload.body().contains("# login requirement"),
                "解码后的原件字节随 multipart 件落库: " + upload.body());
        assertEquals(String.valueOf(OPERATOR), upload.lastOperatorHeader());
        StubCoreHttpServer.RecordedCall extract = core.call(2);
        assertEquals("POST", extract.method());
        assertEquals("/internal/documents/3001/extract", extract.path());
        assertTrue(result.contains("\"document_id\":3001"), result);
        assertTrue(result.contains("\"task_id\":3001"), result);
    }

    @Test
    void docUploadOverSizeLimitRefusesNamingCurrentBytesLimitAndKey() {
        RestMcpToolGateway tinyCap = new RestMcpToolGateway(core.baseUrl(), () -> 8L);
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate runs first

        ServiceException ex = assertThrows(ServiceException.class, () -> tinyCap.invoke(
                "doc.upload", Map.of("project_id", 11, "name", "big.pdf",
                        "content_base64", base64Of(new byte[16])), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("16"), "点名当前大小: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("8"), "点名上限: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("mcp.doc.max-bytes"),
                "关闭/调整通道指路（键字面）: " + ex.getMessage());
        assertEquals(1, core.callCount(), "超限零写外呼（不入上传 door）");
    }

    @Test
    void docUploadDryRunPreflightMakesNoWriteHop() {
        core.enqueue(StubCoreHttpServer.ok("null"));

        String result = gateway.invoke("doc.upload", Map.of(
                "project_id", 11, "name", "login-spec.pdf",
                "content_base64", base64Of("hi".getBytes(StandardCharsets.UTF_8)),
                "dry_run", true), McpCaller.unrestricted(OPERATOR));

        assertEquals(1, core.callCount(), "预检半拍不入库、不入队");
        assertTrue(result.contains("\"dry_run\":true"), result);
        assertTrue(result.contains("\"accepted\":true"), result);
    }

    @Test
    void docTaskPollMapsStateProgressCountAndFidelity() {
        core.enqueue(StubCoreHttpServer.ok(DOC_ROW_3001)); // task→project anchor
        core.enqueue(StubCoreHttpServer.ok("null")); // member gate
        core.enqueue(StubCoreHttpServer.ok(
                "{\"documentId\":3001,\"status\":3,\"extractError\":null,\"page\":1,"
                        + "\"size\":1,\"total\":42,\"items\":[],\"stageLedger\":["
                        + "{\"code\":\"group-2\",\"label\":\"grouping 2/5\","
                        + "\"at\":\"2026-11-17T18:00:00\"}],\"fidelity\":"
                        + "{\"retentionPercent\":93.5}}"));

        String result = gateway.invoke("doc_task.poll", Map.of("task_id", 3001), McpCaller.unrestricted(OPERATOR));

        StubCoreHttpServer.RecordedCall preview = core.call(2);
        assertEquals("GET", preview.method());
        assertEquals("/internal/documents/3001/extraction", preview.path());
        assertTrue(preview.query().contains("size=1"), "poll 只要状态面，不拖条目页: "
                + preview.query());
        assertTrue(result.contains("\"state\":\"extracting\""), result);
        assertTrue(result.contains("\"requirement_count\":42"), result);
        assertTrue(result.contains("grouping 2/5"), "进度=最近落账步骤文本（诚实现读）: " + result);
        assertTrue(result.contains("\"retentionPercent\":93.5"),
                "忠实账摘要原样透出: " + result);
    }

    @Test
    void docTaskPollSurfacesParseFailureReasonHumanWords() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":11,\"name\":\"login-spec\",\"fileType\":\"pdf\","
                        + "\"status\":2,\"chunkCount\":0,\"errorMsg\":\"解析失败：文件损坏\"}"));
        core.enqueue(StubCoreHttpServer.ok("null"));

        String result = gateway.invoke("doc_task.poll", Map.of("task_id", 3001), McpCaller.unrestricted(OPERATOR));

        assertTrue(result.contains("\"state\":\"parse_failed\""), result);
        assertTrue(result.contains("解析失败：文件损坏"), "失败人话不折叠: " + result);
    }

    @Test
    void docTaskCancelOnTerminalStateReturnsIdempotentReceiptWithCurrentState() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":11,\"status\":4,\"errorMsg\":null}"));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.fail("40902", "该文档当前没有进行中的提取任务，无需取消提取"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"documentId\":3001,\"status\":4,\"total\":12,\"items\":[]}"));

        String result = gateway.invoke("doc_task.cancel", Map.of("task_id", 3001), McpCaller.unrestricted(OPERATOR));

        assertEquals(4, core.callCount());
        assertEquals("/internal/documents/3001/extraction-cancel", core.call(2).path());
        assertTrue(result.contains("\"cancelled\":false"), result);
        assertTrue(result.contains("\"state\":\"extracted\""), result);
    }

    @Test
    void docTaskCancelActiveTaskEchoesRolledBackState() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":11,\"status\":3,\"errorMsg\":null}"));
        core.enqueue(StubCoreHttpServer.ok("null"));
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":11,\"status\":1,\"errorMsg\":null}"));

        String result = gateway.invoke("doc_task.cancel", Map.of("task_id", 3001), McpCaller.unrestricted(OPERATOR));

        assertTrue(result.contains("\"cancelled\":true"), result);
        assertTrue(result.contains("\"state\":\"parsed\""), "取消后回到可再提取态: " + result);
    }

    @Test
    void docChannelReadsSurfaceForeignProjectAsPlainRefusal() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"id\":3001,\"projectId\":99,\"status\":4,\"errorMsg\":null}"));
        core.enqueue(StubCoreHttpServer.fail("40301", "无权访问该项目"));

        ServiceException ex = assertThrows(ServiceException.class, () -> gateway.invoke(
                "doc_task.poll", Map.of("task_id", 3001), McpCaller.unrestricted(OPERATOR)));

        assertTrue(ex.getMessage().contains("无权访问该项目"), ex.getMessage());
        assertEquals(2, core.callCount(), "越域即止：预览 door 零外呼");
    }
}
