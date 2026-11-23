package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceWxMockClient;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoWxMockServiceTest {

    @Autowired
    private AppautoWxMockService wxMockService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @MockitoBean
    private AiServiceWxMockClient aiServiceWxMockClient;

    @BeforeEach
    void switchOn() {
        configCacheHolder.put(AppautoConfigReader.KEY_WX_MOCK, "1");
    }

    @AfterEach
    void resetSwitch() {
        configCacheHolder.remove(AppautoConfigReader.KEY_WX_MOCK);
    }

    private AppautoWxMockRequest request() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("response", Map.of("idCard", "string", "total", "number"));
        return new AppautoWxMockRequest(7L, "/api/order/list", "POST", contract);
    }

    private Map<String, Object> mockRow(String scenario, Map<String, Object> response) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario);
        row.put("response", response);
        return row;
    }

    @Test
    void switchOffRejectsHumanWordsAndNeverCallsModel() {
        configCacheHolder.put(AppautoConfigReader.KEY_WX_MOCK, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request()));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.wx-mock"), "关态文案须人话说明开关未开：" + ex.getMessage());
        verifyNoInteractions(aiServiceWxMockClient);
    }

    @Test
    void parameterGatesRejectBeforeAnyModelCall() {
        ServiceException noProject = assertThrows(ServiceException.class,
                () -> wxMockService.generate(
                        new AppautoWxMockRequest(null, "/api/order/list", "POST",
                                Map.of("response", Map.of("a", "string")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noProject.getCode());
        ServiceException noPath = assertThrows(ServiceException.class,
                () -> wxMockService.generate(
                        new AppautoWxMockRequest(7L, " ", "POST",
                                Map.of("response", Map.of("a", "string")))));
        assertTrue(noPath.getMessage().contains("接口"), "缺路径文案须人话点名接口路径：" + noPath.getMessage());
        ServiceException noContract = assertThrows(ServiceException.class,
                () -> wxMockService.generate(
                        new AppautoWxMockRequest(7L, "/api/order/list", "POST", Map.of())));
        assertTrue(noContract.getMessage().contains("契约"), "缺契约文案须人话点名契约样例：" + noContract.getMessage());
        verifyNoInteractions(aiServiceWxMockClient);
    }

    @Test
    void sendsGenKitRandomFamilyFromRegistrySingleSource() {
        when(aiServiceWxMockClient.generate(any())).thenReturn(Map.of("mocks", List.of(
                mockRow("normal", Map.of("idCard", GenKit.idCard(7L), "total", 12)))));
        wxMockService.generate(request());
        ArgumentCaptor<AppautoWxMockLlmRequest> captor =
                ArgumentCaptor.forClass(AppautoWxMockLlmRequest.class);
        verify(aiServiceWxMockClient).generate(captor.capture());
        AppautoWxMockLlmRequest sent = captor.getValue();
        List<String> expectedRandomFamily = java.util.Arrays.stream(GenFunction.values())
                .filter(GenFunction::isRandom)
                .map(GenFunction::functionName)
                .toList();
        assertEquals(expectedRandomFamily, sent.genFunctions(),
                "下传函数清单必须精确等于 GenFunction 注册表随机族（单源禁手抄）");
        assertEquals(7L, sent.projectId());
        assertEquals("/api/order/list", sent.apiPath());
        assertEquals("POST", sent.method());
        assertEquals(request().contract(), sent.contract(), "契约样例原样透传禁二次加工");
    }

    @Test
    void prunesFakeCheckDigitKeepsValidAndPlaceholders() {
        String real = GenKit.idCard(7L);
        String fake = real.substring(0, 17) + (real.charAt(17) == '1' ? '2' : '1');
        when(aiServiceWxMockClient.generate(any())).thenReturn(Map.of("mocks", List.of(
                mockRow("normal", Map.of("idCard", real, "total", 12)),
                mockRow("boundary", Map.of("idCard", fake, "total", 0)),
                mockRow("empty-placeholder", Map.of("idCard", "${gen.idCard(seed=9)}")))));
        AppautoWxMockView view = wxMockService.generate(request());
        assertEquals(2, view.mocks().size(), "剪假留真+占位符保留");
        assertEquals(1, view.pruned(), "剪除计数人话留痕");
        assertEquals("normal", view.mocks().get(0).get("scenario"));
        assertEquals("empty-placeholder", view.mocks().get(1).get("scenario"));
    }

    @Test
    void emptyAnswerMocksRejectedAsFormatError() {
        when(aiServiceWxMockClient.generate(any())).thenReturn(Map.of("mocks", List.of()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("没有产出"), "空应答文案须人话：" + ex.getMessage());
    }

    @Test
    void missingMocksKeyRejectedAsFormatError() {
        when(aiServiceWxMockClient.generate(any())).thenReturn(Map.of("data", "x"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode());
    }

    @Test
    void allRowsPrunedGuidesToGenPlaceholder() {
        String real = GenKit.idCard(7L);
        String fake = real.substring(0, 17) + (real.charAt(17) == '1' ? '2' : '1');
        when(aiServiceWxMockClient.generate(any())).thenReturn(Map.of("mocks", List.of(
                mockRow("normal", Map.of("idCard", fake)))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("gen."), "全剪文案须引导占位符写法：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("身份证"), "全剪文案须点名号型：" + ex.getMessage());
    }
}
